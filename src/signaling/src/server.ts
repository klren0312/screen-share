import { WebSocketServer } from "ws";
import { randomUUID } from "crypto";
import {
  register,
  unregister,
  joinRoom,
  leaveRoom,
  leaveAll,
  sendTo,
  relay,
  notifyPeers,
} from "./rooms";
import type { ClientMessage, Role } from "./protocol";

const PORT = Number(process.env.PORT) || 8080;
const wss = new WebSocketServer({ port: PORT });

wss.on("connection", (ws) => {
  const id = randomUUID();
  register(id, ws, "viewer"); // role 在 join 时更新

  ws.on("message", (raw) => {
    let msg: ClientMessage;
    try {
      msg = JSON.parse(raw.toString()) as ClientMessage;
    } catch {
      return;
    }
    try {
      switch (msg.type) {
        case "join": {
          const role = msg.role as Role;
          register(id, ws, role);
          const { you, peers } = joinRoom(msg.room, id, role);
          sendTo(id, { type: "joined", room: msg.room, you, peers });
          notifyPeers(msg.room, id, {
            type: "peer-joined",
            peer: { id, role },
          });
          console.log(`[signaling] ${role} ${id} joined room ${msg.room}`);
          break;
        }
        case "signal": {
          relay(msg.room, id, msg.data);
          break;
        }
        case "leave": {
          leaveRoom(msg.room, id);
          break;
        }
      }
    } catch (err) {
      console.error("[signaling] message handling error", err);
    }
  });

  const cleanup = () => {
    leaveAll(id);
    unregister(id);
  };
  ws.on("close", cleanup);
  ws.on("error", cleanup);
});

console.log(`[signaling] WebSocket server listening on ws://localhost:${PORT}`);
