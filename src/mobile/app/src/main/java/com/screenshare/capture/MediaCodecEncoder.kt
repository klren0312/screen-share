package com.screenshare.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.view.Surface
import com.screenshare.AppLog
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * 屏幕采集 + H.264 硬编码。
 *
 * MediaProjection → VirtualDisplay（直接渲染进 MediaCodec 的 inputSurface）→ 编码器输出
 * Annex-B 码流。与旧实现（ScreenCapturerAndroid 把帧交给 WebRTC 再走 RTP/UDP）不同，
 * 这里拿到的是编码后的 NAL，可直接经 iroh 发送，因此媒体完全不依赖 ICE/STUN/TURN。
 *
 * 线程模型：start() 立刻返回，编解码器在后台线程创建（createEncoderByType 可能耗时），
 * 就绪后回调 [onStarted]；[onFrame] 在编码器线程上被调用，实现里不能做耗时操作。
 */
class MediaCodecEncoder(
    private val context: Context,
    private val projection: MediaProjection,
    private val options: Options = Options(),
    private val onFrame: (payload: ByteArray, ptsUs: Long, isKeyframe: Boolean, isConfig: Boolean) -> Unit,
    private val onStarted: (codec: String, width: Int, height: Int) -> Unit,
    private val onError: (message: String) -> Unit,
) {
    data class Options(
        /** 长边上限，超过则等比缩小（短边自动对齐到 16 的倍数） */
        val maxLongEdge: Int = 1280,
        val bitRate: Int = 4_000_000,
        val frameRate: Int = 30,
        val iFrameIntervalSec: Int = 2,
    )

    @Volatile
    private var running = false

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderThread: Thread? = null

    var width = 0
        private set

    var height = 0
        private set

    fun start() {
        if (running) return
        running = true
        encoderThread =
            thread(name = "MediaCodecEncoder") {
                try {
                    runEncoder()
                } catch (e: Throwable) {
                    running = false
                    AppLog.log(TAG, "编码器运行失败", e)
                    onError("编码器失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
    }

    /** 请求立刻产生一个 IDR（接收端解码器重建或丢包后调用） */
    fun requestKeyframe() {
        val c = codec ?: return
        try {
            c.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
            )
        } catch (e: Throwable) {
            AppLog.log(TAG, "请求关键帧失败", e)
        }
    }

    fun stop() {
        running = false
        try {
            encoderThread?.join(1500)
        } catch (_: InterruptedException) {
        }
        encoderThread = null
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        inputSurface = null
    }

    private fun runEncoder() {
        val metrics = context.resources.displayMetrics
        val realW = metrics.widthPixels
        val realH = metrics.heightPixels
        val longEdge = max(realW, realH)
        val scale =
            if (longEdge > options.maxLongEdge) {
                options.maxLongEdge.toFloat() / longEdge
            } else {
                1f
            }
        width = align16(realW * scale)
        height = align16(realH * scale)

        val format =
            MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, options.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, options.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, options.iFrameIntervalSec)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // 每个关键帧前附上 SPS/PPS：接收端 WebCodecs 以 annexb 模式解码，
                    // 这样即便参数集帧丢过一次也能自愈
                    setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                }
            }

        val c = MediaCodec.createEncoderByType(MIME)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        c.start()
        codec = c
        inputSurface = surface

        virtualDisplay =
            projection.createVirtualDisplay(
                "screenshare-vd",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )

        AppLog.log(TAG, "编码器就绪 ${width}x$height @${options.frameRate} 码率=${options.bitRate}")
        onStarted(DEFAULT_CODEC_STRING, width, height)

        drainLoop(c)
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index =
                try {
                    c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                } catch (e: IllegalStateException) {
                    if (running) AppLog.log(TAG, "dequeueOutputBuffer 失败", e)
                    break
                }

            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // csd-0 = SPS(+PPS)。有的设备走这里，有的走 BUFFER_FLAG_CODEC_CONFIG
                val csd = c.outputFormat.getByteBuffer("csd-0")
                if (csd != null) {
                    val bytes = ByteArray(csd.remaining())
                    csd.get(bytes)
                    emitConfig(bytes)
                }
                continue
            }
            if (index < 0) continue // TRY_AGAIN_LATER

            try {
                val buf = c.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val raw = ByteArray(info.size)
                    buf.get(raw)

                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        emitConfig(raw)
                    } else if (raw.isNotEmpty()) {
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val annexB = if (looksLikeAnnexB(raw)) raw else avccToAnnexB(raw)
                        if (annexB.isNotEmpty()) {
                            onFrame(annexB, info.presentationTimeUs, isKey, false)
                        }
                    }
                }
            } finally {
                try {
                    c.releaseOutputBuffer(index, false)
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    /** 参数集（SPS/PPS）单独成帧下发，接收端用它 configure 解码器 */
    private fun emitConfig(raw: ByteArray) {
        val annexB = if (looksLikeAnnexB(raw)) raw else avccToAnnexB(raw)
        if (annexB.isEmpty()) return
        onFrame(annexB, 0L, true, true)
    }

    private companion object {
        const val TAG = "MediaCodecEncoder"
        const val MIME = "video/avc"
        const val DEQUEUE_TIMEOUT_US = 10_000L

        /** createVideoFormat 默认会协商 Baseline（42E01F）；实际值以 SPS 为准 */
        const val DEFAULT_CODEC_STRING = "avc1.42E01F"

        fun align16(value: Float): Int {
            val v = (value.toInt() / 16) * 16
            return if (v < 16) 16 else v
        }

        /** Annex-B 起始码：00 00 01 或 00 00 00 01 */
        fun looksLikeAnnexB(data: ByteArray): Boolean {
            if (data.size < 4) return false
            val b0 = data[0].toInt()
            val b1 = data[1].toInt()
            val b2 = data[2].toInt()
            val b3 = data[3].toInt()
            return (b0 == 0 && b1 == 0 && b2 == 1) ||
                (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 1)
        }

        /**
         * AVCC（4 字节大端长度前缀）→ Annex-B（起始码）。
         * 部分设备的 MediaCodec 直接输出 AVCC，而 WebCodecs 的 annexb 模式只认起始码。
         */
        fun avccToAnnexB(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size + 16)
            var i = 0
            while (i + 4 <= data.size) {
                val len =
                    ((data[i].toInt() and 0xFF) shl 24) or
                        ((data[i + 1].toInt() and 0xFF) shl 16) or
                        ((data[i + 2].toInt() and 0xFF) shl 8) or
                        (data[i + 3].toInt() and 0xFF)
                i += 4
                if (len <= 0 || i + len > data.size) break
                out.write(0)
                out.write(0)
                out.write(0)
                out.write(1)
                out.write(data, i, len)
                i += len
            }
            // 转换失败时退回原数据，避免整帧丢失
            return if (out.size() == 0) data else out.toByteArray()
        }
    }
}
