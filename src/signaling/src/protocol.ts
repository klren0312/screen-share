// 信令协议类型（与 Web 端 src/app/lib/types.ts 保持一致）
//
// 连接层已迁移到 iroh：
//  - caster（Android）通过 iroh QUIC 连接到本服务的 iroh 端点（见 irohBridge.ts），
//    以换行分隔的 JSON 文本流收发消息（CasterMessage）。
//  - viewer（浏览器）仍通过普通 WebSocket 与本服务通信（ClientMessage/ServerMessage），
//    服务在 iroh 与 WebSocket 之间中继 SDP/ICE 与 sensor 数据。
//  - WebRTC 仅保留视频媒体轨道；sensor 姿态不再走 WebRTC DataChannel，改走上述中继通道。

export type Role = "viewer" | "caster";

export interface PeerInfo {
  id: string;
  role: Role;
}

export interface SelfInfo {
  id: string;
  role: Role;
  polite: boolean;
}

// 与 WebRTC SDP/ICE 对应的纯结构（不依赖 DOM 类型，便于 Node 端使用）
export type SignalData =
  | { description: { type: string; sdp: string } }
  | { candidate: { candidate: string; sdpMid?: string | null; sdpMLineIndex?: number | null } };

// ---- 浏览器(viewer) <-> 服务 (WebSocket JSON) ----
export type ClientMessage =
  | { type: "join"; room: string; role: Role }
  | { type: "signal"; room: string; data: SignalData }
  | { type: "sensor"; room: string; q: { x: number; y: number; z: number; w: number }; t: number }
  | { type: "leave"; room: string };

export type ServerMessage =
  | { type: "joined"; room: string; you: SelfInfo; peers: PeerInfo[] }
  | { type: "peer-joined"; peer: PeerInfo }
  | { type: "peer-left"; id: string }
  | { type: "signal"; from: string; data: SignalData }
  | { type: "sensor"; q: { x: number; y: number; z: number; w: number }; t: number }
  | { type: "error"; message: string };

// ---- caster(Android) <-> 服务 (iroh QUIC, 换行分隔 JSON) ----
export type CasterMessage =
  | { type: "register"; room: string }
  | { type: "signal"; data: SignalData }
  | { type: "sensor"; q: { x: number; y: number; z: number; w: number }; t: number }
  | { type: "leave" };

// 服务 -> caster 的方向消息
export type BridgeToCasterMessage =
  | { type: "signal"; data: SignalData }
  | { type: "peer-joined" }
  | { type: "peer-left" };
