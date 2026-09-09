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

class MainActivity : AppCompatActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private val REQUEST_CODE_CAPTURE = 1001
    private val REQUEST_CODE_PERMISSIONS = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val roomInput = findViewById<EditText>(R.id.roomInput)
        val serverInput = findViewById<EditText>(R.id.serverInput)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val status = findViewById<TextView>(R.id.statusText)

        startBtn.setOnClickListener {
            val room = roomInput.text.toString().trim().ifEmpty { "DEMO01" }
            val server =
                serverInput.text.toString().trim().ifEmpty { "ws://10.0.2.2:8080" }
            ScreenCaptureService.signalingUrl = server
            ScreenCaptureService.roomId = room
            status.text = "请求屏幕捕获权限…"
            requestPermissionsThenStart()
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
        if (requestCode == REQUEST_CODE_PERMISSIONS) launchCapture()
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
