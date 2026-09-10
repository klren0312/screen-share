import { create } from "zustand";
import type { PeerInfo, Role } from "./types";

export interface Quat {
  x: number;
  y: number;
  z: number;
  w: number;
}

interface SessionState {
  roomId: string | null;
  role: Role | null;
  selfId: string | null;
  polite: boolean;
  signalingStatus: "idle" | "connecting" | "open" | "closed" | "error";
  connectionState: string;
  peers: PeerInfo[];
  stream: MediaStream | null;
  pose: Quat; // 当前设备姿态四元数
  base: Quat; // 校准基线（取反后乘到 pose 上）
  irohTicket: string | null; // 信令服务 iroh 端点 ticket（用于生成二维码供 Android 扫码）
  set: (partial: Partial<SessionState>) => void;
  setPose: (q: Quat) => void;
  calibrate: () => void;
  resetBase: () => void;
}

export const useSession = create<SessionState>((set, get) => ({
  roomId: null,
  role: null,
  selfId: null,
  polite: false,
  signalingStatus: "idle",
  connectionState: "new",
  peers: [],
  stream: null,
  pose: { x: 0, y: 0, z: 0, w: 1 },
  base: { x: 0, y: 0, z: 0, w: 1 },
  irohTicket: null,
  set: (partial) => set(partial),
  setPose: (q) => set({ pose: q }),
  calibrate: () => set({ base: get().pose }),
  resetBase: () => set({ base: { x: 0, y: 0, z: 0, w: 1 } }),
}));
