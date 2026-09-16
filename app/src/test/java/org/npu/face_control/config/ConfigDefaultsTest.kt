package org.npu.face_control.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.npu.face_control.FaceAnalyzer.FaceAction

/**
 * 配置层默认值的纯 JVM 单测：保证「默认配置」精确复现改造前的硬编码行为。
 */
class ConfigDefaultsTest {

    @Test
    fun `默认灵敏度精确复现原硬编码常量`() {
        assertEquals(RuntimeTuning.DEFAULT, SensitivitySettings.DEFAULT.toTuning())
    }

    @Test
    fun `默认竖屏映射等价于改造前行为`() {
        assertEquals(GestureBehavior.TOGGLE_CROSSHAIR, AppConfig.DEFAULT_PORTRAIT[FaceAction.LONG_BLINK])
        assertEquals(GestureBehavior.SWIPE_DOWN, AppConfig.DEFAULT_PORTRAIT[FaceAction.HEAD_DOWN])
        assertEquals(GestureBehavior.SWIPE_UP, AppConfig.DEFAULT_PORTRAIT[FaceAction.LOOK_UP])
        assertEquals(GestureBehavior.SWIPE_LEFT, AppConfig.DEFAULT_PORTRAIT[FaceAction.SHAKE_LEFT])
        assertEquals(GestureBehavior.SWIPE_RIGHT, AppConfig.DEFAULT_PORTRAIT[FaceAction.SHAKE_RIGHT])
        assertEquals(GestureBehavior.PRESS_START, AppConfig.DEFAULT_PORTRAIT[FaceAction.MOUTH_OPEN])
        assertEquals(GestureBehavior.PRESS_END, AppConfig.DEFAULT_PORTRAIT[FaceAction.MOUTH_CLOSE])
        assertEquals(GestureBehavior.NONE, AppConfig.DEFAULT_PORTRAIT[FaceAction.DOUBLE_BLINK])
    }

    @Test
    fun `默认横屏映射与竖屏一致`() {
        assertEquals(AppConfig.DEFAULT_PORTRAIT, AppConfig.DEFAULT_LANDSCAPE)
    }

    @Test
    fun `默认准心映射等价于改造前行为`() {
        assertEquals(GestureBehavior.CLICK, AppConfig.DEFAULT_CROSSHAIR[FaceAction.DOUBLE_BLINK])
        assertEquals(GestureBehavior.TOGGLE_CROSSHAIR, AppConfig.DEFAULT_CROSSHAIR[FaceAction.LONG_BLINK])
        assertEquals(GestureBehavior.NONE, AppConfig.DEFAULT_CROSSHAIR[FaceAction.HEAD_DOWN])
    }

    @Test
    fun `灵敏度两端取值保持阈值不相交`() {
        val lo = SensitivitySettings(
            shakeSensitivity = 0.10f, nodSensitivity = 0.10f,
            crosshairShakeSensitivity = 0.10f, crosshairNodSensitivity = 0.10f
        ).toTuning()
        val hi = SensitivitySettings(
            shakeSensitivity = 0.90f, nodSensitivity = 0.90f,
            crosshairShakeSensitivity = 0.90f, crosshairNodSensitivity = 0.90f
        ).toTuning()
        listOf(lo, hi).forEach { t ->
            assertTrue(t.shakeLeftRatio > t.shakeRightRatio)
            assertTrue(t.nodRatio > t.lookUpRatio)
            assertTrue(t.crosshairShakeLeftRatio > t.crosshairShakeRightRatio)
            assertTrue(t.crosshairNodRatio > t.crosshairLookUpRatio)
        }
    }
}
