import { NextResponse } from "next/server";

// 返回 WebRTC ICE 服务器配置（生产环境在此注入 TURN 凭证）
export async function GET() {
  return NextResponse.json({
    iceServers: [
      { urls: "stun:stun.l.google.com:19302" },
      { urls: "stun:stun1.l.google.com:19302" },
      { urls: "stun:stun2.l.google.com:19302" },
      { urls: "stun:stun3.l.google.com:19302" },
      { urls: "stun:stun4.l.google.com:19302" },
      { urls: "stun:stun.voipbuster.com" },
      { urls: "stun:stun.stunprotocol.org:3478" },
    ],
  });
}
