import { NextResponse } from "next/server";

// 返回 WebRTC ICE 服务器配置（生产环境在此注入 TURN 凭证）
export async function GET() {
  return NextResponse.json({
    iceServers: [{ urls: "stun:stun.l.google.com:19302" }],
  });
}
