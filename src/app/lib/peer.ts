import type { SignalData } from "./types";

export interface PeerHandlers {
  sendSignal: (data: SignalData) => void;
  onTrack: (stream: MediaStream) => void;
  onDataChannel: (dc: RTCDataChannel) => void;
  onConnectionState: (s: RTCPeerConnectionState) => void;
}

// 基于 "Perfect Negotiation" 模式的 PeerConnection 封装，
// 两端逻辑对称：一方 polite、一方 impolite，由信令服务器分配。
export class Peer {
  pc: RTCPeerConnection;
  private makingOffer = false;
  private ignoreOffer = false;

  constructor(
    public polite: boolean,
    iceServers: RTCIceServer[],
    private h: PeerHandlers,
  ) {
    this.pc = new RTCPeerConnection({ iceServers });

    this.pc.onicecandidate = (e) => {
      if (e.candidate) h.sendSignal({ candidate: e.candidate.toJSON() });
    };
    this.pc.ontrack = (e) => {
      if (e.streams[0]) h.onTrack(e.streams[0]);
    };
    this.pc.ondatachannel = (e) => {
      h.onDataChannel(e.channel);
    };
    this.pc.onnegotiationneeded = async () => {
      await this.makeOffer();
    };
    this.pc.onconnectionstatechange = () => {
      h.onConnectionState(this.pc.connectionState);
    };
  }

  async onSignal(data: SignalData) {
    try {
      if ("description" in data && data.description) {
        const desc = data.description;
        const offerCollision =
          desc.type === "offer" &&
          (this.makingOffer || this.pc.signalingState !== "stable");
        this.ignoreOffer = !this.polite && offerCollision;
        if (this.ignoreOffer) return;

        await this.pc.setRemoteDescription(desc);
        if (desc.type === "offer") {
          await this.pc.setLocalDescription();
          this.h.sendSignal({
            description: this.pc.localDescription!.toJSON(),
          });
        }
      } else if ("candidate" in data && data.candidate) {
        try {
          await this.pc.addIceCandidate(data.candidate);
        } catch (err) {
          if (!this.ignoreOffer) console.error("addIceCandidate failed", err);
        }
      }
    } catch (err) {
      console.error("peer.onSignal error", err);
    }
  }

  async makeOffer() {
    try {
      this.makingOffer = true;
      await this.pc.setLocalDescription();
      this.h.sendSignal({
        description: this.pc.localDescription!.toJSON(),
      });
    } catch (err) {
      console.error("makeOffer error", err);
    } finally {
      this.makingOffer = false;
    }
  }

  addTrack(track: MediaStreamTrack, stream: MediaStream) {
    this.pc.addTrack(track, stream);
  }

  createDataChannel(label: string) {
    return this.pc.createDataChannel(label);
  }

  close() {
    this.pc.close();
  }
}
