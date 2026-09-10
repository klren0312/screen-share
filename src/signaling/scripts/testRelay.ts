// 自测：在单个 Node 进程内验证 iroh 中继的端到端链路
//   caster(iroh) --(iroh QUIC)--> 中继 --> viewer(WebSocket)
// 验证：
//   1) caster 注册房间后，viewer 加入能收到 peer-joined
//   2) caster 发送的 sensor 能被 viewer 收到
//   3) viewer 发送的 signal 能被 caster 收到
import { Endpoint, EndpointTicket, presetN0 } from "@number0/iroh";
import { WebSocket } from "ws";
import { IrohRelay } from "../src/irohBridge";
import { startSignalingServer } from "../src/server";

const ALPN = Array.from(new TextEncoder().encode("screen-share/1"));
const ROOM = "TEST-" + Math.random().toString(36).slice(2, 7);
const enc = (s: string) => Array.from(new TextEncoder().encode(s));

function wait(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}

const watchdog = setTimeout(() => {
  console.log("RESULT: TIMEOUT (可能是本机无法访问 n0 relay)");
  process.exit(1);
}, 45000);

async function main() {
  const relay = await IrohRelay.create("0.0.0.0:0");
  console.log("relay ticket:", relay.ticket);
  startSignalingServer(relay, 8080);

  // ---- caster (iroh 端点) ----
  const cb = Endpoint.builder();
  presetN0(cb);
  cb.alpns([ALPN]);
  const casterEp = await cb.bind();
  const addr = EndpointTicket.fromString(relay.ticket).endpointAddr();
  const conn = await casterEp.connect(addr, ALPN);
  const bi = await conn.openBi();
  const cSend = bi.send;
  const cRecv = bi.recv;

  let casterGotPeerJoined = false;
  let casterGotSignal = false;
  let buf = "";
  (async () => {
    while (true) {
      let chunk: number[];
      try {
        chunk = await cRecv.read(4096);
      } catch {
        break;
      }
      if (!chunk || chunk.length === 0) break;
      buf += Buffer.from(chunk).toString("utf8");
      let nl: number;
      while ((nl = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, nl);
        buf = buf.slice(nl + 1);
        if (!line.trim()) continue;
        const m = JSON.parse(line);
        if (m.type === "peer-joined") casterGotPeerJoined = true;
        if (m.type === "signal") casterGotSignal = true;
      }
    }
  })();

  await cSend.writeAll(enc(JSON.stringify({ type: "register", room: ROOM }) + "\n"));

  // ---- viewer (WebSocket) ----
  const ws = new WebSocket("ws://localhost:8080");
  let viewerGotPeerJoined = false;
  let viewerGotSensor = false;

  await new Promise<void>((resolve, reject) => {
    ws.on("open", () => {
      ws.send(JSON.stringify({ type: "join", room: ROOM, role: "viewer" }));
      resolve();
    });
    ws.on("error", reject);
  });

  await new Promise<void>((resolve) => {
    ws.on("message", (raw) => {
      const m = JSON.parse(raw.toString());
      if (m.type === "joined" || m.type === "peer-joined") viewerGotPeerJoined = true;
      if (m.type === "sensor") viewerGotSensor = true;
    });
    // viewer 加入后，caster 发送 sensor；viewer 发送 signal
    wait(300).then(async () => {
      await cSend.writeAll(
        enc(JSON.stringify({ type: "sensor", q: { x: 0, y: 0, z: 0, w: 1 }, t: Date.now() }) + "\n"),
      );
      ws.send(
        JSON.stringify({
          type: "signal",
          room: ROOM,
          data: { description: { type: "offer", sdp: "dummy" } },
        }),
      );
    });
    wait(1500).then(() => resolve());
  });

  const ok = viewerGotPeerJoined && viewerGotSensor && casterGotPeerJoined && casterGotSignal;
  console.log({ viewerGotPeerJoined, viewerGotSensor, casterGotPeerJoined, casterGotSignal });
  console.log(ok ? "RESULT: PASS" : "RESULT: FAIL");

  ws.close();
  await relay.shutdown();
  await casterEp.close();
  clearTimeout(watchdog);
  process.exit(ok ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  clearTimeout(watchdog);
  process.exit(1);
});
