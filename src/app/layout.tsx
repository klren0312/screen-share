import "./globals.css";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Screen Share 3D",
  description: "实时屏幕共享与 3D 姿态同步可视化平台",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="zh">
      <body>{children}</body>
    </html>
  );
}
