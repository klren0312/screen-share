package com.screenshare

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.screenshare.capture.MediaCodecEncoder
import com.screenshare.sensor.PostureTracker
import com.screenshare.webrtc.IrohMediaTransport
import kotlin.concurrent.thread

/**
 * 屏幕共享前台服务（直连架构）。
 *
 * 链路：MediaProjection → MediaCodec(H.264) → iroh QUIC → Electron 接收端。
 * 媒体不再经过 WebRTC，因此没有 SDP/ICE，也不需要 STUN/TURN 与信令服务器；
 * 房间内的"握手"退化为一次扫码（接收端展示 ticket，本机扫码后直连）。
 */
class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "ScreenCaptureService"

        /** 仅在缺少 ticket 时的兜底房间名（直连架构下不参与路由） */
        var roomId: String = "DEMO01"

        /** 接收端（Electron）二维码里的 iroh ticket */
        var irohTicket: String? = null

        /** 投屏授权 Intent 需与 resultCode 一起才能换取 MediaProjection */
        const val EXTRA_RESULT_CODE = "resultCode"

        private const val NOTIF_ID = 1
    }

    private var transport: IrohMediaTransport? = null
    private var encoder: MediaCodecEncoder? = null
    private var postureTracker: PostureTracker? = null
    private var projection: MediaProjection? = null

    @Volatile
    private var isStopping = false

    override fun onCreate() {
        super.onCreate()
        AppLog.init(applicationContext)
        // 刻意不在 onCreate 前台化：Android 14（targetSdk 34）要求 mediaProjection 类型的前台服务
        // 在提升前台态时进程必须持有有效的投屏授权，否则抛 SecurityException 直接崩进程。
        // 前台化推迟到 onStartCommand 中、确认拿到授权 Intent 之后（见 startForegroundWithType）。
    }

    /**
     * Android 14 起，必须在 MediaProjection 会话建立前让本服务处于 mediaProjection 类型的前台态，
     * 否则 getMediaProjection()/createVirtualDisplay() 会抛 SecurityException。
     */
    private fun startForegroundWithType(): Boolean {
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (e: Throwable) {
            AppLog.log(TAG, "提升为 mediaProjection 前台服务失败", e)
            notifyFailure("无法启动前台服务：${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val data =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("data")
            }
        if (data == null) {
            // 进程被杀后系统重建服务（或外部以空 Intent 启动）时没有投屏授权，
            // 此时绝不能前台化（会抛 SecurityException 崩进程），直接停止即可。
            AppLog.log(TAG, "缺少投屏授权 Intent（flags=$flags），停止服务")
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode =
            intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_OK) ?: Activity.RESULT_OK

        if (!startForegroundWithType()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val ticket = irohTicket
        if (ticket.isNullOrBlank()) {
            AppLog.log(TAG, "缺少 iroh ticket：直连架构必须先扫接收端二维码")
            notifyFailure("请先扫描桌面端二维码（iroh 直连，无需服务器）")
            stopSelf()
            return START_NOT_STICKY
        }

        // 与旧实现的关键区别：这里由服务自己换取 MediaProjection。
        // 之前必须让 ScreenCapturerAndroid 独占换取（Android 14 的授权 Intent 只能用一次），
        // 现在不再使用 ScreenCapturerAndroid，所以由本服务持有并释放。
        val mp =
            try {
                (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                    .getMediaProjection(resultCode, data)
            } catch (e: Throwable) {
                AppLog.log(TAG, "获取 MediaProjection 失败", e)
                notifyFailure("投屏授权失效：${e.message ?: e.javaClass.simpleName}")
                stopSelf()
                return START_NOT_STICKY
            }
        if (mp == null) {
            AppLog.log(TAG, "MediaProjection 为 null（授权未被系统接受）")
            notifyFailure("投屏授权无效，请重新发起共享")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        // Android 14+ 必须注册回调，否则停止投屏时系统会抛异常
        mp.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!isStopping) stopSelf()
                }
            },
            Handler(Looper.getMainLooper()),
        )

        AppLog.log(TAG, "开始共享 room=$roomId ticket=${ticket.take(16)}…（iroh 直连）")
        updateNotification("正在连接接收端…")

        // iroh connect 是同步阻塞调用（加载 .so + 建 QUIC 连接），必须离开主线程
        thread {
            val t =
                IrohMediaTransport(ticket, roomId).also { transport = it }
            t.onConnected = { sessionId ->
                AppLog.log(TAG, "接收端已确认 session=$sessionId")
                Handler(Looper.getMainLooper()).post { updateNotification("已连接接收端，正在推流") }
            }
            t.onKeyframeRequest = {
                // 回调来自 Rust 读线程，转主线程统一串行化
                Handler(Looper.getMainLooper()).post { encoder?.requestKeyframe() }
            }
            t.onError = { message ->
                AppLog.log(TAG, "传输错误：$message")
                Handler(Looper.getMainLooper()).post { notifyFailure("连接失败：$message") }
            }

            t.connect()
            if (isStopping) return@thread
            if (!t.isConnected) {
                Handler(Looper.getMainLooper()).post {
                    notifyFailure("无法连接接收端，请确认桌面端已启动并重新扫码")
                    stopSelf()
                }
                return@thread
            }

            Handler(Looper.getMainLooper()).post {
                if (isStopping) return@post
                startCapture(mp)
                postureTracker =
                    PostureTracker(applicationContext) { quaternion ->
                        transport?.sendSensor(quaternion)
                    }.also { it.start() }
            }
        }

        return START_NOT_STICKY
    }

    private fun startCapture(projection: MediaProjection) {
        val enc =
            MediaCodecEncoder(
                context = applicationContext,
                projection = projection,
                onFrame = { payload, ptsUs, isKeyframe, isConfig ->
                    transport?.sendVideoFrame(payload, ptsUs, isKeyframe, isConfig)
                },
                onStarted = { codec, width, height ->
                    AppLog.log(TAG, "编码参数 codec=$codec ${width}x$height")
                    transport?.sendHello(codec, width, height)
                    Handler(Looper.getMainLooper()).post {
                        updateNotification("推流中 ${width}x$height（房间 $roomId）")
                    }
                },
                onError = { message ->
                    AppLog.log(TAG, "编码错误：$message")
                    Handler(Looper.getMainLooper()).post { notifyFailure(message) }
                },
            )
        encoder = enc
        enc.start()
    }

    private fun buildNotification(
        text: String = "正在向桌面端共享屏幕与姿态",
    ): Notification {
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
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .build()
    }

    /** 更新前台通知文案（无需 adb，下拉通知栏即可看到当前状态） */
    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (e: Throwable) {
            AppLog.log(TAG, "更新通知失败", e)
        }
    }

    /**
     * 失败时单独弹一条高优先级通知，并把原因写进日志。
     * MainActivity 启动即 finish，用户看不到界面上的错误，通知栏是唯一"可见"渠道。
     */
    private fun notifyFailure(message: String) {
        AppLog.log(TAG, "失败：$message")
        try {
            val mgr = getSystemService(NotificationManager::class.java)
            val channelId = "screen_share_error"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mgr.createNotificationChannel(
                    NotificationChannel(channelId, "屏幕共享错误", NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val pi =
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(this, channelId)
                    .setContentTitle("屏幕共享失败")
                    .setContentText(message)
                    .setStyle(
                        NotificationCompat
                            .BigTextStyle()
                            .bigText(message)
                            .setSummaryText("点击打开 App 查看完整日志"),
                    ).setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            mgr.notify(NOTIF_ID + 1, notification)
        } catch (e: Throwable) {
            AppLog.log(TAG, "发送失败通知时出错", e)
        }
    }

    override fun onDestroy() {
        isStopping = true
        super.onDestroy()
        // 这些对象可能因后台线程初始化未完成而仍未赋值
        encoder?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        postureTracker?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        transport?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        projection?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        encoder = null
        postureTracker = null
        transport = null
        projection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
