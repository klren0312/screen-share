# AGENT.md — 移动端屏幕共享平台

本文件为在本仓库工作的 AI 助手/开发者提供导航与约定。项目整体说明见 `README.md`、领域模型见 `CONTEXT.md`、架构决策见 `docs/adr/0001-screen-sharing-platform.md`。

## 1. 项目简介

实时屏幕共享平台：Android 设备通过 **MediaProjection** 采集屏幕，用 **MediaCodec** 硬编码 H.264，
经 **iroh QUIC** 直连推送到 **Electron** 桌面端；桌面端主进程接收码流，经 IPC 交给渲染进程用
**WebCodecs** 解码，最终以 **Three.js 程序生成的 3D 手机模型** 同步呈现画面与**传感器融合姿态**。

**媒体不经过 WebRTC**：没有 SDP/ICE，不需要 STUN/TURN，也不需要信令服务器。两端靠桌面端
展示的 iroh ticket 二维码建立点对点连接（iroh 自带 relay 兜底，跨 NAT 可直连）。
改造前的 Next.js 观看端与 WebSocket 信令服务已随 ADR-0002 删除，仓库只剩 desktop 与 mobile 两条链路。

## 2. 技术栈

| 层 | 技术 |
|----|------|
| 桌面接收端 | Electron 33 + electron-vite、React 19、Three.js 0.172、Zustand 5、TypeScript 5.7 |
| 桌面端 iroh | `@number0/iroh`（napi 绑定，**仅主进程可用**；渲染进程是 Chromium，跑不了 iroh） |
| 桌面端解码 | **WebCodecs `VideoDecoder`**（`avc: { format: "annexb" }`）→ `CanvasTexture` |
| Android | Kotlin、**MediaCodec（video/avc 硬编）**、MediaProjection；不再使用 `org.webrtc` 传媒体 |
| Android 连接 | Rust core `src/mobile/iroh-core`（JNI，`iroh = "=1.1.0"`） |
| 构建 | Gradle（AGP 8.5.2、Kotlin 1.9.24）；桌面端用 npm（pnpm workspace） |

## 3. 仓库布局

```
screen-share/
├── package.json            # 根编排：dev / build / typecheck（转发到 desktop）
├── .npmrc                  # electron_mirror（国内网络必需，否则 postinstall 卡死）
├── AGENT.md / README.md / CONTEXT.md
├── docs/adr/               # ADR 文档
└── src/
    ├── desktop/            # ★ Electron 接收端（主链路）
    │   ├── electron.vite.config.ts   # main/preload/renderer 三段构建
    │   └── src/
    │       ├── shared/protocol.ts    # 媒体帧协议（三端唯一定义源）
    │       ├── main/index.ts         # 窗口、二维码、IPC 桥接
    │       ├── main/irohViewer.ts    # iroh 端点、accept、帧解析与重排
    │       ├── preload/index.ts      # contextBridge 白名单 API
    │       └── renderer/             # React UI + WebCodecs 解码管线
    ├── mobile/             # Android 采集端
    │   ├── iroh-core/      # Rust(JNI)：连接 + sendMediaFrame
    │   └── app/src/main/java/com/screenshare/
    │       ├── MainActivity.kt              # 扫码/权限/启动服务（传 resultCode）
    │       ├── ScreenCaptureService.kt      # 前台服务：换取 MediaProjection + 推流
    │       ├── capture/MediaCodecEncoder.kt # 采集 + H.264 编码
    │       ├── webrtc/IrohMediaTransport.kt # 控制消息 + 媒体帧发送
    │       ├── webrtc/IrohCore.kt           # JNI 门面
    │       └── sensor/                      # PostureTracker / MadgwickFusion
```

## 4. 运行方式

```bash
# 1. 安装依赖
pnpm install

# 2. 启动 Electron 接收端（窗口底部显示 iroh ticket 二维码）
pnpm dev

# 3. Android：用 Android Studio 打开 src/mobile
#    - 先构建 iroh 原生库（src/mobile/iroh-core，见其 README）
#    - App 内「扫码连接」扫桌面端二维码 → 「开始共享」→ 授权屏幕捕获
```

> 注：当前工作环境无 Android SDK/Gradle 与 Rust 工具链，Android 端只保证源码结构与 API 用法正确，需在本机 Android Studio 构建验证。

## 5. 关键模块职责

**桌面端（`src/desktop`）**
- `shared/protocol.ts`：**协议唯一定义源**——ALPN、帧头编解码、标志位、控制消息与 IPC 通道名。
  改协议时 Android 的 `IrohMediaTransport.kt`（帧头）与 `iroh-core/src/lib.rs`（转发）必须同步。
