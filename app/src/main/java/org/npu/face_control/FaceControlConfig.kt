package org.npu.face_control

import android.content.Context

/**
 * 灵敏度配置管理器。
 *
 * 所有可调参数存于 @Volatile 的 [config] 单例中，FaceAnalyzer / 服务端每帧读取即可实时生效；
 * 同时持久化到 SharedPreferences，设置页修改后调用 [update] 保存。
 */
object FaceControlConfig {

    data class Config(
        // 基础模式 - 眨眼
        var blinkEarThreshold: Float = 0.18f,       // 闭眼 EAR 绝对阈值（EAR 低于该值判闭眼）
        var longBlinkMinMs: Long = 800L,            // 长闭眼最短时长
        var doubleBlinkMinInterval: Long = 80L,     // 双眨眼最小间隔
        var doubleBlinkMaxInterval: Long = 700L,    // 双眨眼最大间隔
        // 基础模式 - 摇头（鼻子横向比例越过阈值触发）
        var shakeLeftRatio: Float = 0.70f,
        var shakeRightRatio: Float = 0.30f,
        // 基础模式 - 低头/抬头
        var nodRatio: Float = 0.55f,
        var lookUpRatio: Float = 0.45f,
        // 基础模式 - 张嘴
        var mouthOpenMar: Float = 0.5f,
        // 准心模式 - 偏头方向阈值
        var crosshairShakeLeftRatio: Float = 0.60f,
        var crosshairShakeRightRatio: Float = 0.40f,
        var crosshairNodRatio: Float = 0.55f,
        var crosshairLookUpRatio: Float = 0.45f,
        // 准心模式 - 光标步长（像素/帧）
        var crosshairStepPx: Float = 12f,
        // 连续摆头/点头的确认延迟
        var shakeConfirmDelayMs: Long = 650L
    )

    @Volatile
    var config: Config = Config()
        private set

    private const val PREFS = "face_control_config"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val c = Config()
        c.blinkEarThreshold = p.getFloat("blinkEarThreshold", c.blinkEarThreshold)
        c.longBlinkMinMs = p.getLong("longBlinkMinMs", c.longBlinkMinMs)
        c.doubleBlinkMinInterval = p.getLong("doubleBlinkMinInterval", c.doubleBlinkMinInterval)
        c.doubleBlinkMaxInterval = p.getLong("doubleBlinkMaxInterval", c.doubleBlinkMaxInterval)
        c.shakeLeftRatio = p.getFloat("shakeLeftRatio", c.shakeLeftRatio)
        c.shakeRightRatio = p.getFloat("shakeRightRatio", c.shakeRightRatio)
        c.nodRatio = p.getFloat("nodRatio", c.nodRatio)
        c.lookUpRatio = p.getFloat("lookUpRatio", c.lookUpRatio)
        c.mouthOpenMar = p.getFloat("mouthOpenMar", c.mouthOpenMar)
        c.crosshairShakeLeftRatio = p.getFloat("crosshairShakeLeftRatio", c.crosshairShakeLeftRatio)
        c.crosshairShakeRightRatio = p.getFloat("crosshairShakeRightRatio", c.crosshairShakeRightRatio)
        c.crosshairNodRatio = p.getFloat("crosshairNodRatio", c.crosshairNodRatio)
        c.crosshairLookUpRatio = p.getFloat("crosshairLookUpRatio", c.crosshairLookUpRatio)
        c.crosshairStepPx = p.getFloat("crosshairStepPx", c.crosshairStepPx)
        c.shakeConfirmDelayMs = p.getLong("shakeConfirmDelayMs", c.shakeConfirmDelayMs)
        config = c
    }

    /** 修改并保存配置。mutate 在 config 副本上改动，之后整体替换并持久化，保证跨线程可见。 */
    fun update(context: Context, mutate: (Config) -> Unit) {
        val new = config.copy()
        mutate(new)
        config = new
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putFloat("blinkEarThreshold", new.blinkEarThreshold)
            .putLong("longBlinkMinMs", new.longBlinkMinMs)
            .putLong("doubleBlinkMinInterval", new.doubleBlinkMinInterval)
            .putLong("doubleBlinkMaxInterval", new.doubleBlinkMaxInterval)
            .putFloat("shakeLeftRatio", new.shakeLeftRatio)
            .putFloat("shakeRightRatio", new.shakeRightRatio)
            .putFloat("nodRatio", new.nodRatio)
            .putFloat("lookUpRatio", new.lookUpRatio)
            .putFloat("mouthOpenMar", new.mouthOpenMar)
            .putFloat("crosshairShakeLeftRatio", new.crosshairShakeLeftRatio)
            .putFloat("crosshairShakeRightRatio", new.crosshairShakeRightRatio)
            .putFloat("crosshairNodRatio", new.crosshairNodRatio)
            .putFloat("crosshairLookUpRatio", new.crosshairLookUpRatio)
            .putFloat("crosshairStepPx", new.crosshairStepPx)
            .putLong("shakeConfirmDelayMs", new.shakeConfirmDelayMs)
            .apply()
    }
}
