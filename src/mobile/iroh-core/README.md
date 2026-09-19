# iroh-core（Android 端 iroh 原生核心）

Android 端（caster）通过本 crate 与**桌面端接收端（Electron）**建立 QUIC 连接，
把编码后的屏幕画面与姿态经 iroh 直连推送过去。媒体不经过 WebRTC，因此不需要
SDP/ICE 交换，也不需要 STUN/TURN 与信令服务器；两端靠一次扫码（桌面端展示 ticket）配对。

> ⚠️ 本目录 Rust 代码需在本机用 `cargo-ndk` 交叉编译验证。
> iroh 1.x 的具体 API 请以你环境安装的版本为准（本仓库锁定 `iroh = "=1.1.0"`，
> 与桌面端 `@number0/iroh` 同源）。

## 架构位置

```
Android Kotlin                             本 Rust crate (JNI)              Electron 主进程
IrohMediaTransport ──JNI──▶ IrohCore ──iroh QUIC──▶  接收端 iroh 端点
        │                              ▲
        └──── IrohCore.onMessage(handle,json) 回调 ─────┘
```

- Kotlin 侧：`src/mobile/app/.../webrtc/{IrohCore,IrohMediaTransport}.kt`
- 原生侧：本目录（`libiroh_core.so`）

## JNI 契约（Kotlin ↔ Rust）

| Kotlin (`IrohCore.kt`)            | Rust 导出符号                                          | 说明 |
|-----------------------------------|--------------------------------------------------------|------|
| `connect(ticket, room, role)`     | `Java_com_screenshare_webrtc_IrohCore_connect`          | 建立 iroh 连接并发送 `register`，返回句柄（>0 成功，0 失败） |
| `sendMsg(handle, message)`        | `Java_com_screenshare_webrtc_IrohCore_sendMsg`          | 发送一条 JSON 控制消息（**调用方须自带结尾 `\n`**） |
| `sendMediaFrame(handle, frame)`   | `Java_com_screenshare_webrtc_IrohCore_sendMediaFrame`   | 发送一帧媒体（13B 头 + Annex-B H.264）；非阻塞投递，队满丢帧 |
| `closeConn(handle)`               | `Java_com_screenshare_webrtc_IrohCore_closeConn`        | 关闭连接 |
| `onMessage(handle, json)` (静态)  | 由 Rust 读线程回调（JNI `CallStaticVoidMethod`）         | 把收到的 JSON 转发给对应 sink |

**发送路径**：控制消息走 bi stream（换行分隔 JSON）；媒体每帧 `open_uni()` 开一条新的单向流，
写完 `finish()`。**不要**把媒体塞进同一条流——QUIC 流内严格有序，丢包会阻塞后续所有帧。

**帧格式**（唯一定义源：`src/desktop/src/shared/protocol.ts`）：

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0 | 1 | `flags`：bit0=CONFIG(SPS/PPS)，bit1=KEYFRAME |
| 1 | 8 | `ptsUs`（大端） |
| 9 | 4 | `length`（大端） |
| 13 | N | Annex-B H.264 载荷 |

ALPN 固定为 `screen-share/1`，须与桌面端 `protocol.ts` 保持一致。

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

> 两个构建脚本在编译后会自动删除同批产出的 `libiroh-*.so` / `libiroh_relay-*.so`：
> 它们是 iroh / iroh-relay 的 cdylib 副产物，`libiroh_core.so` 并未引用
> （`llvm-readelf -d` 确认 NEEDED 仅 `libc`/`libm`/`libdl`），留着只会让 APK 无谓变大。

## 集成到 App

1. 构建并放置 `libiroh_core.so`（见上）。
2. 打开桌面端，用 App 内「扫码连接」扫描其二维码，`ScreenCaptureService.irohTicket` 会被填入。
3. 点「开始共享」并授权屏幕捕获，媒体与姿态即经 iroh 直连推送。
