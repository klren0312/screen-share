# AGENT.md — 移动端屏幕共享平台

本文件为在本仓库工作的 AI 助手/开发者提供导航与约定。项目整体说明见 `README.md`、领域模型见 `CONTEXT.md`、架构决策见 `docs/adr/0001-screen-sharing-platform.md`。

## 1. 项目简介

实时屏幕共享平台：Android 设备通过 **MediaProjection** 采集屏幕，经 **WebRTC** 实时传输屏幕画面与**传感器融合姿态**；Web 端（Next.js + Three.js）用**程序生成的 3D 手机模型**同步呈现画面与姿态（屏幕面贴 WebRTC 视频纹理，零外部资源）。

两端采用 **Perfect Negotiation** 协商 SDP/ICE；姿态经独立 **DataChannel (`sensor`)** 发送。

## 2. 技术栈

| 层 | 技术 |
|----|------|
| Web 客户端 | Next.js 15、React 19、Three.js 0.172、Zustand 5、TypeScript 5.7 |
| 信令服务 | Node + `ws` + `tsx`（WebSocket，无框架） |
| Android | Kotlin、WebRTC `org.webrtc:google-webrtc:1.0.32006`、MediaProjection |
| 构建 | Gradle（AGP 8.5.2、Kotlin 1.9.24）；Web/信令用 npm |

## 3. 仓库布局

```
screen-share/
├── package.json            # 根编排：install:all / dev（concurrently 并发起 web+signaling）
├── .env.example            # 环境变量示例（NEXT_PUBLIC_SIGNALING_WS_URL、SIGNALING_PORT/PORT）
├── AGENT.md / README.md / CONTEXT.md
├── docs/adr/               # ADR 文档
├── jni/                    # 预编译 libjingle_peerconnection_so.so（各 ABI），供 Android 链接
└── src/
    ├── package.json        # Next.js Web 客户端
    ├── app/
    │   ├── api/session/    # 会话引导 API（房间号生成）
    │   ├── api/webrtc/     # ICE 配置 API（可选，回 STUN/TURN）
    │   ├── components/     # ThreeViewer（3D 模型+视频纹理）、StatusBar
    │   ├── lib/            # types/config/store/signaling/peer/useScreenShare
    │   ├── page.tsx        # 落地页（创建/加入房间）
    │   └── session/[roomId]/page.tsx  # 3D 观看端
    ├── signaling/          # 独立 WebSocket 信令服务
    │   └── src/{server,rooms,protocol}.ts
    └── mobile/             # Android 原生应用（Kotlin），用 Android Studio 打开本目录
        └── app/src/main/java/com/screenshare/
            ├── MainActivity.kt              # UI：输入信令地址/房间号、开始共享
            ├── ScreenCaptureService.kt      # 前台服务：持有 PeerConnectionClient + PostureTracker
            ├── webrtc/                      # SignalingClient / PeerConnectionClient / PeerConnectionFactoryHolder
            └── sensor/                      # PostureTracker（传感器融合编排）、MadgwickFusion（AHRS）
```

## 4. 运行方式

```bash
# 1. 安装依赖（web + signaling）
pnpm install

# 2. 启动 信令服务(:8080) + Web 客户端(:3000)
pnpm dev
# 信令端口可用 SIGNALING_PORT（服务端读 PORT）覆盖；Web 信令地址用 NEXT_PUBLIC_SIGNALING_WS_URL

# 3. Android 端：用 Android Studio 打开 src/mobile
#    - compileSdk 34 / minSdk 24；MainActivity 输入信令地址
#      （模拟器连宿主机用 ws://10.0.2.2:8080）与房间号，点击「开始共享」并授权屏幕捕获
```

> 注：当前工作环境无 Android SDK/Gradle，Android 端只保证源码结构与 API 用法正确，需在本机 Android Studio 构建验证。

## 5. 关键模块职责

