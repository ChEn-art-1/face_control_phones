package com.example.camera

import kotlin.math.sqrt

/**
 * ============================================================
 * 人脸检测数学工具 — 纯计算逻辑，无任何 Android / MediaPipe 依赖
 *
 * 提取原因：MediaPipe 的 NormalizedLandmark 仅在 Android 环境可用，
 * 纯 JVM 单元测试无法实例化。将核心算法放在这里，方便测试。
 * ============================================================
 */
object FaceMath {

    /**
     * 计算两点之间的 2D 欧氏距离
     * 使用标量乘法替代 pow(2.0)，提升 10~50 倍性能
     */
    fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 计算 Eye Aspect Ratio (EAR)
     *
     * EAR = (dist(v2, v6) + dist(v3, v5)) / (2 × dist(v1, v4))
     *
     * 其中 v1/v4 = 左右眼角，v2-v6、v3-v5 = 上下眼睑对应点
     *
     * @return EAR 值，正常睁眼 ≈ 0.25~0.35，闭眼 ≈ 0.10~0.18
     */
    fun calculateEAR(
        p1x: Float, p1y: Float,
        p2x: Float, p2y: Float,
        p3x: Float, p3y: Float,
        p4x: Float, p4y: Float,
        p5x: Float, p5y: Float,
        p6x: Float, p6y: Float,
    ): Float {
        val v1 = dist(p2x, p2y, p6x, p6y)
        val v2 = dist(p3x, p3y, p5x, p5y)
        val h  = dist(p1x, p1y, p4x, p4y)
        if (h == 0f) return 0f // 防止除零
        return (v1 + v2) / (2f * h)
    }
}
