# 移动端屏幕共享平台（Mobile Screen Sharing Platform）

Android 设备通过 **MediaProjection** 采集屏幕，用 **MediaCodec** 硬编码 H.264，
经 **iroh QUIC** 直连推送到桌面端（**Electron**），桌面端用 **WebCodecs** 解码并以
**Three.js「3D 手机模型」** 同步呈现画面与**加速度计/磁力计融合姿态**。

**媒体不经过 WebRTC**，因此没有 SDP/ICE，也不需要 STUN、TURN 或信令服务器：
两端靠一次扫码（桌面端展示 iroh ticket）建立点对点连接，iroh 自带 relay 兜底，
跨网段、跨 NAT 均可直连。

## 架构概览

```
Android (Caster)                                  Electron (Viewer)
┌───────────────────────┐                        ┌────────────────────────────┐
│ MainActivity          │                        │ 主进程 (Node)               │
│  扫码 → iroh ticket    │                        │  iroh 端点 + 二维码          │
│ ScreenCaptureService  │   iroh QUIC (P2P)      │  accept 连接                │
│  ├ MediaCodec H.264   │ ─── 视频帧(uni stream)──▶│  帧重排 + 解析 13B 头        │
│  │  VirtualDisplay    │                        │  ── IPC ─▶ 渲染进程          │
│  └ IrohMediaTransport │ ◀── 控制消息(JSON) ─────│   WebCodecs 解码            │
│    (Rust core / JNI)  │    sensor / hello      │   → CanvasTexture           │
│ PostureTracker        │                        │   → Three.js 手机模型        │
└───────────────────────┘                        └────────────────────────────┘
```

关键点：
- **媒体与姿态走同一条 iroh 连接**：视频每帧开一条 uni stream，控制消息走 bi stream。
- **HOL 阻塞规避**：不复用单条流传视频（QUIC 流内严格有序，丢包会阻塞后续帧），
  改为每帧一条流，接收端按 `ptsUs` 重排后交给解码器。
- **参数集自愈**：MediaCodec 开 `KEY_PREPEND_HEADER_TO_SYNC_FRAMES`，每个关键帧前带
  SPS/PPS，接收端解码器重建后无需额外握手即可恢复。

## 目录结构

```
screen-share/
├── package.json              # 根编排脚本
├── .npmrc                    # electron 二进制镜像（国内网络必需）
├── docs/adr/                 # ADR 文档
└── src/
    ├── desktop/              # ★ Electron 接收端（当前主链路）
    │   ├── electron.vite.config.ts
    │   └── src/
    │       ├── main/         # iroh 端点、帧接收与重排、二维码、IPC 桥接
    │       ├── preload/      # contextBridge 暴露的白名单 API
    │       ├── renderer/     # React UI：ThreeViewer / StatusBar / ConnectPanel
    │       │   └── lib/      # WebCodecs 解码管线（decoder.ts）、store
    │       └── shared/       # 媒体帧协议（三端共用定义）
    ├── mobile/               # Android 采集端（Kotlin）
    │   ├── iroh-core/        # Rust(JNI)：iroh 连接 + 媒体帧发送
    │   └── app/src/main/java/com/screenshare/
    │       ├── MainActivity.kt          # 扫码、权限、启动服务
    │       ├── ScreenCaptureService.kt  # 前台服务：编码 + iroh 推流
    │       ├── capture/                 # MediaCodec 采集编码器
    │       ├── webrtc/                  # IrohMediaTransport / IrohCore(JNI 门面)
    │       └── sensor/                  # PostureTracker + MadgwickFusion
    └── (改造前的 Next.js 观看端与 WebSocket 信令服务已删除，见 ADR-0002)
```

## 运行

### 1. 安装依赖
```bash
pnpm install
```
> `.npmrc` 已配置 `electron_mirror`。若 electron 的 postinstall 卡住（下载/校验走 github），
> 可先用 `$env:DEBUG="@electron/get*"` 观察，或手动把缓存 zip 解压到
> `node_modules/.pnpm/electron@*/node_modules/electron/dist` 并写入 `path.txt`（内容 `electron.exe`）。

### 2. 启动桌面接收端
```bash
pnpm dev:desktop
```
窗口底部会显示 **iroh ticket 二维码**。连接成功后状态栏显示分辨率、codec、FPS 与帧计数。

### 3. Android 端
用 **Android Studio** 打开 `src/mobile`：

