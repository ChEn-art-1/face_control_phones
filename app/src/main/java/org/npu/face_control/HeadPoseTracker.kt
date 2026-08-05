package org.npu.face_control

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

/**
 * 头部姿态追踪器 - 优化版
 * 计算头部旋转角度（Yaw偏航角、Pitch俯仰角），映射到 [-1, 1] 范围
 */
class HeadPoseTracker {

    companion object {
        // 死区阈值（避免抖动），调大一点防止误触
        private const val DEAD_ZONE = 0.05f
        // 灵敏度系数（值越大，头部转动时光标移动越快）
        private const val SENSITIVITY = 1.5f
    }

    // 初始面部参考点（用于消除初始偏移）
    private var initYaw = 0f
    private var initPitch = 0f
    private var isInitialized = false

    /**
     * 计算头部姿态，返回归一化的偏移量 [-1, 1]
     * @param landmarks 面部关键点列表
     * @return PoseResult 包含 yaw（左右）和 pitch（上下）
     */
    fun calculatePose(landmarks: List<NormalizedLandmark>): PoseResult {
        // 使用鼻尖(1)、左眼(33)、右眼(263)、下巴(152) 估算姿态
        val nose = landmarks[1]
        val leftEye = landmarks[33]
        val rightEye = landmarks[263]
        val chin = landmarks[152]
        val forehead = landmarks[10]

        // 计算脸部宽度（左右眼距离）
        val faceWidth = distance(leftEye, rightEye)
        if (faceWidth < 0.001f) return PoseResult(0f, 0f)

        // 计算脸部高度
        val faceHeight = distance(forehead, chin)
        if (faceHeight < 0.001f) return PoseResult(0f, 0f)

        // ---- Yaw（左右转头）：鼻尖相对于左右眼中心的水平偏移 ----
        val eyeCenterX = (leftEye.x() + rightEye.x()) / 2f
        val rawYaw = (nose.x() - eyeCenterX) / faceWidth

        // ---- Pitch（点头/仰头）：鼻尖相对于额头和下巴的垂直偏移 ----
        val faceCenterY = (forehead.y() + chin.y()) / 2f
        val rawPitch = (nose.y() - faceCenterY) / faceHeight

        // ---- 初始化参考点（消除初始偏移） ----
        if (!isInitialized) {
            initYaw = rawYaw
            initPitch = rawPitch
            isInitialized = true
            return PoseResult(0f, 0f)
        }

        // 减去初始偏移
        var yaw = rawYaw - initYaw
        var pitch = rawPitch - initPitch

        // 应用死区（小动作忽略）
        yaw = if (kotlin.math.abs(yaw) < DEAD_ZONE) 0f else yaw
        pitch = if (kotlin.math.abs(pitch) < DEAD_ZONE) 0f else pitch

        // 应用灵敏度
        yaw *= SENSITIVITY
        pitch *= SENSITIVITY

        // 限制范围 [-1, 1]
        val clampedYaw = yaw.coerceIn(-1f, 1f)
        val clampedPitch = pitch.coerceIn(-1f, 1f)

        return PoseResult(clampedYaw, clampedPitch)
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 重置初始化状态（退出控制模式时调用）
     */
    fun reset() {
        isInitialized = false
        initYaw = 0f
        initPitch = 0f
    }

    data class PoseResult(
        val yaw: Float,   // 左右 (-1 ~ 1, 负=左转, 正=右转)
        val pitch: Float  // 上下 (-1 ~ 1, 负=仰头, 正=点头)
    )
}