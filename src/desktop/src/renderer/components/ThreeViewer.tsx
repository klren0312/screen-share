import { useEffect, useRef } from "react";
import * as THREE from "three";
import { useDesktop } from "../lib/store";
import { H264Decoder } from "../lib/decoder";
import type { Quat } from "../lib/store";

/** 设备坐标系(ENU) -> Three.js 世界的默认对齐旋转：绕 X 轴 +90° */
const DEFAULT_ALIGN_Q = new THREE.Quaternion(0.7071, 0, 0, 0.7071);

/** 屏幕画布初始尺寸（竖屏 9:16），收到视频后按实际帧尺寸调整 */
const INITIAL_W = 720;
const INITIAL_H = 1280;

function drawPlaceholder(
  ctx: CanvasRenderingContext2D,
  canvas: HTMLCanvasElement,
  text: string,
) {
  ctx.fillStyle = "#05070d";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = "#3d4f6b";
  ctx.font = `${Math.round(canvas.width / 16)}px system-ui, sans-serif`;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillText(text, canvas.width / 2, canvas.height / 2);
}

interface ViewerRefs {
  renderer?: THREE.WebGLRenderer;
  phone?: THREE.Group;
  alignQuat?: THREE.Quaternion;
  canvas?: HTMLCanvasElement;
  ctx?: CanvasRenderingContext2D;
  texture?: THREE.CanvasTexture;
  lastBase?: Quat;
  receivedCounter: number;
  decodedCounter: number;
}

/**
 * 手机模型 + 屏幕视频纹理。
 *
 * 与 Web 端不同：视频不再来自 WebRTC 的 MediaStream，而是主进程经 iroh 收到的
 * H.264 帧 —— 渲染进程用 WebCodecs 解码后逐帧画到 canvas，再作为 CanvasTexture
 * 贴到机身屏幕面上。
 */
