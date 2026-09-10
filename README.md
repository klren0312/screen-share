# 移动端屏幕共享平台（Mobile Screen Sharing Platform）

基于 [ADR-0001](docs/adr/0001-screen-sharing-platform.md) 构建的实时屏幕共享平台：
Android 设备通过 **MediaProjection** 采集屏幕，使用 **WebRTC** 实时传输屏幕画面与
**加速度计/磁力计融合姿态**，Web 端（Next.js + Three.js）以 **3D 手机模型** 同步呈现画面与姿态。

## 架构概览

连接/信令层已迁移到 **iroh**：信令网桥在服务端运行一个 iroh 端点，Android（caster）通过 iroh QUIC 直连，
浏览器（viewer）仍通过普通 WebSocket 与网桥通信，网桥在两端间中继 SDP/ICE 与 sensor 数据。
WebRTC **仅保留视频媒体轨道**，sensor 姿态不再走 WebRTC DataChannel。

```
Android (Caster, iroh)               信令网桥 (Node: iroh + WS)            Web (Viewer, WS)
┌──────────────────┐   iroh QUIC      ┌────────────────────┐  WS/SDP/ICE   ┌──────────────────┐
│ MediaProjection  │ ─── register ───▶ │ iroh 端点(收 caster)│ ◀── join ──── │  Next.js 页面     │
│ Screen Capturer  │   signal/sensor   │ 中继 SDP/ICE/sensor │  signal/sensor│  Three.js viewer │
│ WebRTC (video)   │ ◀──── 媒体流 ──── │  + WebSocket(viewer)│ ─── 媒体流 ─▶ │  程序生成手机模型  │
│ Sensor Fusion    │ ─── sensor(q) ───▶ │                    │ ─── sensor ──▶ │  姿态四元数同步    │
└──────────────────┘                   └────────────────────┘              └──────────────────┘
```

### 决策（来自你的选择）
- **信令连接层**：服务端 **iroh** 端点（iroh 自动 NAT 穿透 + relay 兜底）桥接 Android 与浏览器；浏览器侧仍是 WebSocket。
- **WebRTC 媒体**：视频轨道仍由 WebRTC 传输（浏览器原生能力），`sensor` 姿态改走信令中继通道。
- **Android 传输**：默认回退到 WebSocket（`WsSignalingTransport`）；设置 `irohTicket` 后切换为 iroh 直连（`IrohSignalingTransport`，依赖 Rust core `iroh-core`）。
- **3D 模型**：Three.js 程序生成手机外形，屏幕面贴 WebRTC 视频纹理，零外部资源。

## 目录结构

```
screen-share/
├── package.json            # 根编排脚本（web + signaling 并发启动）
├── .env.example            # 环境变量示例
├── docs/adr/               # ADR 文档
└── src/
    ├── package.json        # Next.js Web 客户端
    ├── app/
    │   ├── api/session     # 会话引导 API
    │   ├── api/webrtc      # ICE 配置 API
    │   ├── components/     # ThreeViewer / StatusBar
    │   ├── lib/            # types/config/store/signaling/peer/useScreenShare
    │   ├── page.tsx        # 落地页（创建/加入房间）
    │   └── session/[roomId]/page.tsx  # 3D 观看端
    ├── signaling/          # 独立 WebSocket 信令服务（Node + ws + tsx）
    │   └── src/{server,rooms,protocol}.ts
    └── mobile/             # Android 原生应用（Kotlin）
        └── app/src/main/java/com/screenshare/
            ├── MainActivity.kt
            ├── ScreenCaptureService.kt
            ├── webrtc/      # SignalingClient / PeerConnectionClient / Factory
            └── sensor/      # PostureTracker（传感器融合）
```

## 运行

### 1. 安装依赖
```bash
pnpm install
```

### 2. 启动信令网桥 + Web 客户端
```bash
pnpm dev
```
- 信令网桥同时提供：
  - **WebSocket**：`ws://localhost:8080`（浏览器 viewer 连接，端口可用 `PORT` 覆盖）
  - **iroh 端点**：UDP（默认 `0.0.0.0:8787`，可用 `IROH_BIND_ADDR` 覆盖）。启动时打印
    `connect ticket = endpoint...`，Android（iroh 模式）需用此 ticket 连接。