- `main/irohViewer.ts`：`Endpoint.builder()` + `presetN0` + `apply ALPN`；`acceptNext()` 接受连接后
  并行消费控制流（`acceptBi`）与媒体流（`acceptUni` 循环）。**每帧一条 uni stream**，按 `ptsUs`
  做 3 帧窗口重排（QUIC 只保证流内有序），空闲 30ms 强制 flush。
- `main/index.ts`：创建窗口、生成 ticket 二维码（`{"t": ticket}`）、把 viewer 事件经
  `webContents.send` 转发给渲染进程；`did-finish-load` 与 `startViewer()` 两个方向都补发 init，
  避免"窗口早于/晚于 iroh 就绪"的竞态。
- `renderer/lib/decoder.ts`：`H264Decoder` 封装 WebCodecs。**codec string 以 SPS 解析结果为准**
  （`avc1.PPCCLL`），hello 里的值只是提示；解码出错后重建并请求关键帧；无参数集时丢弃 delta 帧。
- `renderer/components/ThreeViewer.tsx`：解码帧画进离屏 canvas → `CanvasTexture` → 机身屏幕面；
  姿态应用逻辑与原 Web 端一致（`DEFAULT_ALIGN × base⁻¹ × pose`，`slerp` 平滑）。

**Android（`src/mobile`）**
- `capture/MediaCodecEncoder.kt`：MediaProjection → `createVirtualDisplay`（输出到 MediaCodec 的
  `inputSurface`）→ 编码器输出。负责 **AVCC→Annex-B 转换**（部分设备输出长度前缀）、参数集单独
  成帧下发、`requestKeyframe()`。
- `webrtc/IrohMediaTransport.kt`：`hello`/`sensor` 控制消息 + `sendVideoFrame()`（拼 13 字节头）。
  收到的 `request-keyframe` 驱动编码器出 IDR。
- `ScreenCaptureService.kt`：**由服务自己换取 `MediaProjection`**（旧实现必须让 `ScreenCapturerAndroid`
  独占换取，现已不再使用该类）；先 `startForegroundWithType()` 再 `getMediaProjection()`，并注册
  `MediaProjection.Callback`。
- `webrtc/IrohCore.kt`：JNI 门面，`MessageSink` 接口让信令/媒体两类传输共用回调分发。

**Rust core（`src/mobile/iroh-core`）**
- `connect` 建立 QUIC 连接并发送 `register`；读线程按 `\n` 切分 JSON 回调 Kotlin。
- `sendMediaFrame` **非阻塞投递**到 mpsc 队列（容量 64，**满则丢帧**），后台任务每帧
  `open_uni()` + `write_all` + `finish()`。绝不阻塞采集/编码线程。

## 6. 通信与协议

详见 `src/desktop/src/shared/protocol.ts` 与 `README.md`「媒体协议」。要点：

- **ALPN** `screen-share/1`（Android Rust core ↔ 桌面端）。
- **视频帧**：每帧一条 uni stream，`flags(1) | ptsUs(8,BE) | length(4,BE) | Annex-B payload`。
- **控制消息**：bi stream，换行分隔 JSON；`hello` / `sensor`（Android→桌面）、`welcome` /
  `request-keyframe`（桌面→Android）。

## 7. 坐标系与姿态对齐（重要）

- Android `MadgwickFusion` 输出**设备坐标系**四元数（ENU 风格，顺序 `[x,y,z,w]`）。
- 桌面端 `DEFAULT_ALIGN = [0.7071, 0, 0, 0.7071]`（约绕 X 轴 +90°，将 ENU 的 Z-Up 映射到 Three 的
  Y-Up、屏幕法线到 +Z），定义在 `renderer/components/ThreeViewer.tsx`。
- 最终模型姿态：`modelQuat = DEFAULT_ALIGN × base⁻¹ × pose`。
- 「校准姿态」将当前设备姿态记录为基线 `base`，可完全吸收坐标系符号/轴序差异；「重置标定」恢复默认。
- 改动对齐相关逻辑时务必同时考虑 Android 输出帧与桌面端的乘法顺序。

## 8. 约定与注意事项（易踩坑）

1. **iroh 版本必须锁定**：Rust `iroh = "=1.1.0"`（见 `iroh-core/Cargo.toml`）对应 Node 侧
   `@number0/iroh@^1.1.0`。两端 QUIC/ALPN 握手依赖同源版本，升级需同时升。
2. **控制消息必须带结尾 `\n`**：Rust 读线程与 Node 网桥都按 `\n` 切分；漏掉分隔符会把多条消息粘成
   一条、永远解析不出来。发送统一走 `IrohMediaTransport.sendJson()`（内部已补 `\n`）。
3. **媒体不要复用单条 stream**：QUIC 流内严格有序，丢一个包会阻塞后续所有帧。当前是「每帧一条
   uni stream + 接收端按 pts 重排」，改动这里要同时考虑重排窗口与延迟。
