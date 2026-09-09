// 运行期配置（来自环境变量，带本地开发默认值）

export const SIGNALING_WS_URL =
  process.env.NEXT_PUBLIC_SIGNALING_WS_URL || "ws://localhost:8080";

export const ICE_SERVERS: RTCIceServer[] = [
  { urls: "stun:stun.l.google.com:19302" },
  // 生产环境可在此追加 TURN 服务器：
  // { urls: "turn:turn.example.com:3478", username: "...", credential: "..." },
];

// 设备坐标系(ENU) -> Three.js 世界的默认对齐旋转，格式 [x, y, z, w]。
// 物理推导为绕 X 轴 +90°（将 ENU 的 Z-Up 映射到 Three 的 Y-Up、屏幕法线到 +Z）。
// 校准时叠加当前姿态的逆，得到精确对齐；即便推导存在符号偏差，校准也能完全吸收。
export const DEFAULT_ALIGN: [number, number, number, number] = [
  0.7071, 0, 0, 0.7071,
];

export const DEFAULT_ROOM_ID_LENGTH = 6;
