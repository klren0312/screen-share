import { NextRequest, NextResponse } from "next/server";

function genRoom(n = 6) {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let s = "";
  for (let i = 0; i < n; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}

// 创建会话：返回随机房间号（真正的房间状态由信令服务维护）
export async function POST() {
  return NextResponse.json({ roomId: genRoom() });
}

// 查询会话引导信息（ICE 服务器、信令地址）
export async function GET(req: NextRequest) {
  const room = req.nextUrl.searchParams.get("room");
  return NextResponse.json({
    room,
    wsUrl:
      process.env.NEXT_PUBLIC_SIGNALING_WS_URL || "ws://localhost:8080",
    iceServers: [{ urls: "stun:stun.l.google.com:19302" }],
  });
}
