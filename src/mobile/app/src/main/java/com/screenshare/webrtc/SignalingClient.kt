package com.screenshare.webrtc

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

// 信令客户端：通过 WebSocket 与信令服务通信，转发 SDP/ICE 与 sensor 给 PeerConnectionClient。
// 作为 [SignalingTransport] 的 WebSocket 实现（开发/兜底用）。
class SignalingClient(
    private val url: String,
    private val room: String,
    private val role: String,
) : WebSocketListener(), SignalingTransport {
    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    override var onJoined: ((selfId: String, polite: Boolean, peerCount: Int) -> Unit)? = null
    override var onPeerJoined: (() -> Unit)? = null
    override var onRemoteDescription: ((SessionDescription) -> Unit)? = null
    override var onRemoteCandidate: ((IceCandidate) -> Unit)? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, this)
    }

    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        send(
            JSONObject()
                .apply {
                    put("type", "join")
                    put("room", room)
                    put("role", role)
                }.toString(),
        )
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        val msg = JSONObject(text)
        when (msg.getString("type")) {
            "joined" -> {
                val you = msg.getJSONObject("you")
                val selfId = you.getString("id")
                val polite = you.getBoolean("polite")
                val peers = msg.getJSONArray("peers").length()
                onJoined?.invoke(selfId, polite, peers)
            }
            "peer-joined" -> onPeerJoined?.invoke()
            "signal" -> {
                val data = msg.getJSONObject("data")
                if (data.has("description")) {
                    val desc = data.getJSONObject("description")
                    val type = desc.getString("type")
                    val sdp = desc.getString("sdp")
                    val sd =
                        SessionDescription(
                            if (type == "offer") {
                                SessionDescription.Type.OFFER
                            } else {
                                SessionDescription.Type.ANSWER
                            },
                            sdp,
                        )
                    onRemoteDescription?.invoke(sd)
                } else if (data.has("candidate")) {
                    val c = data.getJSONObject("candidate")
                    val candidate =
                        IceCandidate(
                            c.optString("sdpMid"),
                            c.optInt("sdpMLineIndex"),
                            c.getString("candidate"),
                        )
                    onRemoteCandidate?.invoke(candidate)
                }
            }
            "error" -> android.util.Log.e("Signaling", msg.optString("message"))
        }
    }

    fun sendSignal(description: SessionDescription) {
        val d =
            JSONObject().apply {
                put("type", description.type.canonicalForm())
                put("sdp", description.description)
            }
        val data = JSONObject().apply { put("description", d) }
        send(envelope("signal", data))
    }

    fun sendSignal(candidate: IceCandidate) {
        val c =
            JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
        val data = JSONObject().apply { put("candidate", c) }
        send(envelope("signal", data))
    }

    // sensor 姿态经 WebSocket 信令通道发送（不再走 WebRTC DataChannel）
    override fun sendSensor(quaternion: FloatArray) {
        val q =
            JSONObject().apply {
                put("x", quaternion[0])
                put("y", quaternion[1])
                put("z", quaternion[2])
                put("w", quaternion[3])
            }
        // 顶层 q/t，与 iroh 路径保持一致
        val msg =
            JSONObject().apply {
                put("type", "sensor")
                put("room", room)
                put("q", q)
                put("t", System.currentTimeMillis())
            }
        send(msg.toString())
    }

    private fun envelope(
        type: String,
        data: JSONObject,
    ): String =
        JSONObject()
            .apply {
                put("type", type)
                put("room", room)
                put("data", data)
            }.toString()

    private fun send(json: String) {
        ws?.send(json)
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        android.util.Log.e("Signaling", "failure", t)
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {}

    fun close() {
        ws?.close(1000, "bye")
    }
}
