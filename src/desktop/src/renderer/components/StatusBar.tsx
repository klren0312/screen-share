import { useDesktop } from "../lib/store";

const STATE_TEXT: Record<string, string> = {
  waiting: "等待手机连接",
  connected: "已连接",
  closed: "已断开",
};

const STATE_COLOR: Record<string, string> = {
  waiting: "#d29922",
  connected: "#3fb950",
  closed: "#f85149",
};

export default function StatusBar() {
  const connection = useDesktop((s) => s.connection);
  const endpointReady = useDesktop((s) => s.endpointReady);
  const decoderReady = useDesktop((s) => s.decoderReady);
  const resolution = useDesktop((s) => s.resolution);
  const codec = useDesktop((s) => s.codec);
  const fps = useDesktop((s) => s.fps);
  const received = useDesktop((s) => s.receivedFrames);
  const decoded = useDesktop((s) => s.decodedFrames);
  const lastError = useDesktop((s) => s.lastError);

  return (
    <header
      style={{
        display: "flex",
        alignItems: "center",
        gap: 16,
        padding: "10px 16px",
        borderBottom: "1px solid #1c2536",
        background: "#0d1320",
        fontSize: 13,
        flexWrap: "wrap",
      }}
    >
      <strong style={{ fontSize: 14 }}>屏幕共享接收端</strong>

      <Chip label="端点" value={endpointReady ? "就绪" : "初始化中"} ok={endpointReady} />
      <Chip
        label="连接"
        value={STATE_TEXT[connection] ?? connection}
        color={STATE_COLOR[connection]}
      />
      <Chip label="解码器" value={decoderReady ? "已配置" : "未配置"} ok={decoderReady} />

      <span style={{ color: "#8b949e" }}>
        分辨率 <b style={{ color: "#e6edf3" }}>{resolution}</b>
      </span>
      <span style={{ color: "#8b949e" }}>
        codec <b style={{ color: "#e6edf3" }}>{codec ?? "-"}</b>
      </span>
      <span style={{ color: "#8b949e" }}>
        FPS <b style={{ color: "#e6edf3" }}>{fps}</b>
      </span>
      <span style={{ color: "#8b949e" }}>
        帧 <b style={{ color: "#e6edf3" }}>{decoded}</b>
        <span style={{ color: "#6e7681" }}>/{received}</span>
      </span>

      {lastError && (
        <span style={{ color: "#f85149", marginLeft: "auto" }} title={lastError}>
          错误：{lastError.slice(0, 60)}
        </span>
      )}
    </header>
  );
}

function Chip({
  label,
  value,
  ok,
  color,
}: {
  label: string;
  value: string;
  ok?: boolean;
  color?: string;
}) {
  const c = color ?? (ok ? "#3fb950" : "#8b949e");
  return (
    <span
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 6,
        padding: "3px 9px",
        border: "1px solid #212a3b",
        borderRadius: 999,
        background: "#0b1220",
      }}
    >
      <span style={{ width: 7, height: 7, borderRadius: "50%", background: c }} />
      <span style={{ color: "#8b949e" }}>{label}</span>
      <span style={{ color: "#e6edf3" }}>{value}</span>
    </span>
  );
}
