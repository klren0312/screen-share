package com.screenshare.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

// 传感器融合姿态追踪：将加速度计、磁力计、陀螺仪统一送入 MadgwickFusion。
// - 陀螺仪（SENSOR_DELAY_FASTEST）提供高频率、低延迟姿态更新
// - 加速度计 + 磁力计经 Madgwick 梯度下降校正陀螺仪积分漂移，提供绝对朝向（含航向）
class PostureTracker(
    private val context: Context,
    private val onQuaternion: (FloatArray) -> Unit,
) : SensorEventListener {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val hasGyro = gyroscope != null

    private val fusion = MadgwickFusion()

    private val gravity = FloatArray(3)
    private val geomag = FloatArray(3)
    private val gyro = FloatArray(3)
    private var haveAccel = false
    private var haveMag = false
    private var lastT = 0L

    fun start() {
        // 传感器可能不存在（部分设备/模拟器没有磁力计或陀螺仪），registerListener 传 null 会 NPE
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (hasGyro) {
            gyroscope?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                event.values.copyInto(gravity)
                haveAccel = true
                if (!hasGyro) maybeUpdate(event.timestamp, fromGyro = false)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                event.values.copyInto(geomag)
                haveMag = true
                if (!hasGyro) maybeUpdate(event.timestamp, fromGyro = false)
            }
            Sensor.TYPE_GYROSCOPE -> {
                event.values.copyInto(gyro)
                maybeUpdate(event.timestamp, fromGyro = true)
            }
        }
    }

    // 优先在陀螺仪事件上推进融合（高频率）；无陀螺仪时退回加速度计事件。
    // dt 由传感器事件时间戳推导，并对异常跳变做钳制。
    private fun maybeUpdate(
        timestamp: Long,
        fromGyro: Boolean,
    ) {
        if (!haveAccel || !haveMag) return
        if (lastT == 0L) {
            lastT = timestamp
            return
        }
        val dt = (timestamp - lastT) / 1_000_000_000f
        lastT = timestamp
        if (dt <= 0f || dt > 0.1f) return // 跳过异常间隔

        val (gx, gy, gz) =
            if (fromGyro) Triple(gyro[0], gyro[1], gyro[2]) else Triple(0f, 0f, 0f)

        fusion.update(
            gx, gy, gz,
            gravity[0], gravity[1], gravity[2],
            geomag[0], geomag[1], geomag[2],
            dt,
        )
        onQuaternion(fusion.getQuaternion())
    }
}
