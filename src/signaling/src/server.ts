import { WebSocketServer, WebSocket } from "ws";
import { randomUUID } from "crypto";
import { fileURLToPath } from "node:url";
import { IrohRelay } from "./irohBridge";
import type { ClientMessage, ServerMessage } from "./protocol";

const WS_PORT = Number(process.env.PORT) || 8080; // 浏览器连接的 WebSocket 端口
const IROH_BIND = process.env.IROH_BIND_ADDR || "0.0.0.0:8787"; // iroh 端点 UDP 地址

// 房间 -> 浏览器 viewer 的 WebSocket 集合
const rooms = new Map<string, Set<WebSocket>>();
const viewerRoom = new Map<WebSocket, string>();

function send(ws: WebSocket, msg: ServerMessage) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
}

/**
 * 启动面向浏览器的 WebSocket 信令服务，并与 iroh 中继双向桥接。
 * - caster 经 iroh 发来的 signal / sensor 广播给房间内 viewer
 * - viewer 发来的 signal 经 iroh 转发给 caster
 * - caster 注册/离开事件触发 viewer 的 peer-joined / peer-left
 */
export function startSignalingServer(relay: IrohRelay, wsPort = WS_PORT): WebSocketServer {
  // caster 发来的 signal / sensor -> 广播给房间内 viewer
  relay.on("caster-message", (room: string, msg: any) => {
    const viewers = rooms.get(room);
    if (!viewers) return;
    for (const ws of viewers) {
      if (msg.type === "signal") {
        send(ws, { type: "signal", from: "caster", data: msg.data });
      } else if (msg.type === "sensor") {
        send(ws, { type: "sensor", q: msg.q, t: msg.t });
      }
    }
  });

  // caster 注册 / 离开 -> 通知房间内 viewer，并触发 caster 发起 offer
  relay.on("caster-registered", (room: string) => {
    const viewers = rooms.get(room);
    if (!viewers) return;
    for (const ws of viewers) {
      send(ws, { type: "peer-joined", peer: { id: "caster", role: "caster" } });
    }
    relay.sendToCaster(room, { type: "peer-joined" });
  });

  relay.on("caster-left", (room: string) => {
    const viewers = rooms.get(room);
    if (!viewers) return;
    for (const ws of viewers) send(ws, { type: "peer-left", id: "caster" });
  });

  const wss = new WebSocketServer({ port: wsPort });

  const cleanup = (ws: WebSocket) => {
    const room = viewerRoom.get(ws);
    if (room) {
      rooms.get(room)?.delete(ws);
      viewerRoom.delete(ws);
    }
  };

  wss.on("connection", (ws) => {
    const id = randomUUID();

    ws.on("message", (raw) => {
      let msg: ClientMessage;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        return;
      }
      try {
        switch (msg.type) {
          case "join": {
            const room = msg.room;
            if (!rooms.has(room)) rooms.set(room, new Set());
            rooms.get(room)!.add(ws);
            viewerRoom.set(ws, room);

            const hasCaster = relay.hasCaster(room);
            const you = { id, role: msg.role, polite: msg.role === "viewer" };
            const peers = hasCaster ? [{ id: "caster", role: "caster" as const }] : [];
            send(ws, { type: "joined", room, you, peers });

            if (hasCaster) {
              send(ws, { type: "peer-joined", peer: { id: "caster", role: "caster" } });
              relay.sendToCaster(room, { type: "peer-joined" });
            }
            console.log(`[ws] ${msg.role} ${id} joined room ${room}`);
            break;
          }
          case "signal": {
            // viewer 的 SDP/ICE 经 iroh 转发给 caster
            relay.sendToCaster(msg.room, { type: "signal", data: msg.data });
            break;
          }
          case "sensor": {
            // caster 经 WS 通道发来的 sensor 姿态 -> 广播给房间内 viewer
            const viewers = rooms.get(msg.room);
            if (viewers) {
              for (const ws of viewers) {
                send(ws, { type: "sensor", q: msg.q, t: msg.t });
              }
            }
            break;
          }
          case "leave": {
            cleanup(ws);
            break;
          }
        }
      } catch (err) {
        console.error("[ws] message error", err);
      }
    });

    ws.on("close", () => cleanup(ws));
    ws.on("error", () => cleanup(ws));
  });

  console.log(`[ws] signaling server listening on ws://localhost:${wsPort}`);
  return wss;
}

async function main() {
  const relay = await IrohRelay.create(IROH_BIND);
  console.log(`[iroh] node id = ${relay.nodeId}`);
  console.log(`[iroh] connect ticket = ${relay.ticket}`);
  console.log(`[iroh] UDP bound at ${IROH_BIND} (Android caster 需使用上面的 ticket 连接)`);
  startSignalingServer(relay);
}

// 仅当本文件被直接执行时启动（被测试脚本 import 时不自动运行）
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((e) => {
    console.error("fatal", e);
    process.exit(1);
  });
}
