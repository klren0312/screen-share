package com.screenshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private val REQUEST_CODE_CAPTURE = 1001
    private val REQUEST_CODE_PERMISSIONS = 1002
    private val REQUEST_CODE_CAMERA = 1003

    private lateinit var roomInput: EditText
    private lateinit var serverInput: EditText
    private lateinit var status: TextView

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

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        roomInput = findViewById(R.id.roomInput)
        serverInput = findViewById(R.id.serverInput)
        status = findViewById(R.id.statusText)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val scanBtn = findViewById<Button>(R.id.scanBtn)

        startBtn.setOnClickListener {
            val room = roomInput.text.toString().trim().ifEmpty { "DEMO01" }
            val server =
                serverInput.text.toString().trim().ifEmpty { "ws://10.0.2.2:8080" }
            ScreenCaptureService.signalingUrl = server
            ScreenCaptureService.roomId = room
            status.text = "请求屏幕捕获权限…"
            requestPermissionsThenStart()
        }

        scanBtn.setOnClickListener { startScan() }
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
                .setPrompt("扫描 Web 端二维码")
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
        var ticket: String? = null
        var room: String? = null
        try {
            val json = JSONObject(trimmed)
            ticket = json.optString("t").takeIf { it.isNotBlank() }
            room = json.optString("r").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            ticket = trimmed
        }

        if (ticket.isNullOrBlank()) {
            status.text = "二维码无效：未包含 ticket"
            return
        }

        // 设置 ticket 后，ScreenCaptureService 会改用 iroh 直连信令网桥
        ScreenCaptureService.irohTicket = ticket
        if (!room.isNullOrBlank()) {
            ScreenCaptureService.roomId = room
            roomInput.setText(room)
        }
        status.text = "已扫码（iroh 直连），请求屏幕捕获权限…"
        requestPermissionsThenStart()
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
            REQUEST_CODE_PERMISSIONS -> launchCapture()
        }
    }

    private fun launchCapture() {
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_CODE_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CAPTURE && resultCode == RESULT_OK && data != null) {
            val intent =
                Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }
            ContextCompat.startForegroundService(this, intent)
            finish()
        }
    }
}
