"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

function genRoom(n = 6) {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let s = "";
  for (let i = 0; i < n; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}

export default function HomePage() {
  const router = useRouter();
  const [room, setRoom] = useState("");

  const enter = (id: string) => {
    const r = id.trim().toUpperCase() || genRoom();
    router.push(`/session/${r}`);
  };

  return (
    <main
      style={{
        height: "100%",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        gap: 18,
        padding: 24,
      }}
    >
      <h1 style={{ margin: 0 }}>移动端屏幕共享 · 3D 可视化</h1>
      <p style={{ color: "#8b949e", maxWidth: 520, textAlign: "center" }}>
        作为 Web 观看端进入房间，然后将下方房间号输入到 Android
        共享端即可实时看到屏幕画面与手机姿态的 3D 同步。
      </p>
      <div style={{ display: "flex", gap: 8, width: 360, maxWidth: "100%" }}>
        <input
          value={room}
          onChange={(e) => setRoom(e.target.value)}
          placeholder="输入房间号（留空则随机生成）"
          style={{
            flex: 1,
            padding: "10px 12px",
            borderRadius: 6,
            border: "1px solid #30363d",
            background: "#0d1322",
            color: "#e6edf3",
          }}
        />
        <button
          onClick={() => enter(room)}
          style={{
            padding: "10px 18px",
            borderRadius: 6,
            border: "none",
            background: "#1f6feb",
            color: "#fff",
            cursor: "pointer",
          }}
        >
          进入
        </button>
      </div>
      <button
        onClick={() => enter(genRoom())}
        style={{
          padding: "10px 18px",
          borderRadius: 6,
          border: "1px solid #30363d",
          background: "transparent",
          color: "#e6edf3",
          cursor: "pointer",
        }}
      >
        随机创建房间
      </button>
    </main>
  );
}