export default function ThreeViewer() {
  const mountRef = useRef<HTMLDivElement>(null);
  const refs = useRef<ViewerRefs>({ receivedCounter: 0, decodedCounter: 0 });

  // 场景初始化
  useEffect(() => {
    const mount = mountRef.current!;
    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x0b0f1a);

    const camera = new THREE.PerspectiveCamera(
      45,
      mount.clientWidth / Math.max(mount.clientHeight, 1),
      0.1,
      100,
    );
    camera.position.set(0, 0, 4);

    const renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setSize(mount.clientWidth, mount.clientHeight);
    renderer.setPixelRatio(window.devicePixelRatio);
    mount.appendChild(renderer.domElement);
    refs.current.renderer = renderer;

    scene.add(new THREE.AmbientLight(0xffffff, 0.9));
    const dir = new THREE.DirectionalLight(0xffffff, 0.9);
    dir.position.set(2, 4, 3);
    scene.add(dir);

    const grid = new THREE.GridHelper(10, 20, 0x223355, 0x16213a);
    grid.position.y = -1.2;
    scene.add(grid);

    const phone = new THREE.Group();
    const body = new THREE.Mesh(
      new THREE.BoxGeometry(0.86, 1.5, 0.09),
      new THREE.MeshStandardMaterial({
        color: 0x111418,
        metalness: 0.6,
        roughness: 0.4,
      }),
    );
    phone.add(body);

    // 屏幕面：9:16 占满可见区域，避免画面被拉伸
    const canvas = document.createElement("canvas");
    canvas.width = INITIAL_W;
    canvas.height = INITIAL_H;
    const ctx = canvas.getContext("2d")!;
    drawPlaceholder(ctx, canvas, "等待手机连接");
    const texture = new THREE.CanvasTexture(canvas);
    texture.colorSpace = THREE.SRGBColorSpace;

    const screen = new THREE.Mesh(
      new THREE.PlaneGeometry(0.7875, 1.4),
      new THREE.MeshBasicMaterial({ map: texture }),
    );
    screen.position.z = 0.047;
    phone.add(screen);

    refs.current.canvas = canvas;
    refs.current.ctx = ctx;
    refs.current.texture = texture;
    refs.current.phone = phone;
    scene.add(phone);

    let raf = 0;
    const animate = () => {
      raf = requestAnimationFrame(animate);
      const st = refs.current;
      const { pose, base } = useDesktop.getState();

      // 基线变化（校准/重置）时重算对齐旋转
      if (!st.lastBase || !sameQuat(st.lastBase, base)) {
        st.lastBase = base;
        const baseQ = new THREE.Quaternion(base.x, base.y, base.z, base.w);
        st.alignQuat = DEFAULT_ALIGN_Q.clone().multiply(baseQ.invert());
      }

      if (st.phone && st.alignQuat) {
        const pq = new THREE.Quaternion(pose.x, pose.y, pose.z, pose.w);
        st.phone.quaternion.slerp(st.alignQuat.clone().multiply(pq), 0.3);
      }
      renderer.render(scene, camera);
    };
    animate();

    const onResize = () => {
      camera.aspect = mount.clientWidth / Math.max(mount.clientHeight, 1);
      camera.updateProjectionMatrix();
      renderer.setSize(mount.clientWidth, mount.clientHeight);
    };
    window.addEventListener("resize", onResize);

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", onResize);
      texture.dispose();
      renderer.dispose();
      const el = renderer.domElement;
      if (el.parentNode) el.parentNode.removeChild(el);
    };
  }, []);

  // 解码管线 + IPC 接入
  useEffect(() => {
    const api = window.desktop;
    if (!api) return;
    const st = refs.current;
    const store = useDesktop.getState();

    const decoder = new H264Decoder({
      onFrame: (frame) => {
        const { canvas, ctx, texture } = refs.current;
        const w = frame.displayWidth;
        const h = frame.displayHeight;
        if (canvas && ctx && texture) {
          if (canvas.width !== w || canvas.height !== h) {
            canvas.width = w;
            canvas.height = h;
          }
          ctx.drawImage(frame, 0, 0, w, h);
          texture.needsUpdate = true;
        }
        frame.close();
        refs.current.decodedCounter += 1;
        if (canvas) {
          const current = useDesktop.getState().resolution;
          const res = `${w}×${h}`;
          if (current !== res) useDesktop.getState().set({ resolution: res });
        }
      },
      onError: (msg) => useDesktop.getState().set({ lastError: msg }),
      onNeedKeyframe: () => api.requestKeyframe(),
    });

    const applyHello = (h: { codec: string; width: number; height: number } | null) => {
      if (!h) return;
      useDesktop.getState().set({ codec: h.codec, resolution: `${h.width}×${h.height}` });
      decoder.setCodec(h.codec);
    };

    const offs = [
      api.onInit((p) =>
        useDesktop.getState().set({
          endpointReady: true,
          ticket: p.ticket,
          qr: p.qr,
          nodeId: p.nodeId,
        }),
      ),
      api.onHello(applyHello),
      api.onConfig((data) => {
        decoder.setParameterSets(data);
        useDesktop.getState().set({ decoderReady: true });
      }),
      api.onChunk((chunk) => {
        refs.current.receivedCounter += 1;
        decoder.push(chunk);
      }),
      api.onSensor((q) => useDesktop.getState().setPose(q)),
      api.onState((s) => {
        if (s === "connected") {
          // 新会话：解码器要等新的 SPS/PPS 与关键帧
          decoder.reset();
          refs.current.receivedCounter = 0;
          refs.current.decodedCounter = 0;
          useDesktop.getState().set({
            decoderReady: false,
            lastError: null,
            receivedFrames: 0,
            decodedFrames: 0,
            fps: 0,
          });
        }
        useDesktop.getState().set({ connection: s });
      }),
    ];

    // 窗口可能早于主进程就绪，拉取一次当前状态
    void api.getInit().then((p) => {
      if (p)
        useDesktop.getState().set({
          endpointReady: true,
          ticket: p.ticket,
          qr: p.qr,
          nodeId: p.nodeId,
        });
    });
    void api.getHello().then(applyHello);

    let lastDecoded = 0;
    const statsTimer = window.setInterval(() => {
      const decoded = refs.current.decodedCounter;
      useDesktop.getState().set({
        receivedFrames: refs.current.receivedCounter,
        decodedFrames: decoded,
        fps: decoded - lastDecoded,
      });
      lastDecoded = decoded;
    }, 1000);

    store.set({ endpointReady: false });

    return () => {
      window.clearInterval(statsTimer);
      offs.forEach((off) => off());
      decoder.close();
    };
  }, []);

  return (
    <div style={{ position: "relative", width: "100%", height: "100%" }}>
      <div ref={mountRef} style={{ width: "100%", height: "100%" }} />
      <div
        style={{
          position: "absolute",
          bottom: 16,
          right: 16,
          display: "flex",
          gap: 8,
        }}
      >
        <ActionButton primary onClick={() => useDesktop.getState().calibrate()}>
          校准姿态
        </ActionButton>
        <ActionButton onClick={() => useDesktop.getState().resetBase()}>
          重置标定
        </ActionButton>
        <ActionButton onClick={() => window.desktop?.requestKeyframe()}>
          请求关键帧
        </ActionButton>
      </div>
    </div>
  );
}

function ActionButton({
  children,
  onClick,
  primary,
}: {
  children: React.ReactNode;
  onClick: () => void;
  primary?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      style={{
        padding: "8px 14px",
        background: primary ? "#1f6feb" : "transparent",
        color: primary ? "#fff" : "#e6edf3",
        border: primary ? "none" : "1px solid #30363d",
        borderRadius: 6,
        cursor: "pointer",
        font: "inherit",
      }}
    >
      {children}
    </button>
  );
}

function sameQuat(a: Quat, b: Quat): boolean {
  return a.x === b.x && a.y === b.y && a.z === b.z && a.w === b.w;
}
