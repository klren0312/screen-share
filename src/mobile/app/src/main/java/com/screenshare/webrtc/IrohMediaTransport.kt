package com.screenshare.webrtc

import com.screenshare.AppLog
import org.json.JSONObject

/**
 * 直连接收端（Electron）的媒体传输：一条 iroh QUIC 连接同时承载
 *  - 控制消息（换行分隔 JSON）：hello / sensor
 *  - 媒体帧：每帧一条 uni stream，13 字节头 + Annex-B H.264
 *
 * 这里不交换 SDP/ICE —— 媒体不走 WebRTC，因此不需要 STUN/TURN，
 * 也不需要中间的信令服务器；ticket 由接收端（桌面端）二维码给出。
 */
class IrohMediaTransport(
    private val ticket: String,
    private val room: String,
) : IrohCore.MessageSink {
    private var handle: Long = 0

    /** 收到接收端 welcome（连接可用） */
    var onConnected: ((sessionId: String) -> Unit)? = null

    /** 接收端请求关键帧 */
    var onKeyframeRequest: (() -> Unit)? = null

    var onError: ((message: String) -> Unit)? = null

    val isConnected: Boolean get() = handle != 0L

    fun connect() {
        try {
            // 打点：原生 connect 同步阻塞（加载 .so + 建 QUIC 连接），调用方需放在后台线程
            AppLog.log("IrohMedia", "调用原生 connect（阻塞）… room=$room")
            handle = IrohCore.register(this, ticket, room, ROLE)
            AppLog.log("IrohMedia", "原生 connect 返回 handle=$handle")
            if (handle == 0L) {
                onError?.invoke("iroh 连接失败：ticket 无效 / 接收端未启动 / ALPN 不匹配")
            }
        } catch (e: Throwable) {
            handle = 0L
            AppLog.log("IrohMedia", "iroh 原生库不可用", e)
            onError?.invoke("iroh 原生库不可用：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 告知接收端编码参数；真正的 codec 以随后的 SPS 为准，这里只是提前给个提示 */
    fun sendHello(
        codec: String,
        width: Int,
        height: Int,
    ) {
        sendJson(
            JSONObject().apply {
                put("type", "hello")
                put("room", room)
                put("codec", codec)
                put("width", width)
                put("height", height)
            },
        )
    }

    fun sendSensor(quaternion: FloatArray) {
        sendJson(
            JSONObject().apply {
                put("type", "sensor")
                put(
                    "q",
                    JSONObject().apply {
                        put("x", quaternion[0])
                        put("y", quaternion[1])
                        put("z", quaternion[2])
                        put("w", quaternion[3])
                    },
                )
                put("t", System.currentTimeMillis())
            },
        )
    }

    /**
     * 发送一帧媒体。帧格式必须与 Desktop 端 shared/protocol.ts 一致：
     * flags(1) | ptsUs(8, BE) | length(4, BE) | payload
     */
    fun sendVideoFrame(
        payload: ByteArray,
        ptsUs: Long,
        isKeyframe: Boolean,
        isConfig: Boolean,
    ) {
        if (handle == 0L) return
        var flags = 0
        if (isConfig) flags = flags or FLAG_CONFIG
        if (isKeyframe) flags = flags or FLAG_KEYFRAME

        val frame = ByteArray(MEDIA_HEADER_SIZE + payload.size)
        frame[0] = flags.toByte()
        writeLongBE(frame, 1, ptsUs)
        writeIntBE(frame, 9, payload.size)
        System.arraycopy(payload, 0, frame, MEDIA_HEADER_SIZE, payload.size)
        IrohCore.sendMedia(handle, frame)
    }

    /** 由 [IrohCore.onMessage] 回调，解析接收端下发的控制消息 */
    override fun onRawMessage(json: String) {
        val msg =
            try {
                JSONObject(json)
            } catch (e: Exception) {
                AppLog.log("IrohMedia", "收到非 JSON 控制消息：${json.take(80)}")
                return
            }
        when (msg.optString("type")) {
            "welcome" -> onConnected?.invoke(msg.optString("sessionId"))
            "request-keyframe" -> onKeyframeRequest?.invoke()
        }
    }

    fun close() {
        if (handle != 0L) IrohCore.close(handle)
        handle = 0
    }

    private fun sendJson(obj: JSONObject) {
        if (handle == 0L) return
        try {
            // Rust 侧按换行切分消息，必须带结尾换行（缺了会把多条消息粘成一条）
            IrohCore.send(handle, obj.toString() + "\n")
        } catch (e: Throwable) {
            AppLog.log("IrohMedia", "sendJson 失败", e)
        }
    }

    companion object {
        private const val ROLE = "caster"

        /** 帧头长度，与 Desktop 端 MEDIA_HEADER_SIZE 一致 */
        const val MEDIA_HEADER_SIZE = 13
        private const val FLAG_CONFIG = 1 shl 0
        private const val FLAG_KEYFRAME = 1 shl 1

        private fun writeLongBE(
            buf: ByteArray,
            offset: Int,
            value: Long,
        ) {
            for (i in 0 until 8) {
                buf[offset + i] = ((value shr (56 - 8 * i)) and 0xFF).toByte()
            }
        }

        private fun writeIntBE(
            buf: ByteArray,
            offset: Int,
            value: Int,
        ) {
            for (i in 0 until 4) {
                buf[offset + i] = ((value shr (24 - 8 * i)) and 0xFF).toByte()
            }
        }
    }
}
