package com.screenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.screenshare.sensor.PostureTracker
import com.screenshare.webrtc.IrohSignalingTransport
import com.screenshare.webrtc.PeerConnectionClient
import com.screenshare.webrtc.PeerConnectionFactoryHolder
import com.screenshare.webrtc.SignalingClient
import com.screenshare.webrtc.SignalingTransport
import kotlin.concurrent.thread

class ScreenCaptureService : Service() {
    companion object {
        var signalingUrl: String = "ws://10.0.2.2:8080"
        var roomId: String = "DEMO01"

        // 设置后改用 iroh 直连信令网桥（需 Rust core .so）；否则回退到 WebSocket 信令
        var irohTicket: String? = null
        private const val NOTIF_ID = 1
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var signalingClient: SignalingTransport
    private lateinit var peerClient: PeerConnectionClient
    private lateinit var postureTracker: PostureTracker

    @Volatile
    private var isStopping = false

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("data")
            }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val factory = PeerConnectionFactoryHolder.factory(applicationContext)

        // 信令客户端（轻量，先在主线程创建以便后续回调绑定）
        signalingClient =
            if (irohTicket != null) {
                IrohSignalingTransport(irohTicket!!, roomId, "caster")
            } else {
                SignalingClient(signalingUrl, roomId, "caster")
            }

        // PeerConnection 初始化涉及 EGL + JNI + 采集器，属于重量级操作，
        // 必须在后台线程执行以避免 ANR（Android 12+ 对前台服务也有 ANR 限制）。
        // 创建完成后再切回主线程绑定回调。
        thread {
            if (isStopping) return@thread

            try {
                val client =
                    PeerConnectionClient(
                        applicationContext,
                        factory,
                        data,
                        object : MediaProjection.Callback() {
                            override fun onStop() {
                                // 系统停止了屏幕投影（如用户从状态栏停止），结束采集
                                stopSelf()
                            }
                        },
                    )

                if (isStopping) {
                    client.close()
                    return@thread
                }

                Handler(Looper.getMainLooper()).post {
                    if (isStopping) {
                        client.close()
                        return@post
                    }

                    peerClient = client

                    peerClient.apply {
                        onLocalDescription = { signalingClient.sendSignal(it) }
                        onLocalCandidate = { signalingClient.sendSignal(it) }
                        onConnectionChange = { /* 可在通知中展示连接状态 */ }
                    }

                    signalingClient.onJoined = { _, polite, peerCount ->
                        peerClient.setPolite(polite)
                        if (peerCount > 0) peerClient.tryOffer()
                    }
                    signalingClient.onPeerJoined = { peerClient.tryOffer() }
                    signalingClient.connect()

                    // 传感器融合 → 经信令传输通道发送姿态四元数
                    postureTracker =
                        PostureTracker(applicationContext) { quaternion ->
                            signalingClient.sendSensor(quaternion)
                        }
                    postureTracker.start()
                }
            } catch (e: Exception) {
                android.util.Log.e("ScreenCaptureService", "PeerConnection init failed", e)
                Handler(Looper.getMainLooper()).post {
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "screen_share_channel"
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Screen Share",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val pi =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, channelId)
            .setContentTitle("屏幕共享中")
            .setContentText("正在向 Web 客户端共享屏幕与姿态")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        isStopping = true
        super.onDestroy()
        // postureTracker / peerClient 可能因后台线程初始化未完成而未赋值
        if (::postureTracker.isInitialized) {
            try {
                postureTracker.stop()
            } catch (_: Exception) {
            }
        }
        if (::signalingClient.isInitialized) {
            try {
                signalingClient.close()
            } catch (_: Exception) {
            }
        }
        if (::peerClient.isInitialized) {
            try {
                peerClient.close()
            } catch (_: Exception) {
            }
        }
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