1. 构建 iroh 原生库（前置条件）：
   ```bash
   # 详见 src/mobile/iroh-core/README.md
   cd src/mobile/iroh-core && ./build-android.ps1   # 或 build-android-docker.ps1
   ```
   产物 `libiroh_core.so` 会落到 `app/src/main/jniLibs/<abi>/`。
2. 打开 App，点「**扫码连接**」扫描桌面端二维码（相机权限）→ 自动填入 ticket。
3. 点「**开始共享**」并授权屏幕捕获。

也可以不开 IDE，直接用命令行构建并安装：

```powershell
cd src/mobile
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

> 已配置 **ABI splits**：真机装 `app-arm64-v8a-debug.apk`，模拟器装 `app-x86_64-debug.apk`，
> 单包约 25 MB（4 个 ABI 全打时为 86 MB）。
> 本机已验证：JDK 17 + Android SDK 下 `gradlew assembleDebug` 可正常出包；
> Rust 工具链非必需——可用 `build-android-docker.ps1` 在容器里交叉编译原生库。

## 媒体协议（`src/desktop/src/shared/protocol.ts` 为唯一定义源）

**ALPN**：`screen-share/1`（Android Rust core 与桌面端必须一致）。

**视频帧**（每帧一条 uni stream，13 字节定长头 + 载荷）：

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0 | 1 | `flags`：bit0=CONFIG(SPS/PPS)，bit1=KEYFRAME |
| 1 | 8 | `ptsUs`（MediaCodec `presentationTimeUs`，大端） |
| 9 | 4 | `length`（载荷字节数，大端） |
| 13 | N | Annex-B H.264 载荷 |

- 载荷统一为 **Annex-B**：部分设备输出 AVCC（4 字节长度前缀），Android 侧会自动转换。
- 接收端按 `ptsUs` 排序（窗口 3 帧）后再解码；空闲 30ms 强制 flush，避免低帧率时压帧。

**控制消息**（bi stream，换行分隔 JSON，**发送方必须带 `\n`**）：

| 方向 | 消息 |
|------|------|
| Android → 桌面 | `{"type":"hello","room","codec","width","height"}`、`{"type":"sensor","q":{x,y,z,w},"t"}` |
| 桌面 → Android | `{"type":"welcome","sessionId"}`、`{"type":"request-keyframe"}` |

`hello.codec` 仅作提示：接收端会优先从 SPS 解析 `avc1.PPCCLL`，因为 MediaCodec 实际协商的
profile/level 常与 `createVideoFormat` 的预设不同。

## 姿态标定与传感器融合

- **精确对齐标定**：桌面端以 `DEFAULT_ALIGN`（设备 ENU 帧 → Three 世界帧，绕 X 轴 +90°）为默认对齐，
  点「校准姿态」把当前设备姿态记为基线，最终 `modelQuat = DEFAULT_ALIGN × base⁻¹ × pose`，
  可完全吸收坐标系符号/轴序差异；「重置标定」恢复默认。
- **Madgwick AHRS**：`MadgwickFusion` 以梯度下降融合加速度计、陀螺仪与磁力计
  （`∇f = Jᵀf`），陀螺仪（`SENSOR_DELAY_FASTEST`）提供低延迟高频姿态，重力/地磁校正积分漂移，
  **β 自适应**（动态加速度大时减小 β 以更信任陀螺仪，静止时增大 β 加速消除漂移）。
  姿态经 iroh 控制流发送（不再走 WebRTC DataChannel）。

## 架构演进

改造前的实现（Next.js 观看端 + WebSocket 信令服务 + WebRTC 媒体）已随
[ADR-0002](docs/adr/0002-iroh-native-media-direct-connect.md) 一并删除。
删除的核心原因是：信令层虽然迁移到了 iroh，但 WebRTC 媒体仍走自己的 ICE/UDP 通路，
iroh 只搬运 SDP/ICE 文本、不转发任何媒体字节；而浏览器又跑不了 iroh（只有 Node 与 Android JNI 绑定），
所以媒体注定绕不开 NAT 与 TURN。

## 后续可增强

- 多观看端 / 多采集端（当前 iroh 连接为 1:1，新连接顶掉旧连接）。
- 码率自适应（接收端上报丢帧/延迟，Android 侧动态调整 `MediaFormat.KEY_BIT_RATE`）。
- 断线重连（当前断开后需重新扫码）。
