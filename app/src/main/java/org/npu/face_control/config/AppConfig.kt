package org.npu.face_control.config

import org.npu.face_control.FaceAnalyzer.FaceAction
import kotlin.math.roundToInt

/**
 * 9 个用户可调灵敏度旋钮。默认值经 [toTuning] 换算后必须精确等于改造前的硬编码常量。
 *
 * 抽象灵敏度（earClose / shake / nod / crosshairShake / crosshairNod）统一取 [0.10, 0.90]，
 * 默认 0.50（中点），保证任意取值下成对阈值单调且不相交。
 */
data class SensitivitySettings(
    val earCloseSensitivity: Float = 0.50f,        // 闭眼灵敏度 [0.10,0.90]
    val longBlinkMs: Long = 2000L,                 // 长闭眼时长 [500,4000] 步进 100
    val doubleBlinkWindowMs: Long = 700L,          // 双眨眼间隔窗口 [300,1500] 步进 50
    val shakeSensitivity: Float = 0.50f,           // 摇头灵敏度 [0.10,0.90]
    val nodSensitivity: Float = 0.50f,             // 点头灵敏度 [0.10,0.90]
    val mouthOpenMar: Float = 0.50f,               // 张嘴阈值 MAR [0.20,0.90] 步进 0.05
    val crosshairSpeedPx: Float = 12f,             // 准心移动速度 [4,40] 步进 2
    val crosshairShakeSensitivity: Float = 0.50f,  // 准心偏头灵敏度 [0.10,0.90]
    val crosshairNodSensitivity: Float = 0.50f     // 准心点头灵敏度 [0.10,0.90]
) {
    /**
     * 换算为运行时阈值快照。默认值（各 s=0.5）经 r2 取整后精确等于原硬编码常量。
     *
     * 成对阈值（摇头/点头/准心）用「中心 ± 偏差」形式，偏差随灵敏度增大而减小，
     * 且偏差恒为正 → 任意取值下都不会出现左右/上下阈值交叉。
     */
    fun toTuning(): RuntimeTuning {
        val shakeDev = 0.25f * (1.5f - shakeSensitivity)              // 默认 0.5 → 0.25 → 0.75/0.25
        val chShakeDev = 0.10f * (1.5f - crosshairShakeSensitivity)   // 默认 0.5 → 0.10 → 0.60/0.40
        val chNodDev = 0.05f * (1.5f - crosshairNodSensitivity)       // 默认 0.5 → 0.05 → 0.55/0.45
        return RuntimeTuning(
            earCloseRatio = r2(0.30f + 0.40f * earCloseSensitivity),
            longBlinkMinMs = longBlinkMs,
            doubleBlinkMaxIntervalMs = doubleBlinkWindowMs,
            shakeLeftRatio = r2(0.5f + shakeDev),
            shakeRightRatio = r2(0.5f - shakeDev),
            nodRatio = r2(0.70f - 0.20f * nodSensitivity),
            lookUpRatio = r2(0.25f + 0.20f * nodSensitivity),
            mouthOpenMar = mouthOpenMar,
            crosshairStepPx = crosshairSpeedPx,
            crosshairShakeLeftRatio = r2(0.5f + chShakeDev),
            crosshairShakeRightRatio = r2(0.5f - chShakeDev),
            crosshairNodRatio = r2(0.5f + chNodDev),
            crosshairLookUpRatio = r2(0.5f - chNodDev)
        )
    }

    /** 取两位小数，消除浮点误差，使默认值精确等于原常量 */
    private fun r2(v: Float): Float = (v * 100f).roundToInt() / 100f

    companion object {
        val DEFAULT = SensitivitySettings()
    }
}

/**
 * 完整应用配置：自定义模式开关 + 灵敏度 + 三张手势映射表（竖屏 / 横屏 / 准心）。
 * 默认值精确复现改造前的行为。
 */
data class AppConfig(
    val customModeEnabled: Boolean = false,
    val sensitivity: SensitivitySettings = SensitivitySettings.DEFAULT,
    val portraitMap: Map<FaceAction, GestureBehavior> = DEFAULT_PORTRAIT,
    val landscapeMap: Map<FaceAction, GestureBehavior> = DEFAULT_LANDSCAPE,
    val crosshairMap: Map<FaceAction, GestureBehavior> = DEFAULT_CROSSHAIR
) {
    companion object {
        /** 竖屏默认映射 —— 等价于改造前的 handlePortraitMode */
        val DEFAULT_PORTRAIT: Map<FaceAction, GestureBehavior> = mapOf(
            FaceAction.DOUBLE_BLINK to GestureBehavior.NONE,
            FaceAction.LONG_BLINK to GestureBehavior.TOGGLE_CROSSHAIR,
            FaceAction.HEAD_DOWN to GestureBehavior.SWIPE_DOWN,
            FaceAction.LOOK_UP to GestureBehavior.SWIPE_UP,
            FaceAction.SHAKE_LEFT to GestureBehavior.SWIPE_LEFT,
            FaceAction.SHAKE_RIGHT to GestureBehavior.SWIPE_RIGHT,
            FaceAction.MOUTH_OPEN to GestureBehavior.PRESS_START,
            FaceAction.MOUTH_CLOSE to GestureBehavior.PRESS_END,
            FaceAction.BLINK to GestureBehavior.NONE
        )

        /** 横屏默认映射 —— 与竖屏相同（改造前两个函数逐行一致） */
        val DEFAULT_LANDSCAPE: Map<FaceAction, GestureBehavior> = DEFAULT_PORTRAIT

        /** 准心模式默认映射 —— 等价于改造前的准心分支 */
        val DEFAULT_CROSSHAIR: Map<FaceAction, GestureBehavior> = mapOf(
            FaceAction.DOUBLE_BLINK to GestureBehavior.CLICK,
            FaceAction.LONG_BLINK to GestureBehavior.TOGGLE_CROSSHAIR,
            FaceAction.HEAD_DOWN to GestureBehavior.NONE,
            FaceAction.LOOK_UP to GestureBehavior.NONE,
            FaceAction.SHAKE_LEFT to GestureBehavior.NONE,
            FaceAction.SHAKE_RIGHT to GestureBehavior.NONE,
            FaceAction.MOUTH_OPEN to GestureBehavior.NONE,
            FaceAction.MOUTH_CLOSE to GestureBehavior.NONE,
            FaceAction.BLINK to GestureBehavior.NONE
        )

        /** 完整默认配置（依赖上面的默认映射表，必须声明在它们之后，否则初始化时会拿到 null） */
        val DEFAULT = AppConfig()
    }
}
