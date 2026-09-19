package com.screenshare

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    companion object {
        // 进程内只自动弹出一次崩溃日志，避免每次回到 Activity 都打扰
        private var crashDialogShown = false
    }

    private lateinit var projectionManager: MediaProjectionManager
    private val REQUEST_CODE_CAPTURE = 1001
    private val REQUEST_CODE_PERMISSIONS = 1002
    private val REQUEST_CODE_CAMERA = 1003

    private lateinit var roomInput: EditText
    private lateinit var status: TextView

    // 屏幕捕获回调（替代 deprecated 的 startActivityForResult / onActivityResult）
    private lateinit var captureLauncher: ActivityResultLauncher<Intent>

    // 授权弹窗是系统 Activity：结果回调回来时本 Activity 还没 resume，此刻直接
    // startForegroundService() 会被判为「后台启动前台服务」，Android 12+ 直接抛
    // ForegroundServiceStartNotAllowedException（Android 17 上必现）。
    // 所以先把授权 Intent 存下来，等 onResume（已回到前台）再真正启动服务。
    // resultCode 也要一起带着：MediaProjectionManager.getMediaProjection(resultCode, data)
    // 需要二者配对，直连架构下由 Service 自己换取 MediaProjection。
    private var pendingProjectionData: Intent? = null
    private var pendingProjectionResultCode: Int = RESULT_OK

    // 扫码结果回调（ScanContract 输出 ScanIntentResult）
    private val scanLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents
            if (contents.isNullOrBlank()) {
                status.text = "扫码已取消"
            } else {
                onScanned(contents)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 无 adb 时靠这里：捕获未处理异常并落盘，下次启动可见
        AppLog.installCrashHandler(this)
        AppLog.log("MainActivity", "App 启动（onCreate）")
        AppLog.logDeviceEnv()
        // 原生崩溃没有 Java 堆栈，只能靠系统记录的退出原因/tombstone 还原
        AppLog.logLastExitReason(this)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        roomInput = findViewById(R.id.roomInput)
        status = findViewById(R.id.statusText)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val scanBtn = findViewById<Button>(R.id.scanBtn)
        val logBtn = findViewById<Button>(R.id.logBtn)

        logBtn.setOnClickListener { showLogDialog("运行日志") }

        if ((AppLog.crashCount > 0 || AppLog.lastExitAbnormal) && !crashDialogShown) {
            crashDialogShown = true
            status.text = "上次运行异常退出，日志已保存"
            showLogDialog("上次运行异常退出日志（点「复制」后发我即可）")
        }

        captureLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK && result.data != null) {
                    pendingProjectionData = result.data
                    pendingProjectionResultCode = result.resultCode
                }
            }

        startBtn.setOnClickListener {
            // 直连架构：媒体与姿态全部经 iroh 送到桌面端，必须先扫码拿到 ticket
            if (ScreenCaptureService.irohTicket.isNullOrBlank()) {
                status.text = "请先扫描桌面端二维码（点「扫码连接」）"
                return@setOnClickListener
            }
            ScreenCaptureService.roomId =
                roomInput.text
                    .toString()
                    .trim()
                    .ifEmpty { "DEMO01" }
            AppLog.log("MainActivity", "点按开始共享 room=${ScreenCaptureService.roomId}（iroh 直连）")
            status.text = "请求屏幕捕获权限…"
            requestPermissionsThenStart()
        }

        scanBtn.setOnClickListener { startScan() }
    }

    override fun onResume() {
        super.onResume()
        // 此刻 Activity 已回到前台，启动前台服务才是合法的
        val data = pendingProjectionData ?: return
        pendingProjectionData = null
        startCaptureService(data)
    }

    private fun startCaptureService(data: Intent) {
        val intent =
            Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("data", data)
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, pendingProjectionResultCode)
            }
        try {
            startForegroundService(intent)
            finish()
        } catch (e: Throwable) {
            // 万一仍被判为后台启动，给用户可见反馈，别让异常冒泡把进程崩掉
            AppLog.log("MainActivity", "启动前台服务失败", e)
            status.text = "启动失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_CAMERA,
            )
            return
        }
        scanLauncher.launch(
            ScanOptions()
                .setPrompt("扫描桌面端二维码")
                .setBeepEnabled(true)
                .setOrientationLocked(false),
        )
    }

    /**
     * 解析扫码结果。二维码内容为 JSON：{"t": ticket, "r": room}。
     * 若不是 JSON（例如手输或旧版二维码），则把整串当作 ticket，房间号沿用输入框。
     */
    private fun onScanned(text: String) {
        val trimmed = text.trim()
        // 必须用 var：try / catch 两个分支分别赋值，val 在这里不合法
        var ticket: String?
        var room: String?
        try {
            val json = JSONObject(trimmed)
            ticket = json.optString("t").takeIf { it.isNotBlank() }
            room = json.optString("r").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // 非 JSON（手输或旧版二维码）：整串当作 ticket，房间名沿用输入框
            ticket = trimmed
            room = null
        }

        if (ticket.isNullOrBlank()) {
            AppLog.log("MainActivity", "扫码内容无效：未解析出 ticket，raw=${trimmed.take(80)}")
            status.text = "二维码无效：未包含 ticket"
            return
        }

        AppLog.log(
            "MainActivity",
            "扫码成功 room=${room ?: "(沿用输入框)"} ticket=${ticket.take(16)}…(len=${ticket.length})",
        )
        // 设置 ticket 后，ScreenCaptureService 会改用 iroh 直连信令网桥
        ScreenCaptureService.irohTicket = ticket
        if (!room.isNullOrBlank()) {
            ScreenCaptureService.roomId = room
            roomInput.setText(room)
        }
        status.text = "已扫码（iroh 直连桌面端），请求屏幕捕获权限…"
        requestPermissionsThenStart()
    }

    /** 展示日志弹窗：可复制到剪贴板（无 adb 时用它把堆栈发出来） */
    private fun showLogDialog(title: String) {
        val logText = AppLog.dump()
        val content =
            TextView(this).apply {
                text = logText
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(32, 32, 32, 32)
            }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("复制") { _, _ -> copyLog(logText) }
            .setNeutralButton("清空日志") { _, _ ->
                AppLog.clear()
                status.text = "日志已清空"
            }.setNegativeButton("关闭", null)
            .show()
    }

    private fun copyLog(text: String) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("screen-share-log", text))
            status.text = "日志已复制到剪贴板，可直接粘贴发送"
        } catch (e: Throwable) {
            AppLog.log("MainActivity", "复制日志失败", e)
            status.text = "复制失败：${e.message}"
        }
    }

    private fun requestPermissionsThenStart() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed =
            perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            launchCapture()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_CAMERA -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                if (granted) startScan() else status.text = "需要相机权限才能扫码"
            }

            REQUEST_CODE_PERMISSIONS -> {
                launchCapture()
            }
        }
    }

    private fun launchCapture() {
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
