//! iroh-core：Android 端 iroh 原生核心（JNI）。
//!
//! 由 Kotlin 侧 `IrohCore.kt` 通过 JNI 调用，负责：
//!  1. 解析信令网桥的 iroh ticket，建立到网桥端点的 QUIC 连接（iroh 自动 NAT 穿透 + relay 兜底）；
//!  2. 以换行分隔的 JSON 文本流与网桥互发信令/姿态消息；
//!  3. 把收到的每条消息通过 `IrohCore.onMessage(handle, json)` 回调给 Kotlin 侧。
//!
//! 构建：见仓库 AGENT.md（cargo-ndk 交叉编译后把各 ABI 的 libircore.so 放入 jniLibs）。

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use iroh::{Endpoint, NodeAddr};
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jlong, JNI_VERSION_1_8};
use jni::JNIEnv;
use jni::JavaVM;
use once_cell::sync::OnceCell;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
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

/// 一条活跃连接：保留 Connection（防止被 drop 而关闭）、发送流、以及对端地址
struct Conn {
    #[allow(dead_code)]
    conn: iroh::endpoint::Connection,
    send: Arc<AsyncMutex<iroh::endpoint::SendStream>>,
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
    // 解析网桥 ticket -> NodeAddr
    let node_ticket = ticket
        .parse::<iroh::ticket::NodeTicket>()
        .map_err(|e| anyhow::anyhow!("bad ticket: {e}"))?;
    let node_addr: NodeAddr = node_ticket.node_addr();

    let endpoint = Endpoint::builder()
        .discovery_n0()
        .map_err(|e| anyhow::anyhow!("discovery_n0: {e}"))?
        .relay_mode(iroh::RelayMode::Default)
        .bind()
        .await
        .map_err(|e| anyhow::anyhow!("bind: {e}"))?;
    endpoint
        .add_node_addr(node_addr.clone())
        .map_err(|e| anyhow::anyhow!("add_node_addr: {e}"))?;

    let conn = endpoint
        .connect(node_addr, ALPN.to_vec())
        .await
        .map_err(|e| anyhow::anyhow!("connect: {e}"))?;

    let (mut send, mut recv) = conn
        .open_bi()
        .await
        .map_err(|e| anyhow::anyhow!("open_bi: {e}"))?;

    // 发送 register
    let register = serde_json::json!({ "type": "register", "room": room }).to_string() + "\n";
    send
        .write_all(register.as_bytes())
        .await
        .map_err(|e| anyhow::anyhow!("write register: {e}"))?;
    send
        .flush()
        .await
        .map_err(|e| anyhow::anyhow!("flush: {e}"))?;

    let handle = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
    let send = Arc::new(AsyncMutex::new(send));

    // 退出信号
    let (close_tx, mut close_rx) = mpsc::channel::<()>(1);
    conns().lock().unwrap().insert(
        handle,
        Conn {
            conn,
            send: send.clone(),
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
                    let n = match read {
                        Ok(n) => n,
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
        &[
            JValue::Long(handle as i64),
            JValue::Object(obj),
        ],
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
        let guard = conns().lock().unwrap();
        if let Some(conn) = guard.get(&(handle as u64)) {
            let mut send = conn.send.lock().await;
            let _ = send.write_all(msg.as_bytes()).await;
            let _ = send.flush().await;
        }
    });
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
