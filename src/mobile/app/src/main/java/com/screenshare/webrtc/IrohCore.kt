package com.screenshare.webrtc

import java.util.concurrent.ConcurrentHashMap

/**
 * iroh 原生核心的 JNI 门面。Rust(cargo-ndk 产物 libircore.so) 通过本类与本进程通信：
 *  - [register] 建立到信令网桥 iroh 端点的连接，返回 handle
 *  - [send] 发送一条 JSON 信令/姿态消息
 *  - [close] 关闭连接
 *  - [onMessage] 由 Rust 回调，把收到的 JSON 转发给对应的 [IrohSignalingTransport]
 *
 * 注意：本类依赖各 ABI 的 libircore（构建与放置见 AGENT.md）。未构建时不会
 * 自动加载（仅当使用 iroh 传输、即实例化 [IrohSignalingTransport] 时才会触发
 * System.loadLibrary）；默认 WebSocket 传输不受影响。
 */
object IrohCore {
    private val transports = ConcurrentHashMap<Long, IrohSignalingTransport>()

    private external fun connect(ticket: String, room: String, role: String): Long
    private external fun sendMsg(handle: Long, message: String)
    private external fun closeConn(handle: Long)

    fun register(transport: IrohSignalingTransport, ticket: String, room: String, role: String): Long {
        val handle = connect(ticket, room, role)
        transports[handle] = transport
        return handle
    }

    fun send(handle: Long, message: String) = sendMsg(handle, message)

    fun close(handle: Long) {
        if (handle != 0L) closeConn(handle)
        transports.remove(handle)
    }

    /** 由 Rust(JNI) 回调：handle 对应的连接收到一条 JSON 消息 */
    @JvmStatic
    fun onMessage(handle: Long, json: String) {
        transports[handle]?.onRawMessage(json)
    }

    init {
        System.loadLibrary("iroh_core")
    }
}
