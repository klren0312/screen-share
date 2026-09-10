package com.screenshare.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.*

// 封装 WebRTC PeerConnection：采集屏幕视频、建立传感器 DataChannel，
// 并采用 "Perfect Negotiation" 模式处理 SDP/ICE 交换。
class PeerConnectionClient(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val captureIntent: Intent,
    private val mediaProjectionCallback: MediaProjection.Callback,
) {
    private var pc: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var capturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    @Volatile private var polite = false
    @Volatile private var makingOffer = false
    @Volatile private var ignoreOffer = false

    var onLocalDescription: ((SessionDescription) -> Unit)? = null
    var onLocalCandidate: ((IceCandidate) -> Unit)? = null
    var onConnectionChange: ((String) -> Unit)? = null

    init {
        val iceServers =
            listOf(
                PeerConnection.IceServer
                    .builder("stun:stun.l.google.com:19302")
                    .createIceServer(),
            )

        pc =
            factory.createPeerConnection(
                iceServers,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(c: IceCandidate?) {
                        c?.let { onLocalCandidate?.invoke(it) }
                    }

                    override fun onIceCandidatesRemoved(p0: Array<IceCandidate?>?) {}

                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        onConnectionChange?.invoke(newState?.name ?: "")
                    }

                    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}

                    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}

                    override fun onIceConnectionReceivingChange(p0: Boolean) {}

                    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}

                    override fun onAddStream(p0: MediaStream?) {}

                    override fun onRemoveStream(p0: MediaStream?) {}

                    override fun onDataChannel(dc: DataChannel?) {}

                    override fun onRenegotiationNeeded() {
                        makeOffer()
                    }

                    override fun onAddTrack(
                        p0: RtpReceiver?,
                        p1: Array<MediaStream?>?,
                    ) {}
                },
            )

        // 屏幕采集
        capturer = ScreenCapturerAndroid(captureIntent, mediaProjectionCallback)
        surfaceTextureHelper =
            SurfaceTextureHelper.create("CaptureThread", EglBase.create().eglBaseContext)
        videoSource = factory.createVideoSource(true)
        capturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(720, 1280, 30)
        val videoTrack = factory.createVideoTrack("SCREEN_TRACK", videoSource!!)
        pc!!.addTrack(videoTrack)
    }

    fun setPolite(p: Boolean) {
        polite = p
    }

    fun onRemoteDescription(desc: SessionDescription) {
        val offerCollision =
            desc.type == SessionDescription.Type.OFFER &&
                (makingOffer || pc!!.signalingState() != PeerConnection.SignalingState.STABLE)
        ignoreOffer = !polite && offerCollision
        if (ignoreOffer) return

        pc!!.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}

                override fun onSetSuccess() {
                    if (desc.type == SessionDescription.Type.OFFER) {
                        pc!!.createAnswer(
                            object : SdpObserver {
                                override fun onCreateSuccess(answer: SessionDescription?) {
                                    pc!!.setLocalDescription(
                                        object : SdpObserver {
                                            override fun onCreateSuccess(p0: SessionDescription?) {}

                                            override fun onSetSuccess() {
                                                answer?.let { onLocalDescription?.invoke(it) }
                                            }

                                            override fun onCreateFailure(p0: String?) {}

                                            override fun onSetFailure(p0: String?) {}
                                        },
                                        answer,
                                    )
                                }

                                override fun onSetSuccess() {}

                                override fun onCreateFailure(p0: String?) {}

                                override fun onSetFailure(p0: String?) {}
                            },
                            MediaConstraints(),
                        )
                    }
                }

                override fun onCreateFailure(p0: String?) {}

                override fun onSetFailure(p0: String?) {}
            },
            desc,
        )
    }

    fun onRemoteCandidate(c: IceCandidate) {
        pc!!.addIceCandidate(c)
    }

    fun makeOffer() {
        if (pc == null) return
        makingOffer = true
        pc!!.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(offer: SessionDescription?) {
                    pc!!.setLocalDescription(
                        object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}

                            override fun onSetSuccess() {
                                offer?.let { onLocalDescription?.invoke(it) }
                            }

                            override fun onCreateFailure(p0: String?) {}

                            override fun onSetFailure(p0: String?) {}
                        },
                        offer,
                    )
                }

                override fun onSetSuccess() {}

                override fun onCreateFailure(p0: String?) {}

                override fun onSetFailure(p0: String?) {}
            },
            MediaConstraints(),
        )
        makingOffer = false
    }

    // 仅在 impolite 端主动发起（避免 glare）
    fun tryOffer() {
        if (!polite) makeOffer()
    }

    fun close() {
        capturer?.stopCapture()
        capturer?.dispose()
        videoSource?.dispose()
        sensorChannel?.dispose()
        pc?.close()
        surfaceTextureHelper?.dispose()
    }
}
