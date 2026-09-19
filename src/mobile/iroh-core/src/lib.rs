//! iroh-core：Android 端 iroh 原生核心（JNI）。
//!
//! 由 Kotlin 侧 `IrohCore.kt` 通过 JNI 调用，负责：
//!  1. 解析信令网桥的 iroh ticket（iroh-tickets 1.0.0，与 napi 侧 EndpointTicket 同源），
//!     建立到网桥端点的 QUIC 连接（iroh 自动 NAT 穿透 + relay 兜底）；
//!  2. 以换行分隔的 JSON 文本流与网桥互发信令/姿态消息；
//!  3. 把收到的每条消息通过 `IrohCore.onMessage(handle, json)` 回调给 Kotlin 侧。
//!
//! iroh 版本固定为 1.1.0（见 Cargo.toml），以匹配 Node 端 @number0/iroh@1.1.0。

use std::collections::HashMap;
use std::str::FromStr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use iroh::endpoint::presets;
use iroh::endpoint::{Connection, SendStream};
use iroh::{Endpoint, EndpointAddr};
use iroh_tickets::endpoint::EndpointTicket;
use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jlong, JNI_VERSION_1_8};
use jni::JNIEnv;
use jni::JavaVM;
use once_cell::sync::OnceCell;
use tokio::io::AsyncWriteExt;
use tokio::sync::mpsc;
use tokio::sync::Mutex as AsyncMutex;

/// 与 Node 端 irohBridge.ts 保持一致的 ALPN
const ALPN: &[u8] = b"screen-share/1";

/// 全局 tokio 运行时（JNI 调用来自 Java 线程，需要多线程运行时）
static RUNTIME: OnceCell<tokio::runtime::Runtime> = OnceCell::new();

/// 保存 JavaVM，供读线程回调 Kotlin 使用
static JVM: OnceCell<JavaVM> = OnceCell::new();

/// 连接句柄计数器
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

/// 一条活跃连接：保留 Connection（防止被 drop 而关闭）、控制流、媒体发送队列
struct Conn {
    /// 仅用于保活：持有 Connection，防止所有句柄被 drop 后连接自动关闭。
    /// 实际的收发都走 clone 出去的句柄（控制流用 send，媒体用 writer 任务）。
    #[allow(dead_code)]
    conn: Connection,
    /// 控制消息（换行分隔 JSON）发送流
    send: Arc<AsyncMutex<SendStream>>,
    /// 媒体帧队列：投递非阻塞，队满丢帧（实时视频宁可丢帧也不阻塞采集线程）
    media_tx: mpsc::Sender<Vec<u8>>,
    /// 用于通知读任务退出
    close_tx: mpsc::Sender<()>,
}

static CONNS: OnceCell<Mutex<HashMap<u64, Conn>>> = OnceCell::new();

fn conns() -> &'static Mutex<HashMap<u64, Conn>> {
    CONNS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("failed to build tokio runtime")
    })
}

/// JNI_OnLoad：缓存 JavaVM
#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM) -> jni::sys::jint {
    let _ = JVM.set(vm);
    JNI_VERSION_1_8
}

/// `IrohCore.connect(ticket, room, role) -> handle`
/// 阻塞直到连接建立并发送 register，返回连接句柄（>0）。失败返回 0。
#[no_mangle]
pub extern "system" fn Java_com_screenshare_webrtc_IrohCore_connect(
    mut env: JNIEnv,
    _class: JClass,
    ticket: JString,
    room: JString,
    _role: JString,
) -> jlong {
    let ticket_str = match env.get_string(&ticket) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let room_str = match env.get_string(&room) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let rt = runtime();
    match rt.block_on(connect_async(ticket_str, room_str)) {
        Ok((handle, _)) => handle as jlong,
        Err(e) => {
            eprintln!("iroh connect failed: {e:?}");
            0
        }
    }
}