**Web（`src/app`）**
- `lib/peer.ts`：`Peer` 类，封装 Perfect Negotiation（polite/impolite 由服务端分配），处理 SDP/ICE 收发、track、datachannel、`onnegotiationneeded`。
- `lib/signaling.ts`：WebSocket 客户端，连接信令服务、收发 `join`/`signal`/`leave`、`joined`/`peer-joined`/`peer-left`/`signal`。
- `lib/store.ts`：Zustand 会话状态（房间、连接状态、视频流、姿态四元数、标定基线）。
- `lib/config.ts`：运行期配置——`SIGNALING_WS_URL`、`ICE_SERVERS`（STUN，生产加 TURN）、`DEFAULT_ALIGN`（坐标对齐四元数）、`DEFAULT_ROOM_ID_LENGTH`。
- `lib/types.ts`：Web 端信令/姿态类型（**与 `signaling/src/protocol.ts` 刻意保持结构一致**）。
- `lib/useScreenShare.ts`：组合 hook，驱动加入房间、建立 Peer、接收视频与姿态。
- `components/ThreeViewer.tsx`：程序生成手机外形，屏幕面贴 WebRTC 视频纹理；按 `modelQuat = DEFAULT_ALIGN × base⁻¹ × pose` 应用姿态。
- `components/StatusBar.tsx`：连接/标定状态与「校准姿态 / 重置标定」操作。

**信令（`src/signaling`）**
- `server.ts`：`WebSocketServer`，按 `ClientMessage` 路由 `join`/`signal`/`leave`，维护连接 id 与 role。
- `rooms.ts`：房间注册表；`joinRoom` 时分配 `polite`（先入房间者为 impolite），返回 `joined` 与现有 `peers`，并 `notifyPeers` 广播 `peer-joined`。
- `protocol.ts`：信令消息类型（与 Web `types.ts` 同步）。

**Android（`src/mobile`）**
- `MainActivity.kt`：输入信令地址/房间号，启动 `ScreenCaptureService`（带 MediaProjection 权限 Intent）。
- `ScreenCaptureService.kt`：前台服务（foregroundServiceType=mediaProjection）；创建 `PeerConnectionFactory`、`SignalingClient`、`PeerConnectionClient`、`PostureTracker`；`onDestroy` 释放资源。
- `webrtc/SignalingClient.kt`：WebSocket 客户端，连接信令服务并中继 SDP/ICE（`sendSignal`、`onJoined`/`onPeerJoined` 回调）。
- `webrtc/PeerConnectionClient.kt`：封装 `PeerConnection`；用 `ScreenCapturerAndroid` 采集屏幕、`createVideoTrack` 并 `addTrack`；创建 `sensor` DataChannel；Perfect Negotiation 状态机（`polite`/`makingOffer`/`ignoreOffer`）；`sendSensor(quaternion)` 经 DataChannel 发送姿态。
- `webrtc/PeerConnectionFactoryHolder.kt`：单例 `PeerConnectionFactory`。
- `sensor/PostureTracker.kt`：注册加速度计/陀螺仪/磁力计，`onSensorChanged` 调用 `MadgwickFusion.update` 并回调融合四元数。
- `sensor/MadgwickFusion.kt`：Madgwick AHRS（梯度下降融合加速度计+陀螺仪+磁力计）；**β 自适应**（见第 7 节）。

## 6. 通信与协议

**信令（WebSocket JSON）**
- client→server：`join{room,role}` / `signal{room,data}` / `leave{room}`
- server→client：`joined{you,peers}` / `peer-joined{peer}` / `peer-left{id}` / `signal{from,data}` / `error{message}`
- role：`viewer`（Web）| `caster`（Android）。

**Perfect Negotiation**：服务端在 `join` 时分配 `polite`（先入房间者为 impolite）。仅 impolite 端在对端出现时主动 `tryOffer()`，避免 offer 碰撞（glare）。Web 与 Android 逻辑对称。

**姿态（WebRTC DataChannel `sensor`）**
```json
{ "t": 1694200000000, "q": { "x":0, "y":0, "z":0, "w":1 } }
```
`q` 为**设备坐标系**融合四元数（顺序 `[x,y,z,w]`）。Web 端将其应用到 3D 手机模型。

## 7. 坐标系与姿态对齐（重要）

- Android `MadgwickFusion` 输出**设备坐标系**四元数（ENU 风格，顺序 `[x,y,z,w]`）。
- Web 端 `DEFAULT_ALIGN = [0.7071, 0, 0, 0.7071]`（约绕 X 轴 +90°，将 ENU 的 Z-Up 映射到 Three 的 Y-Up、屏幕法线到 +Z）。
- 最终模型姿态：`modelQuat = DEFAULT_ALIGN × base⁻¹ × pose`。
- Web「校准姿态」将当前设备姿态记录为基线 `base`，可完全吸收坐标系符号/轴序差异；「重置标定」恢复默认。
- 改动对齐相关逻辑时务必同时考虑 Android 输出帧与 Web `DEFAULT_ALIGN`/`useScreenShare` 的乘法顺序。

