import { FrameFlags } from "../../shared/protocol";

/**
 * H.264 解码器（WebCodecs）。
 *
 * 输入是 Android MediaCodec 产出的 Annex-B 码流：
 *  - 编解码器配置帧（SPS/PPS）单独到达，作为参数集缓存
 *  - 关键帧（IDR）解码前若无参数集，则把缓存的 SPS/PPS 前置
 *
 * codec string 优先取 hello 里协商的值；缺失时从 SPS 自行推导。
 */

/** 从 NAL 起始码后读取 nal_unit_type */
function nalTypes(data: Uint8Array, limit = 512): Set<number> {
  const types = new Set<number>();
  const end = Math.min(data.length - 3, limit);
  for (let i = 0; i < end; i++) {
    if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
      types.add(data[i + 3] & 0x1f);
      i += 3;
    }
  }
  return types;
}

function hex2(n: number): string {
  return n.toString(16).padStart(2, "0");
}

/** 从 Annex-B 数据里找 SPS 并生成 avc1.PPCCLL */
export function codecStringFromAnnexB(data: Uint8Array): string | null {
  const end = Math.min(data.length - 3, 512);
  for (let i = 0; i < end; i++) {
    if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
      const type = data[i + 3] & 0x1f;
      if (type === 7 && i + 7 < data.length) {
        const profile = data[i + 4];
        const constraints = data[i + 5];
        const level = data[i + 6];
        return `avc1.${hex2(profile)}${hex2(constraints)}${hex2(level)}`;
      }
      i += 3;
    }
  }
  return null;
}

function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

export interface DecoderHooks {
  onFrame: (frame: VideoFrame) => void;
  onError?: (message: string) => void;
  /** 解码器需要关键帧才能继续（重建/配置后），由上层请求对端出 IDR */
  onNeedKeyframe?: () => void;
}

export class H264Decoder {
  private decoder: VideoDecoder | null = null;
  private parameterSets: Uint8Array | null = null;
  private codec: string | null = null;
  /** 实际用于 configure 的 codec string，用于判断 SPS 变化后是否需要重建 */
  private configuredCodec: string | null = null;
  private configured = false;
  /** 配置后/解码出错后必须等到关键帧才能继续喂数据 */
  private awaitingKeyframe = true;
  private decodeErrors = 0;

  constructor(private readonly hooks: DecoderHooks) {}

  get isConfigured(): boolean {
    return this.configured;
  }

  get errorCount(): number {
    return this.decodeErrors;
  }

  /** hello 里的编码参数（avc1.xxxxxx），仅作提示；有 SPS 时以 SPS 为准 */
  setCodec(codec: string) {
    if (this.codec === codec) return;
    this.codec = codec;
    this.tryConfigure();
  }

  /** SPS/PPS（Annex-B） */
  setParameterSets(ps: Uint8Array) {
    this.parameterSets = ps;
    // SPS 才是权威：MediaCodec 实际协商的 profile/level 常与 createVideoFormat 的预设不同
    // （例如实际输出 High profile），codec string 不匹配会让 configure 直接失败
    const fromSps = codecStringFromAnnexB(ps);
    if (fromSps && this.configuredCodec && fromSps !== this.configuredCodec) {
      this.configured = false;
    }
    this.tryConfigure();
  }

  private tryConfigure(): boolean {
    if (this.configured && this.decoder?.state === "configured") return true;
    const codec =
      (this.parameterSets ? codecStringFromAnnexB(this.parameterSets) : null) ?? this.codec;
    if (!codec) return false;

    try {
      this.decoder?.close();
    } catch {
      /* 已关闭 */
    }

    const config: VideoDecoderConfig = {
      codec,
      // Annex-B 输入：参数集随流下发，无需 avcC description
      ...(codec.startsWith("avc1") || codec.startsWith("avc3")
        ? { avc: { format: "annexb" as const } }
        : {}),
      optimizeForLatency: true,
    };

    try {
      this.decoder = new VideoDecoder({
        output: (frame) => this.hooks.onFrame(frame),
        error: (e) => this.handleDecoderError(e),
      });
      this.decoder.configure(config);
    } catch (e) {
      this.hooks.onError?.(`VideoDecoder configure 失败（${codec}）：${(e as Error).message}`);
      this.decoder = null;
      this.configured = false;
      return false;
    }

    this.configured = true;
    this.configuredCodec = codec;
    this.awaitingKeyframe = true;
    this.hooks.onNeedKeyframe?.();
    return true;
  }

  private handleDecoderError(e: DOMException) {
    this.decodeErrors += 1;
    this.hooks.onError?.(`解码错误：${e.name} ${e.message}`);
    // VideoDecoder 出错后进入 closed 状态，必须重建
    this.configured = false;
    this.awaitingKeyframe = true;
    try {
      this.decoder?.close();
    } catch {
      /* ignore */
    }
    this.decoder = null;
    this.hooks.onNeedKeyframe?.();
  }

  push(chunk: { data: Uint8Array; flags: number; ptsUs: string }) {
    if (!this.configured && !this.tryConfigure()) return;
    const decoder = this.decoder;
    if (!decoder || decoder.state !== "configured") return;

    const isKey = (chunk.flags & FrameFlags.Keyframe) !== 0;
    if (this.awaitingKeyframe && !isKey) return; // 参数集缺失时喂 delta 帧只会报错

    // 解码队列堆积说明追不上，丢掉非关键帧以免延迟滚雪球
    if (decoder.decodeQueueSize > 16 && !isKey) return;

    let data = chunk.data;
    if (isKey && this.parameterSets && !hasParameterSets(data)) {
      data = concat(this.parameterSets, data);
    }
    if (isKey) this.awaitingKeyframe = false;

    try {
      decoder.decode(
        new EncodedVideoChunk({
          type: isKey ? "key" : "delta",
          timestamp: Number(chunk.ptsUs),
          data,
        }),
      );
    } catch (e) {
      this.hooks.onError?.(`提交解码失败：${(e as Error).message}`);
    }
  }

  reset() {
    try {
      this.decoder?.close();
    } catch {
      /* ignore */
    }
    this.decoder = null;
    this.configured = false;
    this.configuredCodec = null;
    this.awaitingKeyframe = true;
    this.decodeErrors = 0;
  }

  close() {
    this.reset();
    this.parameterSets = null;
    this.codec = null;
  }
}

function hasParameterSets(data: Uint8Array): boolean {
  const types = nalTypes(data, 256);
  return types.has(7) && types.has(8);
}