- Web 客户端：`http://localhost:3000`

打开 `http://localhost:3000`，创建/输入房间号进入观看端。

### 3. Android 端
用 **Android Studio** 打开 `src/mobile`：
1. 配置 SDK（compileSdk 34，minSdk 24）。
2. 默认走 **WebSocket** 信令：`MainActivity` 输入信令地址（模拟器连宿主机用 `ws://10.0.2.2:8080`）与房间号即可。
3. **（可选）iroh 直连**：用 `cargo-ndk` 构建 `src/mobile/iroh-core`（详见该目录 `README.md` 与 `build-android.ps1`），
   把各 ABI 的 `libiroh_core.so` 放入 `app/src/main/jniLibs/<abi>/`，再在 `ScreenCaptureService.irohTicket`
   填入网桥启动时的 ticket，即可改用 iroh QUIC 直连（自动 NAT 穿透，免 STUN/TURN 配置）。
4. 点击「开始共享」并授权屏幕捕获；Web 端即可看到屏幕画面与 3D 姿态。
> 注：当前工作环境无 Android SDK/Gradle 与 Rust 工具链，Android 端仅保证源码结构与 API 使用正确，需在本机 Android Studio 中构建验证（iroh 模式还需 cargo-ndk）。

## WebRTC 协商策略
两端均采用 **Perfect Negotiation**：viewer 固定为 `polite`，Android caster 固定为 `impolite`
（网桥 `welcome` 消息下发）。仅 impolite 端（caster）在出现对端（`peer-joined`）时主动 `tryOffer()`，
避免 offer 碰撞（glare）。WebRTC 仅用于视频媒体轨道。

## 数据协议
- **信令（WebSocket / iroh QUIC，换行分隔 JSON）**：
  - 浏览器↔网桥：`join` / `signal{description|candidate}` / `leave` / `sensor{q,t}`；网桥回 `joined` / `peer-joined` / `peer-left` / `signal{from,data}` / `sensor{q,t}`。
  - Android(iroh)↔网桥：`register{room}` / `signal` / `sensor` / `leave`；网桥回 `welcome{you}` / `peer-joined` / `signal` / `peer-left`。
- **姿态（经信令中继通道，不再走 WebRTC DataChannel）**：
  ```json
  { "t": 1694200000000, "q": { "x":0, "y":0, "z":0, "w":1 } }
  ```
  `q` 为设备坐标系融合四元数，Web 端将其应用到 3D 手机模型（可用「校准姿态」设定基线）。

## 姿态标定与传感器融合
- **精确对齐标定**：Web 端以 `DEFAULT_ALIGN`（设备 ENU 帧 → Three 世界帧，绕 X 轴 +90°）为默认对齐，
  点击「校准姿态」时将当前设备姿态记录为基线，最终 `modelQuat = DEFAULT_ALIGN × base⁻¹ × pose`，
  可完全吸收坐标系符号/轴序差异；「重置标定」恢复默认。
- **陀螺仪高频融合（Madgwick AHRS）**：Android 端 `MadgwickFusion` 以梯度下降同时融合加速度计、陀螺仪与磁力计
  （`∇f = Jᵀf`，β=0.1）。陀螺仪（`SENSOR_DELAY_FASTEST`）提供低延迟高频率姿态更新，
  重力/地磁经梯度下降校正陀螺仪积分漂移，输出含绝对航向、无长期漂移的姿态四元数经 DataChannel 发送。
  （此前采用的互补滤波已替换为 Madgwick，在动态加速度下更鲁棒。）

## 后续可增强
- TURN 服务器配置（跨 NAT 场景）。
- 多观看端 / 多采集端房间策略。
- 自适应 β（动态加速度大时自动**减小** β 以更信任陀螺仪惯导、抑制动态加速度干扰；静止时增大 β 让重力/地磁参考更快消除漂移）。
