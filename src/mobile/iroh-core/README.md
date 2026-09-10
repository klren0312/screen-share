# iroh-core（Android 端 iroh 原生核心）

Android 端（caster）通过本 crate 与信令网桥的 **iroh 端点**建立 QUIC 连接，
由服务端在 iroh 与浏览器 WebSocket 之间中继 SDP/ICE 与 sensor 数据。

> ⚠️ 本目录 Rust 代码为**参考实现**，需在本机用 `cargo-ndk` 交叉编译验证。
> iroh 1.x 的具体 API（如 `Endpoint::builder()`、`discovery_n0`、`RelayMode`、
> `NodeTicket` 解析等）请以你环境安装的版本为准，首次构建可能需做少量 API 适配。

## 架构位置

```
Android 端 Kotlin                        本 Rust crate (JNI)             信令网桥 (Node iroh 端点)
IrohSignalingTransport ──JNI──▶ IrohCore ──iroh QUIC──▶  网桥 iroh 端点
        │                                  ▲
        └──── IrohCore.onMessage(handle,json) 回调 ──────┘
```

- Kotlin 侧：`src/mobile/app/.../webrtc/{IrohCore,IrohSignalingTransport,SignalingTransport}.kt`
- 原生侧：本目录（`libiroh_core.so`）

## JNI 契约（Kotlin ↔ Rust）

| Kotlin (`IrohCore.kt`)            | Rust 导出符号                                        | 说明 |
|----------------------------------|------------------------------------------------------|------|
| `connect(ticket, room, role)`    | `Java_com_screenshare_webrtc_IrohCore_connect`       | 建立 iroh 连接并发送 `register`，返回句柄（>0 成功，0 失败） |
| `sendMsg(handle, message)`       | `Java_com_screenshare_webrtc_IrohCore_sendMsg`       | 发送一条 JSON 信令/姿态消息 |
| `closeConn(handle)`              | `Java_com_screenshare_webrtc_IrohCore_closeConn`     | 关闭连接 |
| `onMessage(handle, json)` (静态) | 由 Rust 读线程回调（JNI `CallStaticVoidMethod`）     | 把收到的 JSON 转发给对应 transport |

**消息协议（换行分隔 JSON）**
- Android→网桥：`register{room}` / `signal{data}` / `sensor{q,t}` / `leave`
- 网桥→Android：`welcome{you}` / `peer-joined` / `signal{data}` / `peer-left`

ALPN 固定为 `screen-share/1`，须与信令网桥 `irohBridge.ts` 中保持一致。

## 前置依赖

- Rust toolchain（rustup）
- `cargo install cargo-ndk`
- 交叉编译目标：
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
  ```
- Android NDK，并导出 `ANDROID_NDK_HOME`（或 `NDK_HOME`）

## 构建

Windows（PowerShell，在本目录执行）：

```powershell
.\build-android.ps1
```

其他平台等价于：

```bash
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 \
  -o ../app/src/main/jniLibs build --release
```

产物：`src/mobile/app/src/main/jniLibs/<abi>/libiroh_core.so`
（AGP 默认即从此目录加载 `.so`，无需额外 `sourceSets` 配置。）

## 集成到 App

1. 构建并放置 `libiroh_core.so`（见上）。
2. 在 `ScreenCaptureService.irohTicket` 填入信令网桥启动时打印的 iroh ticket
   （默认 `IrohSignalingTransport` 会用它连接网桥 iroh 端点）。
3. 不设置 `irohTicket` 时 App 仍走默认 `WsSignalingTransport`（WebSocket），行为不变。
