"use client";

import { useCallback, useEffect, useRef } from "react";
import { useSession } from "./store";
import { SignalingClient } from "./signaling";
import { Peer } from "./peer";
import { SIGNALING_WS_URL, ICE_SERVERS } from "./config";

// 在 Web (viewer) 端挂载会话：连接信令、建立 PeerConnection、
// 接收远端屏幕流与传感器姿态（sensor 经信令中继通道下发）。
//
// 返回值 refreshTicket 用于重新向信令服务索取 iroh ticket（刷新二维码）。
export function useScreenShare(roomId: string, role: "viewer" | "caster") {
  const signalingRef = useRef<SignalingClient | null>(null);

  useEffect(() => {
    useSession.getState().set({
      roomId,
      role,
      signalingStatus: "connecting",
      connectionState: "new",
      peers: [],
      stream: null,
      pose: { x: 0, y: 0, z: 0, w: 1 },
      base: { x: 0, y: 0, z: 0, w: 1 },
      irohTicket: null,
    });

    const signaling = new SignalingClient(SIGNALING_WS_URL);
    signalingRef.current = signaling;
    let peer: Peer | null = null;

    const off = signaling.onMessage(async (msg) => {
      switch (msg.type) {
        case "joined": {
          useSession.getState().set({
            selfId: msg.you.id,
            polite: msg.you.polite,
            peers: msg.peers,
            signalingStatus: "open",
          });
          peer = new Peer(msg.you.polite, ICE_SERVERS, {
            sendSignal: (data) => signaling.sendSignal(roomId, data),
            onTrack: (stream) => useSession.getState().set({ stream }),
            onDataChannel: () => {},
            onConnectionState: (st) =>
              useSession.getState().set({ connectionState: st }),
          });
          break;
        }
        case "peer-joined": {
          const peers = [...useSession.getState().peers, msg.peer];
          useSession.getState().set({ peers });
          break;
        }
        case "peer-left": {
          const peers = useSession.getState().peers.filter(
            (p) => p.id !== msg.id,
          );
          useSession.getState().set({ peers });
          break;
        }
        case "signal": {
          if (peer) await peer.onSignal(msg.data);
          break;
        }
        case "sensor": {
          // sensor 姿态经信令中继通道下发（不再走 WebRTC DataChannel）
          useSession.getState().setPose(msg.q);
          break;
        }
        case "iroh-ticket": {
          // 信令服务 iroh 端点 ticket，供二维码展示给 Android 扫码直连
          useSession.getState().set({ irohTicket: msg.ticket });
          break;
        }
        case "error": {
          console.error("signaling error:", msg.message);
          break;
        }
      }
    });

    signaling
      .connect()
      .then(() => signaling.join(roomId, role))
      .catch((e) => {
        console.error("signaling connect failed", e);
        useSession.getState().set({ signalingStatus: "error" });
      });

    return () => {
      off();
      peer?.close();
      signaling.close();
      signalingRef.current = null;
      useSession.getState().set({
        connectionState: "closed",
        signalingStatus: "closed",
      });
    };
  }, [roomId, role]);

  const refreshTicket = useCallback(() => {
    signalingRef.current?.requestTicket();
  }, []);

  return { refreshTicket };
}
