"use client";

import { useEffect } from "react";
import { useSession } from "./store";
import { SignalingClient } from "./signaling";
import { Peer } from "./peer";
import { SIGNALING_WS_URL, ICE_SERVERS } from "./config";
import type { PostureMessage } from "./types";

// 在 Web (viewer) 端挂载会话：连接信令、建立 PeerConnection、
// 接收远端屏幕流与传感器姿态数据通道。
export function useScreenShare(roomId: string, role: "viewer" | "caster") {
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
    });

    const signaling = new SignalingClient(SIGNALING_WS_URL);
    let peer: Peer | null = null;

    const setupDataChannel = (dc: RTCDataChannel) => {
      dc.onmessage = (ev) => {
        try {
          const m = JSON.parse(ev.data as string) as PostureMessage;
          if (m.q) useSession.getState().setPose(m.q);
        } catch {
          /* ignore */
        }
      };
    };

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
            onDataChannel: (dc) => setupDataChannel(dc),
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
      useSession.getState().set({
        connectionState: "closed",
        signalingStatus: "closed",
      });
    };
  }, [roomId, role]);
}
