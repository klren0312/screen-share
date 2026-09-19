import ThreeViewer from "./components/ThreeViewer";
import StatusBar from "./components/StatusBar";
import ConnectPanel from "./components/ConnectPanel";

export default function App() {
  return (
    <main style={{ height: "100%", display: "flex", flexDirection: "column" }}>
      <StatusBar />
      <div style={{ flex: 1, minHeight: 0 }}>
        <ThreeViewer />
      </div>
      <ConnectPanel />
    </main>
  );
}
