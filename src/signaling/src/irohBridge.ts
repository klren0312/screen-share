import { Endpoint, EndpointTicket, presetN0 } from "@number0/iroh";
import { EventEmitter } from "node:events";
import type { CasterMessage, BridgeToCasterMessage } from "./protocol";

// iroh 应用层协议标识（ALPN），两端必须一致
const ALPN = Array.from(new TextEncoder().encode("screen-share/1"));

interface CasterSession {
  room: string;
  send: import("@number0/iroh").SendStream;
}

/**
 * iroh 中继：在服务端运行一个 iroh 端点，接收 Android(caster) 的 iroh QUIC 连接，
 * 并通过普通 WebSocket 与浏览器(viewer) 通信，在两端间中继 SDP/ICE 与 sensor 数据。
 *
 * 事件：
 *  - "caster-registered" (room)        caster 注册房间
 *  - "caster-message"    (room, msg)   caster 发来的 signal / sensor
 *  - "caster-left"       (room)        caster 断开
 */
export class IrohRelay extends EventEmitter {
  readonly endpoint: Endpoint;
  readonly ticket: string;
  private casters = new Map<string, CasterSession>();
  private acceptLoopStarted = false;

  private constructor(endpoint: Endpoint) {
    super();
    this.endpoint = endpoint;
    this.ticket = EndpointTicket.fromAddr(endpoint.addr()).toString();
  }

  static async create(bindAddr?: string): Promise<IrohRelay> {
    const builder = Endpoint.builder();
    presetN0(builder); // n0 预设：relay + 发现 + crypto provider
    builder.alpns([ALPN]);
    if (bindAddr) builder.bindAddr(bindAddr);
    const endpoint = await builder.bind();
    await endpoint.online(); // 等待获得可用的 home relay
    const relay = new IrohRelay(endpoint);
    relay.startAcceptLoop();
    return relay;
  }

  get nodeId(): string {
    return this.endpoint.id().toString();
  }

  hasCaster(room: string): boolean {
    return this.casters.has(room);
  }

  private startAcceptLoop() {
    if (this.acceptLoopStarted) return;
    this.acceptLoopStarted = true;
    (async () => {
      while (!this.endpoint.isClosed()) {
        let inc;
        try {
          inc = await this.endpoint.acceptNext();
        } catch {
          break;
        }
        if (!inc) break;
        this.handleIncoming(inc).catch((e) =>
          console.error("[iroh] incoming error", e),
        );
      }
    })();
  }

  private async handleIncoming(inc: import("@number0/iroh").Incoming) {
    const acc = await inc.accept();
    const conn = await acc.connect();
    this.handleCaster(conn).catch((e) =>
      console.error("[iroh] caster handling error", e),
    );
  }

  private async handleCaster(conn: import("@number0/iroh").Connection) {
    const bi = await conn.acceptBi();
    const send = bi.send;
    const recv = bi.recv;
    let buffer = "";
    let room: string | null = null;

    const emitClosed = () => {
      if (room && this.casters.get(room)?.send === send) {
        this.casters.delete(room);
        this.emit("caster-left", room);
        console.log(`[iroh] caster left room ${room}`);
      }
    };

    while (true) {
      let chunk: number[];
      try {
        chunk = await recv.read(4096);
      } catch {
        break;
      }
      if (!chunk || chunk.length === 0) break;
      buffer += Buffer.from(chunk).toString("utf8");
      let nl: number;
      while ((nl = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, nl);
        buffer = buffer.slice(nl + 1);
        if (!line.trim()) continue;
        let msg: CasterMessage;
        try {
          msg = JSON.parse(line);
        } catch {
          continue;
        }
        switch (msg.type) {
          case "register":
            room = msg.room;
            this.casters.set(room, { room, send });
            // 向 caster 回送 welcome：selfId 用其 iroh 节点 id，caster 固定为 impolite（由它发起 offer）
            await send.writeAll(
              Array.from(
                new TextEncoder().encode(
                  JSON.stringify({
                    type: "welcome",
                    you: { id: conn.remoteId().toString(), role: "caster", polite: false },
                    peerCount: 0,
                  }) + "\n",
                ),
              ),
            );
            this.emit("caster-registered", room);
            console.log(`[iroh] caster registered room ${room}`);
            break;
          case "signal":
            if (room) this.emit("caster-message", room, { type: "signal", data: msg.data });
            break;
          case "sensor":
            if (room) this.emit("caster-message", room, { type: "sensor", q: msg.q, t: msg.t });
            break;
          case "leave":
            if (room) {
              this.casters.delete(room);
              this.emit("caster-left", room);
            }
            return;
        }
      }
    }
    emitClosed();
  }

  /** 向房间内的 caster 发送消息（来自 viewer 的 signal 或 peer 事件） */
  async sendToCaster(room: string, msg: BridgeToCasterMessage): Promise<boolean> {
    const c = this.casters.get(room);
    if (!c) return false;
    try {
      await c.send.writeAll(
        Array.from(new TextEncoder().encode(JSON.stringify(msg) + "\n")),
      );
      return true;
    } catch (e) {
      console.error("[iroh] sendToCaster failed", e);
      return false;
    }
  }

  async shutdown() {
    await this.endpoint.close();
  }
}
