"use client";

import { useEffect, useState } from "react";
import type { CSSProperties } from "react";
import QRCode from "qrcode";
import { useSession } from "../lib/store";

// 二维码内容格式：JSON { t: iroh ticket, r: 房间号 }
// Android 端扫码后解析出 ticket 与房间号，直接经 iroh 直连信令网桥，无需手动输入。
function payloadOf(ticket: string, room: string | null) {
  return JSON.stringify({ t: ticket, r: room ?? "" });
}

export default function QrPanel({ onRefresh }: { onRefresh?: () => void }) {
  const ticket = useSession((s) => s.irohTicket);
  const roomId = useSession((s) => s.roomId);
  const [open, setOpen] = useState(true);
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!ticket) {
      setDataUrl(null);
      return;
    }
    let cancelled = false;
    QRCode.toDataURL(payloadOf(ticket, roomId), {
      errorCorrectionLevel: "M",
      margin: 1,
      width: 260,
    })
      .then((url) => {
        if (cancelled) return;
        setDataUrl(url);
        setError(null);
      })
      .catch((e) => {
        if (!cancelled) setError(String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [ticket, roomId]);

  const copyPayload = async () => {
    if (!ticket) return;
    try {
      await navigator.clipboard.writeText(payloadOf(ticket, roomId));
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* 非安全上下文下剪贴板不可用，忽略 */
    }
  };

  if (!open) {
    return (
      <button onClick={() => setOpen(true)} style={styles.fab}>
        显示连接二维码
      </button>
    );
  }

  return (
    <div style={styles.panel}>
      <div style={styles.header}>
        <strong style={{ fontSize: 13, color: "#111" }}>扫码连接（iroh）</strong>
        <button onClick={() => setOpen(false)} style={styles.link}>
          收起
        </button>
      </div>

      {ticket ? (
        dataUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={dataUrl}
            alt="iroh 连接二维码"
            style={{ width: 220, height: 220, background: "#fff" }}
          />
        ) : (
          <div style={styles.box}>
            {error ? "二维码生成失败" : "生成中…"}
          </div>
        )
      ) : (
        <div style={styles.box}>等待信令服务下发 ticket…</div>
      )}

      <div style={styles.meta}>
        房间：<code>{roomId}</code>
      </div>
      <div style={styles.actions}>
        <button onClick={onRefresh} style={styles.btn}>
          刷新
        </button>
        <button onClick={copyPayload} disabled={!ticket} style={styles.btn}>
          {copied ? "已复制" : "复制"}
        </button>
      </div>
      <div style={styles.hint}>用 Android 端「扫码连接」扫描此码</div>
    </div>
  );
}

const styles: Record<string, CSSProperties> = {
  panel: {
    position: "fixed",
    right: 16,
    bottom: 16,
    zIndex: 20,
    background: "#fff",
    color: "#111",
    borderRadius: 10,
    padding: 12,
    boxShadow: "0 8px 28px rgba(0,0,0,0.45)",
    display: "flex",
    flexDirection: "column",
    alignItems: "center",
    gap: 2,
  },
  fab: {
    position: "fixed",
    right: 16,
    bottom: 16,
    zIndex: 20,
    padding: "8px 14px",
    borderRadius: 8,
    border: "1px solid #30363d",
    background: "#161b22",
    color: "#e6edf3",
    cursor: "pointer",
    fontSize: 13,
  },
  header: {
    display: "flex",
    width: "100%",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 8,
  },
  link: {
    background: "none",
    border: "none",
    color: "#1f6feb",
    cursor: "pointer",
    fontSize: 12,
  },
  box: {
    width: 220,
    height: 220,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    color: "#666",
    fontSize: 12,
    textAlign: "center",
    padding: 8,
    boxSizing: "border-box",
  },
  meta: { marginTop: 8, fontSize: 12, color: "#444" },
  actions: { marginTop: 8, display: "flex", gap: 8 },
  btn: {
    padding: "6px 12px",
    borderRadius: 6,
    border: "1px solid #d0d7de",
    background: "#f6f8fa",
    color: "#111",
    cursor: "pointer",
    fontSize: 12,
  },
  hint: { marginTop: 6, fontSize: 11, color: "#6e7681" },
};
