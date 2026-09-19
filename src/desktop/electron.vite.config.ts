import { defineConfig, externalizeDepsPlugin } from "electron-vite";
import react from "@vitejs/plugin-react";

// 目录约定：src/main、src/preload、src/renderer（electron-vite 默认入口）
export default defineConfig({
  main: {
    // @number0/iroh 是 napi 原生模块，必须保持 external（不能被打进 bundle）
    plugins: [externalizeDepsPlugin()],
  },
  preload: {
    plugins: [externalizeDepsPlugin()],
  },
  renderer: {
    plugins: [react()],
  },
});
