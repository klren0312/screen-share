/**
 * 媒体通道协议（Android caster ↔ Electron viewer），三端共用同一份定义：
 * Rust core / Kotlin 侧为同构实现（见 src/mobile/iroh-core/src/lib.rs 与
 * Android 的 MediaCodecEncoder.kt / IrohMediaTransport.kt）。
 *
 * 连接层：单条 iroh QUIC 连接，双工：
 *  - 一条 bi stream：换行分隔 JSON 控制消息（hello / welcome / sensor）
 *  - 每帧视频一条 uni stream：定长 13 字节头 + Annex-B H.264 载荷
 */

/** ALPN，必须与 Android Rust core 保持一致 */
export const ALPN = "screen-share/1";

/** 媒体帧头长度（字节） */
export const MEDIA_HEADER_SIZE = 13;

/** 帧标志位 */
export const FrameFlags = {
  /** 载荷是编解码器配置（SPS/PPS），用于初始化解码器 */
  Config: 1 << 0,
  /** 关键帧（IDR） */
  Keyframe: 1 << 1,
} as const;

export interface MediaFrameHeader {
  flags: number;
  /** 展示时间戳（微秒，来自 MediaCodec 的 presentationTimeUs） */
  ptsUs: bigint;
  /** 载荷字节数 */
  length: number;
}

/**
 * 编码媒体帧：flags(1) | ptsUs(8, BE) | length(4, BE) | payload
 */
export function encodeMediaFrame(
  flags: number,
  ptsUs: number | bigint,
  payload: Uint8Array,
): Uint8Array {
  const out = new Uint8Array(MEDIA_HEADER_SIZE + payload.length);
  const view = new DataView(out.buffer);
  view.setUint8(0, flags);
  view.setBigUint64(1, BigInt(ptsUs));
  view.setUint32(9, payload.length);
  out.set(payload, MEDIA_HEADER_SIZE);
  return out;
}

/**
 * 解析 13 字节帧头。传入的 buffer 长度必须 >= MEDIA_HEADER_SIZE。
 */
export function decodeMediaFrameHeader(buf: Uint8Array): MediaFrameHeader {
  const view = new DataView(buf.buffer, buf.byteOffset, MEDIA_HEADER_SIZE);
  return {
    flags: view.getUint8(0),
    ptsUs: view.getBigUint64(1),
    length: view.getUint32(9),
  };
}

/** 控制消息（换行分隔 JSON，走 bi stream） */
export type CasterControlMessage =
  | {
      type: "hello";
      room: string;
      /** 编码器输出格式（AVC codec string，如 avc1.42E01F），供解码器 configure */
      codec: string;
      width: number;
      height: number;
    }
  | { type: "sensor"; q: { x: number; y: number; z: number; w: number }; t: number }
  | { type: "bye" };

export type ViewerControlMessage =
  | { type: "welcome"; sessionId: string }
  | { type: "request-keyframe" };

/** 主进程 → 渲染进程 的事件（IPC 通道名与载荷） */
export const IPC = {
  /** 媒体数据（单个视频帧的完整载荷） */
  MediaChunk: "media:chunk",
  /** 编解码器配置（SPS/PPS），用于初始化解码器 */
  MediaConfig: "media:config",
  /** 连接状态变化 */
  ConnectionState: "media:connection-state",
  /** 手机姿态四元数 */
  Sensor: "media:sensor",
  /** 渲染进程请求关键帧（或重连） */
  RequestKeyframe: "media:request-keyframe",
} as const;

export type ConnectionState = "waiting" | "connected" | "closed";

export interface MediaChunkPayload {
  /** 传输用 ArrayBuffer（transferable，避免拷贝） */
  data: ArrayBuffer;
  flags: number;
  /** ptsUs 超过 Number.MAX_SAFE_INTEGER 概率极低，但为保真仍按字符串传递 */
  ptsUs: string;
}
