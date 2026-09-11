package com.screenshare.webrtc

import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * 基于 iroh 的信令传输实现（[SignalingTransport]）。
 *
 * 通过 [IrohCore] 这个 JNI 门面与 Rust 原生核心通信：Rust 侧用 iroh 端点
 * 直连信令网桥（Node 端 iroh 端点），并以换行分隔的 JSON 文本流收发消息：
 *  - 发送：register / signal / sensor
 *  - 接收（来自网桥转发）：welcome / peer-joined / signal / peer-left
 *
 * 与 WebSocket 实现相比，iroh 侧自动完成 NAT 穿透（relay 兜底），无需配置 STUN/TURN
 * 与自建信令服务。使用前需先用 cargo-ndk 构建 libircore 并放入各 ABI 的 jniLibs 目录。
 */
class IrohSignalingTransport(
    private val ticket: String,
    private val room: String,
    private val role: String,
) : SignalingTransport {
    private var handle: Long = 0

    override var onJoined: ((selfId: String, polite: Boolean, peerCount: Int) -> Unit)? = null
    override var onPeerJoined: (() -> Unit)? = null
    override var onRemoteDescription: ((SessionDescription) -> Unit)? = null
    override var onRemoteCandidate: ((IceCandidate) -> Unit)? = null

    override fun connect() {
        // 触发 IrohCore 类初始化（加载 libircore.so）并建立到网桥的连接
        // 若 native 库缺失或 ABI 不匹配，捕获异常并标记 handle=0，后续发送降级为空操作
        try {
            handle = IrohCore.register(this, ticket, room, role)
        } catch (e: Throwable) {
            android.util.Log.w("IrohSignaling", "iroh native init failed, falling back to no-op", e)
            handle = 0L
        }
    }

    private fun isIrohAvailable(): Boolean = handle != 0L

    /** 由 [IrohCore.onMessage] 回调，解析 JSON 信令并分派给上层 */
    internal fun onRawMessage(json: String) {
        val msg = JSONObject(json)
        when (msg.getString("type")) {
            "welcome" -> {
                val you = msg.getJSONObject("you")
                onJoined?.invoke(
                    you.getString("id"),
                    you.getBoolean("polite"),
                    you.optInt("peerCount", 0),
                )
            }

            "peer-joined" -> {
                onPeerJoined?.invoke()
            }

            "signal" -> {
                val data = msg.getJSONObject("data")
                if (data.has("description")) {
                    val desc = data.getJSONObject("description")
                    val type = desc.getString("type")
                    val sdp = desc.getString("sdp")
                    onRemoteDescription?.invoke(
                        SessionDescription(
                            if (type == "offer") {
                                SessionDescription.Type.OFFER
                            } else {
                                SessionDescription.Type.ANSWER
                            },
                            sdp,
                        ),
                    )
                } else if (data.has("candidate")) {
                    val c = data.getJSONObject("candidate")
                    onRemoteCandidate?.invoke(
                        IceCandidate(
                            c.optString("sdpMid"),
                            c.optInt("sdpMLineIndex"),
                            c.getString("candidate"),
                        ),
                    )
                }
            }

            "peer-left" -> { /* 当前为 1:1，忽略 */ }
        }
    }

    override fun sendSignal(description: SessionDescription) {
        if (!isIrohAvailable()) return
        val d =
            JSONObject().apply {
                put("type", description.type.canonicalForm())
                put("sdp", description.description)
            }
        val data = JSONObject().apply { put("description", d) }
        try {
            IrohCore.send(
                handle,
                JSONObject()
                    .apply {
                        put("type", "signal")
                        put("data", data)
                    }.toString(),
            )
        } catch (e: Throwable) {
            android.util.Log.w("IrohSignaling", "sendSignal failed", e)
        }
    }

    override fun sendSignal(candidate: IceCandidate) {
        if (!isIrohAvailable()) return
        val c =
            JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
        val data = JSONObject().apply { put("candidate", c) }
        try {
            IrohCore.send(
                handle,
                JSONObject()
                    .apply {
                        put("type", "signal")
                        put("data", data)
                    }.toString(),
            )
        } catch (e: Throwable) {
            android.util.Log.w("IrohSignaling", "sendSignal candidate failed", e)
        }
    }

    override fun sendSensor(quaternion: FloatArray) {
        if (!isIrohAvailable()) return
        val q =
            JSONObject().apply {
                put("x", quaternion[0])
                put("y", quaternion[1])
                put("z", quaternion[2])
                put("w", quaternion[3])
            }
        try {
            IrohCore.send(
                handle,
                JSONObject()
                    .apply {
                        put("type", "sensor")
                        put("q", q)
                        put("t", System.currentTimeMillis())
                    }.toString(),
            )
        } catch (e: Throwable) {
            android.util.Log.w("IrohSignaling", "sendSensor failed", e)
        }
    }

    override fun close() {
        if (handle != 0L) IrohCore.close(handle)
        handle = 0
    }
}