## 8. 约定与注意事项（易踩坑）

1. **WebRTC 依赖仓库**：`org.webrtc:google-webrtc` **从未发布到 Maven Central / Google 仓库**，仅发布在已关闭的 JCenter。已在 `build.gradle`/`settings.gradle` 加入阿里云 JCenter 镜像（`https://maven.aliyun.com/repository/public`）。**不要**把该依赖改回 `mavenCentral()`/`google()`，否则无法解析。
2. **WebRTC `1.0.32006` API 细节**（改动 `webrtc/` 时务必对齐）：
   - `PeerConnection.Observer` 是**接口**，创建时写 `object : PeerConnection.Observer { ... }`（无构造括号）。
   - 需实现的抽象方法：`onSignalingChange`、`onIceConnectionChange`、`onIceConnectionReceivingChange(boolean)`、`onIceGatheringChange`、`onIceCandidate`、`onIceCandidatesRemoved(IceCandidate[])`、`onAddStream`、`onRemoveStream`、`onDataChannel`、`onRenegotiationNeeded`、`onAddTrack(RtpReceiver, MediaStream[])`。（`onConnectionChange`、`onStandardizedIceConnectionChange` 等为 default，可不实现。）
   - `ScreenCapturerAndroid(Intent, MediaProjection.Callback)`：**第二个参数是 `MediaProjection.Callback` 而非 `MediaProjection`**。该版本内部通过 Android 14+ 新 API `MediaProjectionManager.getMediaProjection(callback, data)` 自建 `MediaProjection`，**不要**从外部传入 `MediaProjection` 实例。
3. **Madgwick β 现已自适应**（不再固定 0.1）：构造函数参数 `betaStatic`（静止，默认 0.2）、`betaMotion`（运动，默认 0.04）、`dynAccelRef`（动态加速度参考幅度，默认 0.3）、`betaSmoothing`（EMA 平滑，默认 0.1）。动态加速度大时 β 自动减小以更信任陀螺仪惯导、抑制动态加速度干扰；静止时 β 增大让重力/地磁参考更快消除漂移。`getBeta()` 可读取当前（平滑后）增益用于遥测。
4. **类型同步**：`src/app/lib/types.ts`（Web）与 `src/signaling/src/protocol.ts`（信令）的消息类型需保持一致；二者目前是刻意的结构重复（非共享导入），改动协议时两边都要改。
5. **环境变量**：`.env.example` 已提供示例。`NEXT_PUBLIC_SIGNALING_WS_URL` 供 Web 读；信令服务端读 `PORT`（默认 8080）。`.env` 已被 `.gitignore` 忽略。
6. **`.gitignore`** 已覆盖 `node_modules/`、`src/mobile/app/build/`、`.gradle/`、`local.properties`、`.env` 等；`jni/*.so` 与 `classes.jar` 为项目预置产物，会被纳入版本控制。

## 9. 常见任务指引

- **新增 DataChannel 消息类型**：在 `types.ts`/`protocol.ts` 增加结构 → Android 侧在 `PeerConnectionClient` 发送（参考 `sendSensor`）→ Web 侧在 `useScreenShare`/`store` 接收处理。
- **改姿态对齐**：调 `src/app/lib/config.ts` 的 `DEFAULT_ALIGN`；必要时同步 `useScreenShare` 的乘序。
- **加 TURN（跨 NAT）**：在 Web `config.ts` 的 `ICE_SERVERS` 与 Android `PeerConnectionClient` 的 `iceServers` 各加一项，并写入 `.env`/文档。
- **调传感器融合**：改 `sensor/MadgwickFusion.kt`（含自适应 β 参数与梯度下降实现）。
- **改信令行为**：`src/signaling/src/{server,rooms}.ts`（房间/转发/角色分配）。

## 10. 验证

- **Web**：`pnpm --filter screen-share-web run build`（Next.js 构建含类型检查）；`pnpm dev` 起本地服务手动验证页面与 3D 观看端。
- **信令**：`pnpm --filter screen-share-signaling run dev`（tsx watch 热重载），检查 `ws://localhost:8080` 日志。
- **Android**：须在 Android Studio 中 `Sync Project with Gradle Files` 后构建；当前无自动化测试。改动 `webrtc/` 或 `sensor/` 后建议真机跑一次共享流程，确认画面 + 3D 姿态同步。
- 当前仓库**无自动化测试**；变更后应至少保证 Web 类型检查通过与 Android Gradle 同步成功。
