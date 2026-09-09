// 信令协议类型（与 Web 端 src/app/lib/types.ts 保持一致）

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

export type ClientMessage =
  | { type: "join"; room: string; role: Role }
  | { type: "signal"; room: string; data: SignalData }
  | { type: "leave"; room: string };

export type ServerMessage =
  | { type: "joined"; room: string; you: SelfInfo; peers: PeerInfo[] }
  | { type: "peer-joined"; peer: PeerInfo }
  | { type: "peer-left"; id: string }
  | { type: "signal"; from: string; data: SignalData }
  | { type: "error"; message: string };
