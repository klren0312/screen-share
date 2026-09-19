import { EventEmitter } from "node:events";
import {
  Endpoint,
  EndpointTicket,
  presetN0,
  type Connection,
  type RecvStream,
  type SendStream,
} from "@number0/iroh";
import {
  ALPN,
  FrameFlags,
  MEDIA_HEADER_SIZE,
  decodeMediaFrameHeader,
  type CasterControlMessage,
  type ConnectionState,
  type ViewerControlMessage,
} from "../shared/protocol";

const ALPN_BYTES = Array.from(new TextEncoder().encode(ALPN));

/**
 * 重排窗口：每帧走一条独立 uni stream，QUIC 只保证流内有序，不保证流间有序。
 * 按 ptsUs 排序并保留 REORDER_WINDOW 帧的窗口，再按序交给解码器。
 */
const REORDER_WINDOW = 3;

/** 超过这个空闲时间就把缓冲里剩下的帧全部放出，避免低帧率时画面卡住 */
const IDLE_FLUSH_MS = 30;

export interface VideoFramePayload {
  data: Uint8Array;
  flags: number;
  ptsUs: bigint;
}

/**
 * Electron 侧的 iroh 端点：监听 Android caster 直连，接收
 *  - bi stream 上的换行分隔 JSON 控制消息（hello / sensor）
 *  - 每帧一条 uni stream 的 H.264 媒体帧
 *
 * 媒体不经过 WebRTC，因此不依赖 ICE/STUN/TURN；iroh 自带 relay 兜底。
 *
 * 事件：
 *  - "state"   (ConnectionState)
 *  - "control" (CasterControlMessage)
 *  - "config"  (Uint8Array)  SPS/PPS
 *  - "frame"   (VideoFramePayload)
 */
export class IrohViewer extends EventEmitter {
  readonly ticket: string;
  private conn: Connection | null = null;
  private sendStream: SendStream | null = null;
  private reorderBuffer: VideoFramePayload[] = [];
  private idleTimer: NodeJS.Timeout | null = null;
  private closing = false;

  private constructor(private readonly endpoint: Endpoint) {
    super();
    this.ticket = EndpointTicket.fromAddr(endpoint.addr()).toString();
  }

  get nodeId(): string {
    return this.endpoint.id().toString();
  }

  static async create(bindAddr = "0.0.0.0:0"): Promise<IrohViewer> {
    const builder = Endpoint.builder();
    presetN0(builder); // n0 预设：relay + 发现 + crypto provider
    builder.alpns([ALPN_BYTES]);
    builder.bindAddr(bindAddr);
    const endpoint = await builder.bind();
    await endpoint.online(); // 等到拿到 home relay，ticket 才完整可用
    const viewer = new IrohViewer(endpoint);
    viewer.startAcceptLoop();
    return viewer;
  }

  /** 向 caster 发送控制消息（bi stream，换行分隔 JSON） */
  async sendControl(msg: ViewerControlMessage): Promise<void> {
    const send = this.sendStream;
    if (!send) return;
    try {
      await send.writeAll(
        Array.from(new TextEncoder().encode(JSON.stringify(msg) + "\n")),
      );
    } catch (e) {
      console.error("[iroh] sendControl failed", e);
    }
  }

  /** 请求对端立刻出关键帧（解码器刚初始化 / 解码报错时调用） */
  requestKeyframe(): Promise<void> {
    return this.sendControl({ type: "request-keyframe" });
  }

  async shutdown(): Promise<void> {
    this.closing = true;
    if (this.idleTimer) clearTimeout(this.idleTimer);
    this.idleTimer = null;
    this.reorderBuffer = [];
    try {
      this.conn?.close(0n, []);
    } catch {
      /* 连接可能已关闭 */
    }
    this.conn = null;
    this.sendStream = null;
    await this.endpoint.close();
  }

  private startAcceptLoop() {
    void (async () => {
      while (!this.closing && !this.endpoint.isClosed()) {
        let incoming;
        try {
          incoming = await this.endpoint.acceptNext();
        } catch {
          break;
        }
        if (!incoming) break;
        this.handleIncoming(incoming).catch((e) =>
          console.error("[iroh] incoming error", e),
        );
      }
    })();
  }

  private async handleIncoming(incoming: import("@number0/iroh").Incoming) {
    const accepting = await incoming.accept();
    const conn = await accepting.connect();
    console.log(`[iroh] caster connected: ${conn.remoteId().toString()}`);

    // 1:1 直连：新连接顶掉旧连接
    if (this.conn) {
      try {
        this.conn.close(0n, []);
      } catch {
        /* ignore */
      }
    }
    this.conn = conn;
    this.reorderBuffer = [];
    this.emit("state", "connected" satisfies ConnectionState);

    // 控制流与媒体流并行消费
    void this.readControlLoop(conn);
    void this.readMediaLoop(conn);
  }

  private async readControlLoop(conn: Connection) {
    let bi;
    try {
      bi = await conn.acceptBi();
    } catch (e) {
      console.error("[iroh] acceptBi failed", e);
      return;
    }
    this.sendStream = bi.send;
    await this.sendControl({
      type: "welcome",
      sessionId: conn.remoteId().toString(),
    });

    let buffer = "";
    while (true) {
      let chunk: number[];
      try {
        chunk = await bi.recv.read(4096);
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
        try {
          this.emit("control", JSON.parse(line) as CasterControlMessage);
        } catch {
          /* 忽略半条/畸形消息 */
        }
      }
    }

    console.log("[iroh] caster disconnected");
    this.sendStream = null;
    this.emit("state", "closed" satisfies ConnectionState);
  }

  private async readMediaLoop(conn: Connection) {
    while (true) {
      let recv: RecvStream;
      try {
        recv = await conn.acceptUni();
      } catch {
        break; // 连接关闭
      }
      // 必须并发处理：串行读会让后续帧排在 acceptUni 后面
      this.readOneFrame(recv).catch((e) =>
        console.error("[iroh] frame read error", e),
      );
    }
  }

  private async readOneFrame(recv: RecvStream) {
    const head = new Uint8Array(await recv.readExact(MEDIA_HEADER_SIZE));
    const { flags, ptsUs, length } = decodeMediaFrameHeader(head);
    if (length === 0) return;
    const payload = new Uint8Array(await recv.readExact(length));

    if (flags & FrameFlags.Config) {
      // 编解码器配置（SPS/PPS）：渲染进程用它 configure VideoDecoder
      this.emit("config", payload);
      return;
    }
    this.pushFrame({ data: payload, flags, ptsUs });
  }

  private pushFrame(frame: VideoFramePayload) {
    this.reorderBuffer.push(frame);
    if (this.reorderBuffer.length > 1) {
      this.reorderBuffer.sort((a, b) =>
        a.ptsUs < b.ptsUs ? -1 : a.ptsUs > b.ptsUs ? 1 : 0,
      );
    }
    if (this.reorderBuffer.length > REORDER_WINDOW) this.flushOldest();
    this.armIdleFlush();
  }

  private flushOldest() {
    const frame = this.reorderBuffer.shift();
    if (frame) this.emit("frame", frame);
  }

  /** 低帧率/暂停时把缓冲里剩下的帧放出去，否则会一直压着最后一帧 */
  private armIdleFlush() {
    if (this.idleTimer) return;
    this.idleTimer = setTimeout(() => {
      this.idleTimer = null;
      while (this.reorderBuffer.length > 0) this.flushOldest();
    }, IDLE_FLUSH_MS);
  }
}
