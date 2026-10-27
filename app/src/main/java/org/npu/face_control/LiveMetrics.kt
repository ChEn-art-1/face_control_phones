package org.npu.face_control

/**
 * 实时检测指标（供测试页展示）。
 * FaceAnalyzer 每帧更新这些 @Volatile 值，测试页轮询读取并刷新 UI。
 */
object LiveMetrics {
    /** 是否检测到人脸 */
    @Volatile var isTracking: Boolean = false

    /** 当前 EAR（眼径比） */
    @Volatile var ear: Float = 0f

    /** 当前闭眼阈值（基线 × 比例） */
    @Volatile var earThreshold: Float = 0f

    /** 鼻子横向比例（0~1，越小越靠右，越大越靠左） */
    @Volatile var shakeRatio: Float = 0.5f

    /** 鼻子纵向比例（0~1，越小越靠上，越大越靠下） */
    @Volatile var nodRatio: Float = 0.5f

    /** 当前 MAR（嘴径比） */
    @Volatile var mar: Float = 0f

    /** 最近触发的动作名 */
    @Volatile var lastAction: String = "—"

    /** 最近触发时间（SystemClock.uptimeMillis） */
    @Volatile var lastActionTime: Long = 0L
}
