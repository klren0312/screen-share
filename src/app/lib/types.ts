// 共享信令 / 数据协议类型定义（Web 端）
// 与 src/signaling/src/protocol.ts 保持一致

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

export type SignalData =
  | { description: RTCSessionDescriptionInit }
  | { candidate: RTCIceCandidateInit };

// client -> server
export type ClientMessage =
  | { type: "join"; room: string; role: Role }
  | { type: "signal"; room: string; data: SignalData }
  | { type: "leave"; room: string };

// server -> client
export type ServerMessage =
  | { type: "joined"; room: string; you: SelfInfo; peers: PeerInfo[] }
  | { type: "peer-joined"; peer: PeerInfo }
  | { type: "peer-left"; id: string }
  | { type: "signal"; from: string; data: SignalData }
  // sensor 姿态经信令中继通道下发（不再走 WebRTC DataChannel）
  | { type: "sensor"; q: { x: number; y: number; z: number; w: number }; t: number }
  | { type: "error"; message: string };

// 通过 WebRTC DataChannel 传输的传感器姿态（单位四元数，设备坐标系）
export interface PostureMessage {
  t: number; // 时间戳 ms
  q: { x: number; y: number; z: number; w: number };
}
