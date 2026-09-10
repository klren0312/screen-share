"use client";

import { useParams } from "next/navigation";
import ThreeViewer from "../../components/ThreeViewer";
import StatusBar from "../../components/StatusBar";
import QrPanel from "../../components/QrPanel";
import { useScreenShare } from "../../lib/useScreenShare";

export default function SessionPage() {
  const params = useParams();
  const roomId = String(params.roomId ?? "");
  const { refreshTicket } = useScreenShare(roomId, "viewer");

  return (
    <main style={{ height: "100%", display: "flex", flexDirection: "column" }}>
      <StatusBar roomId={roomId} />
      <div style={{ flex: 1, minHeight: 0 }}>
        <ThreeViewer />
      </div>
      <QrPanel onRefresh={refreshTicket} />
    </main>
  );
}
