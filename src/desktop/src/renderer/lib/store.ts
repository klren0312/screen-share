import { create } from "zustand";
import type { ConnectionState } from "../../shared/protocol";

export interface Quat {
  x: number;
  y: number;
  z: number;
  w: number;
}

interface DesktopState {
  /** iroh 端点是否已就绪（ticket 可用） */
  endpointReady: boolean;
  connection: ConnectionState;
  ticket: string | null;
  qr: string | null;
  nodeId: string | null;

  /** 解码诊断 */
  resolution: string;
  codec: string | null;
  decoderReady: boolean;
  receivedFrames: number;
  decodedFrames: number;
  droppedFrames: number;
  fps: number;
  lastError: string | null;

  pose: Quat;
  base: Quat;

  set: (partial: Partial<DesktopState>) => void;
  setPose: (q: Quat) => void;
  calibrate: () => void;
  resetBase: () => void;
}

export const useDesktop = create<DesktopState>((set, get) => ({
  endpointReady: false,
  connection: "waiting",
  ticket: null,
  qr: null,
  nodeId: null,

  resolution: "-",
  codec: null,
  decoderReady: false,
  receivedFrames: 0,
  decodedFrames: 0,
  droppedFrames: 0,
  fps: 0,
  lastError: null,

  pose: { x: 0, y: 0, z: 0, w: 1 },
  base: { x: 0, y: 0, z: 0, w: 1 },

  set: (partial) => set(partial),
  setPose: (q) => set({ pose: q }),
  calibrate: () => set({ base: get().pose }),
  resetBase: () => set({ base: { x: 0, y: 0, z: 0, w: 1 } }),
}));