async fn connect_async(ticket: String, room: String) -> anyhow::Result<(u64, ())> {
    // 解析网桥 ticket -> EndpointAddr（iroh-tickets 与 napi 侧 EndpointTicket 同源同版本）
    let ticket = EndpointTicket::from_str(&ticket)
        .map_err(|e| anyhow::anyhow!("bad ticket: {e}"))?;
    let node_addr: EndpointAddr = ticket.endpoint_addr().clone();

    let endpoint = Endpoint::builder(presets::N0)
        .alpns(vec![ALPN.to_vec()])
        .bind()
        .await
        .map_err(|e| anyhow::anyhow!("bind: {e}"))?;

    let conn = endpoint
        .connect(node_addr, ALPN)
        .await
        .map_err(|e| anyhow::anyhow!("connect: {e}"))?;

    let (mut send, mut recv) = conn
        .open_bi()
        .await
        .map_err(|e| anyhow::anyhow!("open_bi: {e}"))?;

    // 发送 register
    let register = serde_json::json!({ "type": "register", "room": room }).to_string() + "\n";
    send.write_all(register.as_bytes())
        .await
        .map_err(|e| anyhow::anyhow!("write register: {e}"))?;
    send.flush()
        .await
        .map_err(|e| anyhow::anyhow!("flush: {e}"))?;

    let handle = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
    let send = Arc::new(AsyncMutex::new(send));

    // 媒体发送任务：每帧开一条 uni stream。
    // 不复用同一条流的原因：QUIC 流内严格有序，丢一个包会阻塞后续所有帧（HOL 阻塞），
    // 实时视频宁可让 QUIC 在流间各自重传，由接收端按 pts 重排。
    let (media_tx, mut media_rx) = mpsc::channel::<Vec<u8>>(64);
    let media_conn = conn.clone();
    tokio::spawn(async move {
        while let Some(frame) = media_rx.recv().await {
            let Ok(mut uni) = media_conn.open_uni().await else {
                break;
            };
            if uni.write_all(&frame).await.is_err() {
                break;
            }
            let _ = uni.finish();
        }
    });

    // 退出信号
    let (close_tx, mut close_rx) = mpsc::channel::<()>(1);
    conns().lock().unwrap().insert(
        handle,
        Conn {
            conn,
            send: send.clone(),
            media_tx,
            close_tx: close_tx.clone(),
        },
    );

    // 读线程：逐行解析 JSON 并回调 Kotlin
    let handle_for_task = handle;
    tokio::spawn(async move {
        let mut buf = [0u8; 4096];
        let mut line = String::new();
        loop {
            tokio::select! {
                _ = close_rx.recv() => break,
                read = recv.read(&mut buf) => {
                    // iroh RecvStream::read 返回 Ok(Some(n))；Ok(None)/Err 表示流结束
                    let n = match read {
                        Ok(Some(n)) => n,
                        Ok(None) => break,
                        Err(_) => break,
                    };
                    if n == 0 { break; }
                    line.push_str(&String::from_utf8_lossy(&buf[..n]));
                    while let Some(idx) = line.find('\n') {
                        let raw = line[..idx].to_string();
                        line.drain(..idx + 1);
                        let raw = raw.trim();
                        if raw.is_empty() { continue; }
                        callback(handle_for_task, raw);
                    }
                }
            }
        }
        // 连接结束，清理
        conns().lock().unwrap().remove(&handle_for_task);
    });

    Ok((handle, ()))
}

/// 回调 Kotlin 静态方法 IrohCore.onMessage(handle, json)
fn callback(handle: u64, json: &str) {
    let vm = match JVM.get() {
        Some(vm) => vm,
        None => return,
    };
    let mut env = match vm.attach_current_thread() {
        Ok(env) => env,
        Err(_) => return,
    };
    let jstr = match env.new_string(json) {
        Ok(s) => s,
        Err(_) => return,
    };
    let obj: JObject = jstr.into();
    let _ = env.call_static_method(
        "com/screenshare/webrtc/IrohCore",
        "onMessage",
        "(JLjava/lang/String;)V",
        &[JValue::Long(handle as i64), JValue::Object(&obj)],
    );
}

/// `IrohCore.sendMsg(handle, message)`
#[no_mangle]
pub extern "system" fn Java_com_screenshare_webrtc_IrohCore_sendMsg(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    message: JString,
) {
    let msg: String = match env.get_string(&message) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let rt = runtime();
    rt.block_on(async move {
        // 先取出发送流句柄，避免在 .await 期间持有 std MutexGuard
        let send = {
            let guard = conns().lock().unwrap();
            guard.get(&(handle as u64)).map(|c| c.send.clone())
        };
        if let Some(send) = send {
            let mut s = send.lock().await;
            let _ = s.write_all(msg.as_bytes()).await;
            let _ = s.flush().await;
        }
    });
}

/// `IrohCore.sendMediaFrame(handle, frame)`
///
/// frame 是已编码的完整一帧：13 字节头（flags|ptsUs|length，见 Desktop 端
/// shared/protocol.ts）+ Annex-B H.264 载荷。
/// 这里只做非阻塞投递，真正的 QUIC 写入在后台任务里；队列满则丢帧，
/// 绝不阻塞 Android 的采集/编码线程。
#[no_mangle]
pub extern "system" fn Java_com_screenshare_webrtc_IrohCore_sendMediaFrame(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    frame: JByteArray,
) {
    let bytes = match env.convert_byte_array(&frame) {
        Ok(b) => b,
        Err(_) => return,
    };
    let tx = {
        let guard = conns().lock().unwrap();
        guard.get(&(handle as u64)).map(|c| c.media_tx.clone())
    };
    if let Some(tx) = tx {
        let _ = tx.try_send(bytes);
    }
}

/// `IrohCore.closeConn(handle)`
#[no_mangle]
pub extern "system" fn Java_com_screenshare_webrtc_IrohCore_closeConn(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(conn) = conns().lock().unwrap().remove(&(handle as u64)) {
        let _ = conn.close_tx.try_send(());
    }
}
