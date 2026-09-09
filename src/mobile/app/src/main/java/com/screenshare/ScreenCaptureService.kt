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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.screenshare.sensor.PostureTracker
import com.screenshare.webrtc.PeerConnectionClient
import com.screenshare.webrtc.PeerConnectionFactoryHolder
import com.screenshare.webrtc.SignalingClient

class ScreenCaptureService : Service() {
    companion object {
        var signalingUrl: String = "ws://10.0.2.2:8080"
        var roomId: String = "DEMO01"
        private const val NOTIF_ID = 1
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var signalingClient: SignalingClient
    private lateinit var peerClient: PeerConnectionClient
    private lateinit var postureTracker: PostureTracker

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("data")
            }
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data!!)

        val factory = PeerConnectionFactoryHolder.factory(applicationContext)

        // 信令客户端（先于 PeerConnection，便于回调绑定）
        signalingClient = SignalingClient(signalingUrl, roomId, "caster")

        // PeerConnection：采集屏幕并创建传感器数据通道
        peerClient =
            PeerConnectionClient(
                applicationContext,
                factory,
                data!!,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        // 系统停止了屏幕投影（如用户从状态栏停止），结束采集
                        stopSelf()
                    }
                },
            ).apply {
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

        // 传感器融合 → 通过数据通道发送姿态四元数
        postureTracker =
            PostureTracker(applicationContext) { quaternion ->
                peerClient.sendSensor(quaternion)
            }
        postureTracker.start()

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
        super.onDestroy()
        postureTracker.stop()
        signalingClient.close()
        peerClient.close()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