4. **codec string 以 SPS 为准**：`MediaFormat.createVideoFormat` 的预设 profile 常与 MediaCodec 实际
   协商结果不同，用预设值 configure `VideoDecoder` 会失败。`decoder.ts` 会从 SPS 解析并自动重建。
5. **H.264 载荷格式统一 Annex-B**：`MediaCodecEncoder` 逐帧检测起始码，AVCC 自动转换；
   接收端 `VideoDecoder` 配置为 `avc: { format: "annexb" }`，不需要 `description`(avcC)。
6. **MediaProjection 授权只能换一次**：`getMediaProjection(resultCode, data)` 需要二者配对。
   `MainActivity` 保存了 `pendingProjectionResultCode` 并随 Intent 传给 Service；
   **不要**恢复成"只传 data"的旧写法。Android 14+ 还要求先 `startForeground(mediaProjection)` 再换取，
   且必须 `registerCallback`。
7. **iroh 只在 Electron 主进程可用**：渲染进程是 Chromium，`require("@number0/iroh")` 会失败；
   所有网络操作都在主进程，渲染进程只经 preload 白名单 API 收发。
8. **不要跑 `pnpm install` 时忘了 `.npmrc`**：electron 的 postinstall 从 github 下载二进制，国内会卡死
   （表现为 `node install.js` 长时间无输出）。`.npmrc` 已指向 npmmirror；调试用 `$env:DEBUG="@electron/get*"`。
9. **JNI 回调类不可被混淆**：`IrohCore.onMessage` 由 Rust 通过 `CallStaticVoidMethod` 按类名/方法名调用，
   `proguard-rules.pro` 已 keep 该类；将来开启 `minifyEnabled` 时不要删掉这条规则。
10. **iroh 原生库必须本机构建**：`app/src/main/jniLibs/` 已被 `.gitignore` 忽略，clone 后需先用
    `cargo-ndk` 构建 `libiroh_core.so`（见 `src/mobile/iroh-core/README.md`），否则 App 在
    `System.loadLibrary` 失败后只会在日志里降级报错、始终连不上接收端。

## 9. 常见任务指引

- **改媒体协议**：先改 `src/desktop/src/shared/protocol.ts`，同步 `IrohMediaTransport.kt`（帧头拼装）
  与 `iroh-core/src/lib.rs`（若有结构变化）→ 桌面端 `main/irohViewer.ts` 解析处一起改。
- **调画质/码率**：`MediaCodecEncoder.Options`（`bitRate` / `maxLongEdge` / `frameRate` / `iFrameIntervalSec`）。
- **改姿态对齐**：改 `ThreeViewer.tsx` 里的 `DEFAULT_ALIGN_Q`；必要时同步乘序。
- **调传感器融合**：改 `sensor/MadgwickFusion.kt`（含自适应 β 参数与梯度下降实现）。
- **改连接/配对行为**：桌面端 `main/irohViewer.ts`（accept、重连顶替策略）+ Android
  `ScreenCaptureService` 的连接时序；ticket 交付在 `main/index.ts`（二维码）与 `MainActivity.onScanned`。
- **新增控制消息**：`shared/protocol.ts` 加类型 → Android `IrohMediaTransport.onRawMessage` 或
  `sendJson` → 桌面端 `main/index.ts` 的 `viewer.on("control")` 分发。

## 10. 验证

- **桌面端**：`pnpm --filter screen-share-desktop run typecheck`；`pnpm --filter screen-share-desktop run build`
  （electron-vite 构建 main/preload/renderer 三段）；`pnpm dev:desktop` 手动验证二维码与解码画面。
- **原生模块 ABI**：`$env:ELECTRON_RUN_AS_NODE=1; & <electron.exe> <脚本>` 可在 Electron 的 Node 运行时下
  验证 `@number0/iroh` 能否加载并 `Endpoint.builder().bind()`。
- **Rust core**：`cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 -o ../app/src/main/jniLibs build --release`
  （或 `build-android.ps1` / `build-android-docker.ps1`）。
- **Android**：`cd src/mobile && .\gradlew.bat assembleDebug`（或在 Android Studio 中 Sync 后构建）。
  已配置 **ABI splits**（只出 `arm64-v8a` 与 `x86_64`），产物为
  `app/build/outputs/apk/debug/app-<abi>-debug.apk`，真机装 arm64 那个；单包约 25 MB。
  `app/build.gradle` 里用 `packaging.jniLibs.excludes` 排除了 cargo 副产物
  `libiroh-*.so` / `libiroh_relay-*.so`（core 并未引用它们），**不要**删掉这条规则，否则包体会回到 86 MB。
  当前仓库**无自动化测试**；改动 `capture/`、`webrtc/` 或 `sensor/` 后建议真机跑一次完整流程，确认画面 + 3D 姿态同步。
- 变更后应至少保证：桌面端 typecheck/build 通过、Android Gradle 同步成功。
