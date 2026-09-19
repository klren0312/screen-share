import { contextBridge, ipcRenderer } from "electron";
import { IPC, type ConnectionState } from "../shared/protocol";

export interface InitPayload {
  ticket: string;
  qr: string;
  nodeId: string;
}

export interface HelloPayload {
  codec: string;
  width: number;
  height: number;
}

export interface MediaChunkPayload {
  data: Uint8Array;
  flags: number;
  ptsUs: string;
}

export interface Quat {
  x: number;
  y: number;
  z: number;
  w: number;
}

/** 渲染进程可用的桥接 API（contextIsolation 下只暴露这些） */
const api = {
  /** 拉取一次初始化信息（窗口可能在 iroh 就绪前加载） */
  getInit: (): Promise<InitPayload | null> => ipcRenderer.invoke("viewer:get-init"),
  /** 拉取最近一次 hello（编码参数） */
  getHello: (): Promise<HelloPayload | null> => ipcRenderer.invoke("viewer:get-hello"),

  onInit: (cb: (p: InitPayload) => void) => {
    const h = (_e: unknown, p: InitPayload) => cb(p);
    ipcRenderer.on("viewer:init", h);
    return () => ipcRenderer.off("viewer:init", h);
  },
  onHello: (cb: (p: HelloPayload) => void) => {
    const h = (_e: unknown, p: HelloPayload) => cb(p);
    ipcRenderer.on("viewer:hello", h);
    return () => ipcRenderer.off("viewer:hello", h);
  },
  onConfig: (cb: (data: Uint8Array) => void) => {
    const h = (_e: unknown, data: Uint8Array) => cb(data);
    ipcRenderer.on(IPC.MediaConfig, h);
    return () => ipcRenderer.off(IPC.MediaConfig, h);
  },
  onChunk: (cb: (p: MediaChunkPayload) => void) => {
    const h = (_e: unknown, p: MediaChunkPayload) => cb(p);
    ipcRenderer.on(IPC.MediaChunk, h);
    return () => ipcRenderer.off(IPC.MediaChunk, h);
  },
  onSensor: (cb: (q: Quat) => void) => {
    const h = (_e: unknown, q: Quat) => cb(q);
    ipcRenderer.on(IPC.Sensor, h);
    return () => ipcRenderer.off(IPC.Sensor, h);
  },
  onState: (cb: (s: ConnectionState) => void) => {
    const h = (_e: unknown, s: ConnectionState) => cb(s);
    ipcRenderer.on(IPC.ConnectionState, h);
    return () => ipcRenderer.off(IPC.ConnectionState, h);
  },

  /** 请求手机立刻出关键帧 */
  requestKeyframe: () => ipcRenderer.send(IPC.RequestKeyframe),
};

export type DesktopApi = typeof api;

contextBridge.exposeInMainWorld("desktop", api);
