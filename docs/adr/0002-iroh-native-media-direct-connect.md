# ADR-0002: 媒体改走 iroh 直连，Web 观看端由 Electron 取代

## Status

Accepted（2026-09-19）— 部分取代 [ADR-0001](0001-screen-sharing-platform.md)

## Context

ADR-0001 选定 WebRTC 传媒体、Next.js 作观看端。实际运行中暴露出两个结构性问题：

1. **媒体仍受 NAT 限制**：信令层虽已迁移到 iroh（自动穿透 + relay 兜底），但 WebRTC 媒体走的是
   自己的 ICE/UDP 通路——iroh 只搬运了 SDP/ICE 文本，不转发任何媒体字节。跨网段（模拟器、
   蜂窝网络、对称 NAT）时画面始终不通，只能额外部署 TURN 中继。
2. **浏览器跑不了 iroh**：iroh 是 Rust 实现，只有 Node 与 Android JNI 两种绑定。把接收端放在
   浏览器里，媒体就注定只能依赖 WebRTC。

## Decision

接收端从浏览器改为 **Electron**，媒体从 WebRTC 迁移到 **iroh QUIC**：

- **Android**：弃用 `ScreenCapturerAndroid` + `RTCPeerConnection`，改为
  `MediaProjection → VirtualDisplay(MediaCodec.inputSurface) → H.264`，
  经 iroh 推送；**每帧一条 uni stream**（避免 QUIC 流内 HOL 阻塞），参数集随关键帧下发。
- **接收端**：Electron 主进程（Node）起 iroh 端点并展示 ticket 二维码，Android 扫码直连。
  主进程按 `ptsUs` 重排后经 IPC 交给渲染进程，用 **WebCodecs `VideoDecoder`** 解码，
  作为 `CanvasTexture` 贴到 Three.js 手机模型上。
- **姿态**：与视频共用同一条 iroh 连接的控制流（换行分隔 JSON）。
- **删除**：Next.js 观看端（`src/app`）、WebSocket 信令服务与 iroh 网桥（`src/signaling`）、
  Android 侧 WebRTC 依赖、根目录预编译的 `jni/*.so` 与 `classes.jar`。

## Consequences

**收益**

- STUN / TURN / 信令服务器全部移除；iroh 自带 relay 兜底，跨 NAT 可直连。
- 媒体参数完全可控（码率、关键帧间隔、SPS/PPS 随关键帧下发）。
- Android 包体显著减小（移除 WebRTC AAR 与 `jni/` 下约 26 MB 预编译 `.so`）。

**代价**

- 接收端不再是"打开网页"，必须安装桌面应用。
- 编解码与传输链路由自研协议承接，需自行处理分帧、乱序重排、参数集管理与错误恢复
  （详见 `src/desktop/src/shared/protocol.ts` 与 `AGENT.md` 第 8 节）。
- 当前为 1:1 直连（新连接顶掉旧连接），多观看端需另行设计。

## Related Documents

- [ADR-0001](0001-screen-sharing-platform.md) — 原始技术选型（WebRTC + Next.js）
- `src/desktop/src/shared/protocol.ts` — 媒体帧协议唯一定义源
- `src/mobile/iroh-core/README.md` — JNI 契约与构建方式
