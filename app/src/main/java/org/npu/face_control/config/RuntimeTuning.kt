package org.npu.face_control.config

/**
 * 运行时阈值快照（由 [SensitivitySettings.toTuning] 从用户设置换算而来）。
 * 默认值必须与改造前 FaceAnalyzer 中的硬编码常量逐位一致，保证默认行为零漂移。
 */
data class RuntimeTuning(
    val earCloseRatio: Float = 0.50f,             // 原 Thresholds.earCloseRatio
    val longBlinkMinMs: Long = 2000L,             // 原 LONG_BLINK_MIN_MS
    val doubleBlinkMaxIntervalMs: Long = 700L,    // 原 DOUBLE_BLINK_MAX_INTERVAL
    val shakeLeftRatio: Float = 0.75f,            // 原 Thresholds.shakeLeftRatio
    val shakeRightRatio: Float = 0.25f,           // 原 Thresholds.shakeRightRatio
    val nodRatio: Float = 0.6f,                   // 原 Thresholds.nodRatio
    val lookUpRatio: Float = 0.35f,               // 原 Thresholds.lookUpRatio
    val mouthOpenMar: Float = 0.5f,               // 原 Thresholds.mouthOpenMar
    val crosshairStepPx: Float = 12f,             // 原 CROSSHAIR_STEP_PX
    val crosshairShakeLeftRatio: Float = 0.60f,   // 原 CROSSHAIR_SHAKE_LEFT_RATIO
    val crosshairShakeRightRatio: Float = 0.40f,  // 原 CROSSHAIR_SHAKE_RIGHT_RATIO
    val crosshairNodRatio: Float = 0.55f,         // 原 CROSSHAIR_NOD_RATIO
    val crosshairLookUpRatio: Float = 0.45f       // 原 CROSSHAIR_LOOK_UP_RATIO
) {
    companion object {
        val DEFAULT = RuntimeTuning()
    }
}
