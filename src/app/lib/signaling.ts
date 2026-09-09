import type { ClientMessage, ServerMessage, SignalData } from "./types";

type Handler = (msg: ServerMessage) => void;

// 信令 WebSocket 客户端封装
export class SignalingClient {
  private ws: WebSocket | null = null;
  private url: string;
  private handlers = new Set<Handler>();

  constructor(url: string) {
    this.url = url;
  }

  onMessage(h: Handler): () => void {
    this.handlers.add(h);
    return () => this.handlers.delete(h);
  }

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url);
      this.ws = ws;
      ws.onopen = () => resolve();
      ws.onerror = (e) => reject(e);
      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data as string) as ServerMessage;
          this.handlers.forEach((h) => h(msg));
        } catch {
          /* ignore malformed */
        }
      };
    });
  }

  join(room: string, role: "viewer" | "caster") {
    this.send({ type: "join", room, role });
  }

  sendSignal(room: string, data: SignalData) {
    this.send({ type: "signal", room, data });
  }

  leave(room: string) {
    this.send({ type: "leave", room });
  }

  private send(msg: ClientMessage) {
    this.ws?.send(JSON.stringify(msg));
  }

  close() {
    this.ws?.close();
    this.ws = null;
  }
}
