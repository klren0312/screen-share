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

### 方式一：本机 cargo-ndk（需提前装好 Rust/NDK）

Windows（PowerShell，在本目录执行）：

```powershell
.\build-android.ps1
```

其他平台等价于：

```bash
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 \
  -o ../app/src/main/jniLibs build --release
```

### 方式二：Docker 交叉编译（本机无需 Rust/NDK）

前提：安装 Docker Desktop 并切换到 **Linux 容器**；并配置可用的 Docker Hub 镜像源
（国内访问 `docker.io` 常被限，需在 Settings → Docker Engine 加 `registry-mirrors`）。

```powershell
# 在本目录执行
.\build-android-docker.ps1
```

脚本会构建含 Rust + Android NDK(r27c) + cargo-ndk 的镜像，挂载本目录源码与
`app/src/main/jniLibs` 产物目录，编译后 `.so` 直接落盘到宿主（见 `Dockerfile`）。
首次构建较慢（需拉镜像、编译 iroh 依赖），后续有镜像层缓存。

**NDK 获取（容器内默认从 `dl.google.com` 下载，国内通常被拦）：**
- 方式 A（推荐）：用浏览器/代理把 `android-ndk-r27c-linux.zip`（约 1GB）下到本目录，
  脚本会自动起本地 HTTP 服务喂给容器，完全绕开 Google 域名。
- 方式 B：设置环境变量指向任意可直连的 NDK zip 地址后运行脚本：
  ```powershell
  $env:NDK_MIRROR = "https://你的可达镜像/android-ndk-r27c-linux.zip"
  .\build-android-docker.ps1
  ```

产物：`src/mobile/app/src/main/jniLibs/<abi>/libiroh_core.so`
（AGP 默认即从此目录加载 `.so`，无需额外 `sourceSets` 配置。）

## 集成到 App

1. 构建并放置 `libiroh_core.so`（见上）。
2. 在 `ScreenCaptureService.irohTicket` 填入信令网桥启动时打印的 iroh ticket
   （默认 `IrohSignalingTransport` 会用它连接网桥 iroh 端点）。
3. 不设置 `irohTicket` 时 App 仍走默认 `WsSignalingTransport`（WebSocket），行为不变。
