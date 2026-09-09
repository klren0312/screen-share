"use client";

import { useParams } from "next/navigation";
import ThreeViewer from "../../components/ThreeViewer";
import StatusBar from "../../components/StatusBar";
import { useScreenShare } from "../../lib/useScreenShare";

export default function SessionPage() {
  const params = useParams();
  const roomId = String(params.roomId ?? "");
  useScreenShare(roomId, "viewer");

  return (
    <main style={{ height: "100%", display: "flex", flexDirection: "column" }}>
      <StatusBar roomId={roomId} />
      <div style={{ flex: 1, minHeight: 0 }}>
        <ThreeViewer />
      </div>
    </main>
  );
}
