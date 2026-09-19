package com.screenshare.webrtc

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * iroh 原生核心的 JNI 门面。Rust(cargo-ndk 产物 libiroh_core.so) 通过本类与本进程通信：
 *  - [register] 建立到对端 iroh 端点的连接，返回 handle
 *  - [send] 发送一条 JSON 控制消息（姿态 / hello / 结束）
 *  - [sendMedia] 发送一帧已编码媒体（13 字节头 + Annex-B H.264）
 *  - [close] 关闭连接
 *  - [onMessage] 由 Rust 回调，把收到的 JSON 转发给对应的 [MessageSink]
 *
 * 注意：本类依赖各 ABI 的 libircore（构建与放置见 AGENT.md）。未构建时不会
 * 自动加载（仅当使用 iroh 传输、即实例化 [IrohMediaTransport] 时才会触发
 * System.loadLibrary）；WebSocket 传输不受影响。
 */
object IrohCore {
    private const val TAG = "IrohCore"

    /** Rust 侧回调的消息接收者：信令传输与媒体传输都实现它 */
    interface MessageSink {
        fun onRawMessage(json: String)
    }

    private val sinks = ConcurrentHashMap<Long, MessageSink>()

    private external fun connect(
        ticket: String,
        room: String,
        role: String,
    ): Long

    private external fun sendMsg(
        handle: Long,
        message: String,
    )

    /** 发送一帧已编码媒体（13 字节头 + Annex-B H.264），非阻塞投递 */
    private external fun sendMediaFrame(
        handle: Long,
        frame: ByteArray,
    )

    private external fun closeConn(handle: Long)

    fun register(
        sink: MessageSink,
        ticket: String,
        room: String,
        role: String,
    ): Long {
        val handle = connect(ticket, room, role)
        // handle=0 表示连接失败，不登记（否则会留下无法使用的条目）
        if (handle != 0L) sinks[handle] = sink
        return handle
    }

    fun send(
        handle: Long,
        message: String,
    ) = sendMsg(handle, message)

    /**
     * 发送一帧媒体。队列满时 Rust 侧会丢弃该帧，不阻塞调用线程，
     * 因此这里可以直接在编码回调线程上调用。
     */
    fun sendMedia(
        handle: Long,
        frame: ByteArray,
    ) {
        if (handle == 0L) return
        try {
            sendMediaFrame(handle, frame)
        } catch (e: Throwable) {
            Log.w(TAG, "sendMedia failed", e)
        }
    }

    fun close(handle: Long) {
        if (handle != 0L) closeConn(handle)
        sinks.remove(handle)
    }

    /** 由 Rust(JNI) 回调：handle 对应的连接收到一条 JSON 消息 */
    @JvmStatic
    fun onMessage(
        handle: Long,
        json: String,
    ) {
        sinks[handle]?.onRawMessage(json)
    }

    init {
        try {
            System.loadLibrary("iroh_core")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load libiroh_core.so — iroh transport will be unavailable", e)
        }
    }
}
