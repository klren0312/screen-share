"use client";

import { useEffect, useRef } from "react";
import * as THREE from "three";
import { useSession } from "../lib/store";
import { DEFAULT_ALIGN } from "../lib/config";

// 设备坐标系(ENU) -> Three.js 世界的默认对齐旋转（绕 X 轴 +90°）。
// 校准会在此基础上叠加当前姿态的逆，得到精确对齐。
const DEFAULT_ALIGN_Q = new THREE.Quaternion(
  DEFAULT_ALIGN[0],
  DEFAULT_ALIGN[1],
  DEFAULT_ALIGN[2],
  DEFAULT_ALIGN[3],
);

// 程序生成的手机 3D 模型：机身 + 屏幕平面（贴 WebRTC 视频纹理），
// 整体姿态随远端传感器四元数同步，并支持「校准」将当前姿态作为基线。
export default function ThreeViewer() {
  const mountRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const stream = useSession((s) => s.stream);
  const base = useSession((s) => s.base);
  const calibrate = useSession((s) => s.calibrate);
  const resetBase = useSession((s) => s.resetBase);

  const stateRef = useRef<{
    renderer?: THREE.WebGLRenderer;
    phone?: THREE.Group;
    alignQuat?: THREE.Quaternion;
  }>({});

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
    stateRef.current.renderer = renderer;

    scene.add(new THREE.AmbientLight(0xffffff, 0.9));
    const dir = new THREE.DirectionalLight(0xffffff, 0.9);
    dir.position.set(2, 4, 3);
    scene.add(dir);

    const grid = new THREE.GridHelper(10, 20, 0x223355, 0x16213a);
    grid.position.y = -1.2;
    scene.add(grid);

    const phone = new THREE.Group();
    const body = new THREE.Mesh(
      new THREE.BoxGeometry(0.72, 1.5, 0.09),
      new THREE.MeshStandardMaterial({
        color: 0x111418,
        metalness: 0.6,
        roughness: 0.4,
      }),
    );
    phone.add(body);

    const screen = new THREE.Mesh(
      new THREE.PlaneGeometry(0.66, 1.4),
      new THREE.MeshBasicMaterial({ color: 0x000000 }),
    );
    screen.position.z = 0.046;
    phone.add(screen);
    stateRef.current.phone = phone;
    scene.add(phone);

    let raf = 0;
    const animate = () => {
      raf = requestAnimationFrame(animate);
      const st = stateRef.current;
      if (st.phone && st.alignQuat) {
        const pose = useSession.getState().pose;
        const pq = new THREE.Quaternion(pose.x, pose.y, pose.z, pose.w);
        const q = st.alignQuat.clone().multiply(pq);
        st.phone.quaternion.slerp(q, 0.3);
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
      renderer.dispose();
      const el = renderer.domElement;
      if (el.parentNode) el.parentNode.removeChild(el);
    };
  }, []);

  // 将远端屏幕流绑定到隐藏 video，并生成视频纹理贴到手机屏幕
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !stream) return;
    video.srcObject = stream;
    video.play().catch(() => {});
    const tex = new THREE.VideoTexture(video);
    tex.colorSpace = THREE.SRGBColorSpace;
    const phone = stateRef.current.phone!;
    const screen = phone.children[1] as THREE.Mesh;
    const mat = screen.material as THREE.MeshBasicMaterial;
    mat.map = tex;
    mat.color.set(0xffffff);
    mat.needsUpdate = true;
  }, [stream]);

  // 对齐旋转 = 默认设备->世界映射 × 校准基线(当前姿态)逆。
  // 校准时 base = 当前姿态，使 modelQuat = DEFAULT_ALIGN（标准朝向）。
  useEffect(() => {
    const baseQ = new THREE.Quaternion(base.x, base.y, base.z, base.w);
    stateRef.current.alignQuat = DEFAULT_ALIGN_Q.clone().multiply(baseQ.invert());
  }, [base]);

  return (
    <div style={{ position: "relative", width: "100%", height: "100%" }}>
      <div ref={mountRef} style={{ width: "100%", height: "100%" }} />
      <video ref={videoRef} style={{ display: "none" }} playsInline muted />
      <div
        style={{
          position: "absolute",
          bottom: 16,
          right: 16,
          display: "flex",
          gap: 8,
        }}
      >
        <button
          onClick={calibrate}
          style={{
            padding: "8px 14px",
            background: "#1f6feb",
            color: "#fff",
            border: "none",
            borderRadius: 6,
            cursor: "pointer",
          }}
        >
          校准姿态
        </button>
        <button
          onClick={resetBase}
          style={{
            padding: "8px 14px",
            background: "transparent",
            color: "#e6edf3",
            border: "1px solid #30363d",
            borderRadius: 6,
            cursor: "pointer",
          }}
        >
          重置标定
        </button>
      </div>
    </div>
  );
}
