"use client";

import { useSession } from "../lib/store";
import { SIGNALING_WS_URL } from "../lib/config";

const statusColor: Record<string, string> = {
  idle: "#8b949e",
  connecting: "#d29922",
  open: "#2ea043",
  closed: "#8b949e",
  error: "#f85149",
};

export default function StatusBar({ roomId }: { roomId: string }) {
  const signaling = useSession((s) => s.signalingStatus);
  const conn = useSession((s) => s.connectionState);
  const peers = useSession((s) => s.peers);
  const selfId = useSession((s) => s.selfId);

  return (
    <header
      style={{
        display: "flex",
        alignItems: "center",
        gap: 16,
        padding: "10px 16px",
        borderBottom: "1px solid #1c2536",
        background: "#0d1322",
        fontSize: 13,
        flexWrap: "wrap",
      }}
    >
      <strong style={{ fontSize: 15 }}>Screen Share 3D</strong>
      <span>
        房间 <code>{roomId}</code>
      </span>
      <span style={{ color: statusColor[signaling] ?? "#8b949e" }}>
        信令：{signaling}
      </span>
      <span style={{ color: conn === "connected" ? "#2ea043" : "#d29922" }}>
        WebRTC：{conn}
      </span>
      <span>在线端：{peers.length + (selfId ? 1 : 0)}</span>
      <span style={{ color: "#6e7681" }}>信令地址：{SIGNALING_WS_URL}</span>
      <span style={{ color: "#6e7681" }}>
        提示：在 Android 端输入相同房间号（{roomId}）开始共享
      </span>
    </header>
  );
}
