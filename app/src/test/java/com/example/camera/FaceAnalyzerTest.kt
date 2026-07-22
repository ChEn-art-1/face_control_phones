package com.example.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FaceMath 纯数学逻辑的单测 — 不需要 Android 环境，JVM 上直接跑
 *
 * 覆盖：
 *   - dist() 的精度和几何关系
 *   - calculateEAR() 睁眼/闭眼阈值
 *   - 边界情况（除零、对称性）
 */
class FaceAnalyzerTest {

    // ================================================================
    // dist()
    // ================================================================

    @Test
    fun `dist 计算 X 轴距离`() {
        assertEquals(5f, FaceMath.dist(0f, 0f, 5f, 0f), 1e-6f)
    }

    @Test
    fun `dist 计算 Y 轴距离`() {
        assertEquals(3f, FaceMath.dist(0f, 0f, 0f, 3f), 1e-6f)
    }

    @Test
    fun `dist 计算 3-4-5 三角形斜边`() {
        assertEquals(5f, FaceMath.dist(0f, 0f, 3f, 4f), 1e-6f)
    }

    @Test
    fun `dist 相同点距离为 0`() {
        assertEquals(0f, FaceMath.dist(1f, 2f, 1f, 2f), 1e-6f)
    }

    @Test
    fun `dist 交换两点结果相同`() {
        val d1 = FaceMath.dist(1f, 5f, 4f, 9f)
        val d2 = FaceMath.dist(4f, 9f, 1f, 5f)
        assertEquals(d1, d2, 1e-6f)
    }

    @Test
    fun `dist 负坐标正确计算`() {
        assertEquals(5f, FaceMath.dist(-1f, -2f, 2f, 2f), 1e-6f)
    }

    // ================================================================
    // calculateEAR()
    // ================================================================

    @Test
    fun `睁眼 EAR 大于闭眼 EAR`() {
        val open = FaceMath.calculateEAR(
            0f, 0f,      // p1
            0f, 0.25f,   // p2
            0f, 0.30f,   // p3
            1f, 0f,      // p4
            0f, 0.35f,   // p5
            0f, 0.30f,   // p6
        )
        val closed = FaceMath.calculateEAR(
            0f, 0f,
            0f, 0.05f,
            0f, 0.06f,
            1f, 0f,
            0f, 0.07f,
            0f, 0.06f,
        )
        assertTrue("睁眼 EAR($open) 应大于闭眼 EAR($closed)", open > closed)
    }

    @Test
    fun `闭眼 EAR 应非常小`() {
        val ear = FaceMath.calculateEAR(
            0f, 0f, 0f, 0.02f, 0f, 0.02f,
            1f, 0f, 0f, 0.03f, 0f, 0.02f,
        )
        assertTrue("闭眼 EAR($ear) 应小于 0.05", ear < 0.05f)
    }

    @Test
    fun `眼角重合时 EAR 为 0 不抛异常`() {
        val ear = FaceMath.calculateEAR(
            0f, 0f, 0f, 0.2f, 0f, 0.3f,
            0f, 0f,     // p4 与 p1 重合
            0f, 0.3f, 0f, 0.2f,
        )
        assertEquals(0f, ear, 0f)
    }

    @Test
    fun `EAR 对称性`() {
        val ear1 = FaceMath.calculateEAR(
            0f, 0f, 1f, 0.2f, 1f, 0.3f,
            2f, 0f, 1f, 0.3f, 1f, 0.2f,
        )
        val ear2 = FaceMath.calculateEAR(
            2f, 0f, 1f, 0.2f, 1f, 0.3f,
            0f, 0f, 1f, 0.3f, 1f, 0.2f,
        )
        assertEquals(ear1, ear2, 1e-6f)
    }
}
