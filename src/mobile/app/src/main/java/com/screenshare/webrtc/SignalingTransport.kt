package com.screenshare.webrtc

import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * 信令传输抽象。连接层已从纯 WebSocket 迁移到可插拔传输：
 *  - [WsSignalingTransport]：沿用 WebSocket 信令服务（开发/兜底）。
 *  - [IrohSignalingTransport]：通过 iroh QUIC 直连到信令网桥的 iroh 端点，
 *    由服务端在 iroh 与浏览器 WebSocket 之间中继 SDP/ICE 与 sensor 数据。
 *
 * 说明：sensor 姿态不再走 WebRTC DataChannel，统一经本传输通道发送。
 */
interface SignalingTransport {
    fun connect()
    fun sendSignal(description: SessionDescription)
    fun sendSignal(candidate: IceCandidate)
    fun sendSensor(quaternion: FloatArray)
    fun close()

    var onJoined: ((selfId: String, polite: Boolean, peerCount: Int) -> Unit)?
    var onPeerJoined: (() -> Unit)?
    var onRemoteDescription: ((SessionDescription) -> Unit)?
    var onRemoteCandidate: ((IceCandidate) -> Unit)?
}
