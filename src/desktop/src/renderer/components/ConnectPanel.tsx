import { useState } from "react";
import { useDesktop } from "../lib/store";

/**
 * 连接面板：展示 iroh ticket 二维码，手机端扫码即直连本机。
 * 媒体与姿态都走这条 iroh 连接，无需信令服务器 / STUN / TURN。
 */
export default function ConnectPanel() {
  const qr = useDesktop((s) => s.qr);
  const ticket = useDesktop((s) => s.ticket);
  const nodeId = useDesktop((s) => s.nodeId);
  const connection = useDesktop((s) => s.connection);
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    if (!ticket) return;
    try {
      await navigator.clipboard.writeText(ticket);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      /* 剪贴板不可用时忽略 */
    }
  };

  return (
    <footer
      style={{
        display: "flex",
        gap: 18,
        alignItems: "center",
        padding: "14px 16px",
        borderTop: "1px solid #1c2536",
        background: "#0d1320",
      }}
    >
      <div
        style={{
          width: 116,
          height: 116,
          background: "#fff",
          borderRadius: 8,
          padding: 6,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          flex: "0 0 auto",
        }}
      >
        {qr ? (
          <img src={qr} alt="iroh ticket" style={{ width: "100%", height: "100%" }} />
        ) : (
          <span style={{ color: "#6e7681", fontSize: 12 }}>初始化中…</span>
        )}
      </div>

      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14, marginBottom: 6 }}>
          {connection === "connected"
            ? "手机已直连，无需再扫码"
            : "用手机 App 扫描二维码（iroh 直连，自动 NAT 穿透）"}
        </div>
        <div
          style={{
            fontFamily: "ui-monospace, Menlo, Consolas, monospace",
            fontSize: 11,
            color: "#8b949e",
            wordBreak: "break-all",
            maxHeight: 46,
            overflow: "hidden",
          }}
        >
          {ticket ?? "-"}
        </div>
        {nodeId && (
          <div style={{ fontSize: 11, color: "#6e7681", marginTop: 4 }}>
            node id: {nodeId.slice(0, 24)}…
          </div>
        )}
      </div>

      <button
        onClick={copy}
        disabled={!ticket}
        style={{
          padding: "8px 14px",
          background: "transparent",
          color: ticket ? "#e6edf3" : "#6e7681",
          border: "1px solid #30363d",
          borderRadius: 6,
          cursor: ticket ? "pointer" : "default",
          font: "inherit",
          flex: "0 0 auto",
        }}
      >
        {copied ? "已复制" : "复制 ticket"}
      </button>
    </footer>
  );
}
