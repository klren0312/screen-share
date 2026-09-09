package com.screenshare.sensor

import kotlin.math.sqrt

// Madgwick AHRS 姿态融合：基于梯度下降，同时使用加速度计、陀螺仪与磁力计。
// 输出设备坐标系姿态四元数 [x, y, z, w]，与 Web 端校准兼容。
//
// 算法：Madgwick S. (2010) "An efficient orientation filter for inertial and
//       inertial/magnetic sensor arrays"。以梯度下降方向 ∇f = Jᵀf 修正陀螺仪积分，
// 既保留陀螺仪低延迟特性，又用重力/地磁将长期漂移「拉回」绝对朝向。
//
// 内部四元数采用 (q0=w, q1=x, q2=y, q3=z)；getQuaternion() 返回 [x,y,z,w]。
//
// 自适应增益 β：根据「动态加速度」大小在 [betaMotion, betaStatic] 间调节。
//   - 静止时：加速度计≈纯重力，参考可信，β 取较大值（betaStatic）让重力/地磁更快消除陀螺仪漂移；
//   - 运动/晃动时：线性加速度污染加速度计，β 自动减小（趋于 betaMotion）以更信任陀螺仪惯导，
//     抑制动态加速度造成的姿态抖动。
class MadgwickFusion(
    private val betaStatic: Float = 0.2f, // 静止时增益（信任加速度计/磁力计参考）
    private val betaMotion: Float = 0.04f, // 运动时增益（信任陀螺仪惯导）
    private val dynAccelRef: Float = 0.3f, // 动态加速度参考幅度（ζ 达到此值即视为强运动）
    private val betaSmoothing: Float = 0.1f, // β 平滑系数（每步 EMA）
) {
    private var q0 = 1f // w
    private var q1 = 0f // x
    private var q2 = 0f // y
    private var q3 = 0f // z

    private var beta = betaStatic // 当前（平滑后）增益

    // 返回当前增益，便于调试/遥测（自适应情况下会随运动状态变化）
    fun getBeta(): Float = beta

    fun update(
        gx: Float,
        gy: Float,
        gz: Float, // 陀螺仪角速度 rad/s（体坐标系）
        ax: Float,
        ay: Float,
        az: Float, // 加速度计 m/s^2（方向即可）
        mx: Float,
        my: Float,
        mz: Float, // 磁力计（任意单位，仅方向）
        dt: Float, // 采样周期（秒）
    ) {
        if (dt <= 0f) return

        // 1) 陀螺仪推导的四元数变化率
        val qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
        val qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
        val qDot2 = 0.5f * (-q0 * gy + q1 * gz - q3 * gx)
        val qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

        // 2) 归一化加速度计
        var recip = invSqrt(ax * ax + ay * ay + az * az)
        if (recip == 0f) return
        val axn = ax * recip
        val ayn = ay * recip
        val azn = az * recip

        // 3) 归一化磁力计
        recip = invSqrt(mx * mx + my * my + mz * mz)
        if (recip == 0f) return
        val mxn = mx * recip
        val myn = my * recip
        val mzn = mz * recip

        // 4) 预计算四元数乘积，避免重复算术
        val q0q0 = q0 * q0
        val q0q1 = q0 * q1
        val q0q2 = q0 * q2
        val q0q3 = q0 * q3
        val q1q1 = q1 * q1
        val q1q2 = q1 * q2
        val q1q3 = q1 * q3
        val q2q2 = q2 * q2
        val q2q3 = q2 * q3
        val q3q3 = q3 * q3

        // 4.5) 自适应增益 β：估计动态加速度幅度 ζ = |a_测量 − a_重力预测|
        // 当前姿态下由四元数预测的重力方向（单位向量）
        val agx = 2f * (q1q3 - q0q2)
        val agy = 2f * (q0q1 + q2q3)
        val agz = 2f * (q0q0 - q1q1 - q2q2 + q3q3)
        val zeta = sqrt((axn - agx) * (axn - agx) + (ayn - agy) * (ayn - agy) + (azn - agz) * (azn - agz))
        val targetBeta =
            betaStatic - (betaStatic - betaMotion) * (zeta / dynAccelRef).coerceIn(0f, 1f)
        beta += (targetBeta - beta) * betaSmoothing

        // 5) 地球坐标系下磁场的水平分量，求参考场 (bx, 0, bz)
        val hx = 2f * mxn * (0.5f - q2q2 - q3q3) + 2f * myn * (q1q2 - q0q3) + 2f * mzn * (q1q3 + q0q2)
        val hy = 2f * mxn * (q1q2 + q0q3) + 2f * myn * (0.5f - q1q1 - q3q3) + 2f * mzn * (q2q3 - q0q1)
        val bz = 2f * mxn * (q1q3 - q0q2) + 2f * myn * (q2q3 + q0q1) + 2f * mzn * (0.5f - q1q1 - q2q2)
        val bx = sqrt(hx * hx + hy * hy)

        // 6) 目标函数 f = 预测方向 - 测量方向
        val f1 = 2f * (q1q3 - q0q2) - axn
        val f2 = 2f * (q0q1 + q2q3) - ayn
        val f3 = 2f * (0.5f - q1q1 - q2q2) - azn
        val f4 = bx * (1f - 2f * (q2q2 + q3q3)) + bz * (2f * (q1q3 + q0q2)) - mxn
        val f5 = bx * (2f * (q1q2 + q0q3)) + bz * (2f * (q2q3 - q0q1)) - myn
        val f6 = bx * (2f * (q1q3 - q0q2)) + bz * (1f - 2f * (q1q1 + q2q2)) - mzn

        // 7) 梯度 ∇f = Jᵀ f（对 f1..f6 关于 q0..q3 求偏导后加权）
        val s0 = (-2f * q2) * f1 + (2f * q1) * f2 +
            (2f * bz * q2) * f4 +
            (2f * bx * q3 - 2f * bz * q1) * f5 +
            (-2f * bx * q2 - 4f * bz * q1) * f6
        val s1 = (2f * q3) * f1 + (2f * q0) * f2 + (-4f * q1) * f3 +
            (2f * bz * q3) * f4 +
            (2f * bx * q2 - 2f * bz * q0) * f5 +
            (2f * bx * q3 - 4f * bz * q1) * f6
        val s2 = (-2f * q0) * f1 + (2f * q3) * f2 + (-4f * q2) * f3 +
            (-4f * bx * q2 + 2f * bz * q0) * f4 +
            (2f * bx * q1 + 2f * bz * q3) * f5 +
            (-2f * bx * q0 - 4f * bz * q2) * f6
        val s3 = (2f * q1) * f1 + (2f * q2) * f2 +
            (-4f * bx * q3 + 2f * bz * q1) * f4 +
            (2f * bx * q0 + 2f * bz * q2) * f5 +
            (2f * bx * q1) * f6

        // 8) 归一化梯度
        recip = invSqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
        if (recip == 0f) return
        val s0n = s0 * recip
        val s1n = s1 * recip
        val s2n = s2 * recip
        val s3n = s3 * recip

        // 9) 梯度下降修正：qDot -= beta * ∇f
        val nqDot0 = qDot0 - beta * s0n
        val nqDot1 = qDot1 - beta * s1n
        val nqDot2 = qDot2 - beta * s2n
        val nqDot3 = qDot3 - beta * s3n

        // 10) 积分并归一化
        q0 += nqDot0 * dt
        q1 += nqDot1 * dt
        q2 += nqDot2 * dt
        q3 += nqDot3 * dt

        recip = invSqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        q0 *= recip
        q1 *= recip
        q2 *= recip
        q3 *= recip
    }

    // 返回 [x, y, z, w]
    fun getQuaternion(): FloatArray = floatArrayOf(q1, q2, q3, q0)

    private fun invSqrt(x: Float): Float {
        if (x <= 0f) return 0f
        return 1f / sqrt(x)
    }
}
