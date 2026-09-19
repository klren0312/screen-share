import { app, BrowserWindow, ipcMain } from "electron";
import { join } from "node:path";
import QRCode from "qrcode";
import { IrohViewer } from "./irohViewer";
import { IPC, type ConnectionState } from "../shared/protocol";

/** 界面初始化载荷：ticket + 二维码 + 节点 id */
export interface InitPayload {
  ticket: string;
  /** data:image/png;base64,... */
  qr: string;
  nodeId: string;
}

let win: BrowserWindow | null = null;
let viewer: IrohViewer | null = null;

/** 缓存最近一次 hello（编码参数），渲染进程后挂载时补发 */
let lastHello: { codec: string; width: number; height: number } | null = null;
let initPayload: InitPayload | null = null;

function send(channel: string, ...args: unknown[]) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, ...args);
}

function createWindow() {
  win = new BrowserWindow({
    width: 1180,
    height: 860,
    backgroundColor: "#0b0f1a",
    title: "Screen Share — 接收端",
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(__dirname, "../preload/index.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  win.on("closed", () => {
    win = null;
  });

  // 窗口可能在 iroh 端点就绪前/后就绪，两个方向都补发一次，避免竞态丢消息
  win.webContents.on("did-finish-load", () => {
    if (initPayload) send("viewer:init", initPayload);
    if (lastHello) send("viewer:hello", lastHello);
  });

  const devUrl = process.env.ELECTRON_RENDERER_URL;
  if (devUrl) {
    void win.loadURL(devUrl);
  } else {
    void win.loadFile(join(__dirname, "../renderer/index.html"));
  }
}

/**
 * 启动 iroh 端点并把事件桥接到渲染进程。
 *
 * 注意：媒体不经过 WebRTC，Electron 渲染进程只负责解码与渲染，
 * 网络侧全部在主进程（可以用 Node 版 iroh）。
 */
async function startViewer() {
  const v = await IrohViewer.create();
  viewer = v;

  const ticket = v.ticket;
  // 二维码内容与 Android MainActivity.onScanned 约定一致：{"t": ticket}
  const qr = await QRCode.toDataURL(JSON.stringify({ t: ticket }), {
    margin: 1,
    width: 320,
    color: { dark: "#0b0f1a", light: "#ffffff" },
  });
  initPayload = { ticket, qr, nodeId: v.nodeId };
  console.log(`[iroh] viewer endpoint ready, ticket length=${ticket.length}`);
  send("viewer:init", initPayload);

  v.on("state", (state: ConnectionState) => {
    if (state === "connected") lastHello = null;
    send(IPC.ConnectionState, state);
  });

  v.on("control", (msg) => {
    if (msg.type === "sensor") send(IPC.Sensor, msg.q);
    else if (msg.type === "hello") {
      lastHello = { codec: msg.codec, width: msg.width, height: msg.height };
      send("viewer:hello", lastHello);
    }
  });

  // SPS/PPS：渲染进程在收到关键帧前需要它来 configure VideoDecoder
  v.on("config", (payload: Uint8Array) => {
    send(IPC.MediaConfig, payload);
  });

  // 每个视频帧一条 IPC：Uint8Array 走结构化克隆，720p30 下约 1.5MB/s，可接受
  v.on("frame", (frame: { data: Uint8Array; flags: number; ptsUs: bigint }) => {
    send(IPC.MediaChunk, {
      data: frame.data,
      flags: frame.flags,
      ptsUs: frame.ptsUs.toString(),
    });
  });
}

app.whenReady().then(async () => {
  createWindow();

  ipcMain.handle("viewer:get-init", () => initPayload);
  ipcMain.handle("viewer:get-hello", () => lastHello);
  ipcMain.on(IPC.RequestKeyframe, () => {
    void viewer?.requestKeyframe();
  });

  try {
    await startViewer();
  } catch (e) {
    console.error("[iroh] 启动失败", e);
    send(IPC.ConnectionState, "closed");
  }

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

app.on("before-quit", () => {
  void viewer?.shutdown();
  viewer = null;
});
