import type { WebSocket } from "ws";
import type { PeerInfo, Role, SelfInfo, ServerMessage } from "./protocol";

// 全局客户端注册表：id -> { ws, role }
const clients = new Map<string, { ws: WebSocket; role: Role }>();
// 房间 -> 成员 id 集合
const rooms = new Map<string, Set<string>>();

export function register(id: string, ws: WebSocket, role: Role) {
  clients.set(id, { ws, role });
}

export function unregister(id: string) {
  clients.delete(id);
}

export function joinRoom(
  room: string,
  id: string,
  role: Role,
): { you: SelfInfo; peers: PeerInfo[] } {
  let set = rooms.get(room);
  // 第一个进入房间者为 impolite(!polite)，后续进入者为 polite
  const polite = !!set && set.size > 0;
  if (!set) {
    set = new Set();
    rooms.set(room, set);
  }
  set.add(id);
  clients.set(id, { ws: clients.get(id)!.ws, role });

  const peers: PeerInfo[] = [];
  for (const pid of set) {
    if (pid !== id) {
      const c = clients.get(pid);
      if (c) peers.push({ id: pid, role: c.role });
    }
  }
  return { you: { id, role, polite }, peers };
}

export function leaveRoom(room: string, id: string) {
  const set = rooms.get(room);
  if (!set) return;
  set.delete(id);
  if (set.size === 0) rooms.delete(room);
}

export function leaveAll(id: string) {
  for (const [room, set] of rooms) {
    if (set.has(id)) {
      set.delete(id);
      if (set.size === 0) rooms.delete(room);
    }
  }
}

export function sendTo(id: string, msg: ServerMessage) {
  const c = clients.get(id);
  if (c && c.ws.readyState === c.ws.OPEN) {
    c.ws.send(JSON.stringify(msg));
  }
}

// 在房间内将信令数据转发给除发送者外的其他成员
export function relay(room: string, fromId: string, data: unknown) {
  const set = rooms.get(room);
  if (!set) return;
  for (const pid of set) {
    if (pid !== fromId) {
      sendTo(pid, { type: "signal", from: fromId, data } as ServerMessage);
    }
  }
}

export function notifyPeers(room: string, exceptId: string, msg: ServerMessage) {
  const set = rooms.get(room);
  if (!set) return;
  for (const pid of set) {
    if (pid !== exceptId) sendTo(pid, msg);
  }
}
