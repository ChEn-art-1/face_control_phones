package org.npu.face_control

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * 人脸动作识别引擎 — 核心算法模块
 *
 * 检测动作：
 *   LONG_BLINK    → 主动长闭眼（闭眼期间即触发）
 *   DOUBLE_BLINK  → 双眨眼
 *   HEAD_DOWN     → 低头
 *   LOOK_UP       → 抬头
 *   SHAKE_LEFT    → 向左扭头
 *   SHAKE_RIGHT   → 向右扭头
 *   SHAKE_TWICE   → 连续摇头两次（左右左右）
 *   NOD_TWICE     → 连续上下摆头两次（下上下上）
 *   MOUTH_OPEN    → 张嘴
 *   MOUTH_CLOSE   → 闭嘴
 *
 * 所有灵敏度阈值从 [FaceControlConfig] 读取（每帧 @Volatile，实时生效），
 * 实时检测值写入 [LiveMetrics] 供测试页展示。
 */
class FaceAnalyzer(
    context: Context,
    private val onActionDetected: (FaceAction) -> Unit,
    private val onHeadDirection: ((Float, Float) -> Unit)? = null,
    private val onInitFailed: ((Throwable) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceAnalyzer"

        // 短眨眼（生理性）时长上限：低于此值才计为双眨眼候选
        private const val SHORT_BLINK_MAX_MS = 300L
        private const val BLINK_DISABLE_DURATION_MS = 1000L
        private const val FACE_LOST_TIMEOUT_MS = 2000L
        // 连续摆头/点头序列超时（未被单次确认打断时的最长窗口）
        private const val SHAKE_SEQUENCE_TIMEOUT_MS = 2500L
        // 双向阈值（左/右、下/上）之间的最小死区，防止两个阈值交叉导致方向判定失效
        private const val DEAD_ZONE_MIN = 0.05f
        // 横屏时额外旋转角：让送入 MediaPipe 的人脸保持正立（横屏时 rotationDegrees 会让人脸侧躺）。
        // 真机若横屏仍检测不到人脸，尝试改成 -90
        private const val LANDSCAPE_EXTRA_ROTATION_DEG = 90f
        // 方向中心基线学习率与最大偏移：补偿摄像头位置导致的「正对时比例不居中」
        private const val BASELINE_LEARN_RATE = 0.02f
        private const val BASELINE_MAX_OFFSET = 0.25f
        // 眨眼参考基线：睁眼 EAR 归一化目标（横竖屏对齐到该值）
        private const val REFERENCE_EAR = 0.3f
        // 横竖屏切换后的快速校准：帧数（约 2 秒）与快速学习率
        private const val CALIBRATION_FRAMES = 60
        private const val FAST_LEARN_RATE = 0.1f
    }

    private var faceLandmarker: FaceLandmarker? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 眨眼状态
    private var isEyesClosed = false
    private var eyesClosedStartTime = 0L
    private var longBlinkFired = false
    private var lastPhysioBlinkTimestamp = 0L
    private var frameCount = 0L

    @Volatile private var isBlinkControlEnabled = true

    /** 准心模式下置为 false，屏蔽低头/摇头/张嘴等非眨眼动作的派发（双击眨眼仍有效） */
    @Volatile var nonBlinkActionsEnabled = true

    /** 准心模式下置为 false，屏蔽长闭眼点击（双击眨眼仍有效） */
    @Volatile var longBlinkEnabled = true

    /** 设备是否横屏（由服务端传感器更新），用于横屏时把图像转正，保证人脸检测正常 */
    @Volatile var isLandscape = false

    // 睁眼 EAR 基线：趋近当前睁眼 EAR，用于归一化对齐横竖屏
    private var earBaseline = REFERENCE_EAR

    // 摇头/点头方向中心基线：补偿摄像头位置导致的「正对时比例不居中（≠0.5）」
    private var shakeCenterOffset = 0f
    private var nodCenterOffset = 0f
    private var lastLandscape = false
    private var calibratingFrames = 0

    // 其他动作
    private var isMouthOpened = false
    private var lastFaceDetectedTime = 0L

    private val enableBlinkRunnable = Runnable { isBlinkControlEnabled = true }

    // 摇头序列检测（单次滑动延迟确认 + 连续摇头两次）
    private var currentShakeSide = 0
    private val shakeSideEntries = mutableListOf<Int>()
    private var shakeSeqStartTime = 0L
    private val confirmShakeRunnable = Runnable {
        val entries = shakeSideEntries.toList()
        if (entries.size == 1) {
            val dir = entries[0]
            emit(if (dir < 0) FaceAction.SHAKE_LEFT else FaceAction.SHAKE_RIGHT)
        }
        // 多段但不构成连续摇头两次的（如同侧重复），暂不派发
        shakeSideEntries.clear()
        currentShakeSide = 0
        shakeSeqStartTime = 0L
    }

    // 点头序列检测（单次点头/抬头延迟确认 + 连续上下摆头两次）
    private var currentNodSide = 0
    private val nodSideEntries = mutableListOf<Int>()
    private var nodSeqStartTime = 0L
    private val confirmNodRunnable = Runnable {
        val entries = nodSideEntries.toList()
        if (entries.size == 1) {
            val dir = entries[0]
            emit(if (dir > 0) FaceAction.HEAD_DOWN else FaceAction.LOOK_UP)
        }
        // 多段但不构成连续上下摆头两次的，暂不派发
        nodSideEntries.clear()
        currentNodSide = 0
        nodSeqStartTime = 0L
    }

    @Volatile private var isReleased = false

    init {
        try {
            val baseOptions = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ -> processResult(result) }
                .build()
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.i(TAG, "FaceLandmarker 初始化成功")
        } catch (e: Throwable) {
            Log.e(TAG, "FaceLandmarker 初始化失败", e)
            faceLandmarker = null
            onInitFailed?.invoke(e)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val landmarker = faceLandmarker
        if (isReleased || landmarker == null) { image.close(); return }
        try {
            val originalBitmap = image.toBitmap()
            // 竖屏按 rotationDegrees 转正；横屏额外转 LANDSCAPE_EXTRA_ROTATION_DEG，
            // 让送入 MediaPipe 的人脸始终保持正立（否则横屏人脸侧躺、检测失败）
            val baseRotation = image.imageInfo.rotationDegrees
            val extra = if (isLandscape) LANDSCAPE_EXTRA_ROTATION_DEG else 0f
            var total = (baseRotation + extra) % 360f
            if (total < 0f) total += 360f
            val rotatedBitmap = if (total != 0f) {
                val matrix = Matrix().apply { postRotate(total) }
                Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
            } else originalBitmap
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            val timestampMs = image.imageInfo.timestamp / 1_000_000
            landmarker.detectAsync(mpImage, timestampMs)
        } catch (e: Throwable) {
            Log.e(TAG, "analyze frame failed", e)
        } finally { image.close() }
    }

    private fun processResult(result: FaceLandmarkerResult) {
        if (isReleased) return
        val landmarksList = result.faceLandmarks()
        if (landmarksList.isNullOrEmpty()) {
            LiveMetrics.isTracking = false
            if (System.currentTimeMillis() - lastFaceDetectedTime > FACE_LOST_TIMEOUT_MS) {
                resetAllStates()
            }
            return
        }
        lastFaceDetectedTime = System.currentTimeMillis()
        LiveMetrics.isTracking = true
        // 检测到横竖屏切换时，重置方向中心基线、EAR 基线，进入快速校准期
        if (lastLandscape != isLandscape) {
            shakeCenterOffset = 0f
            nodCenterOffset = 0f
            earBaseline = REFERENCE_EAR
            calibratingFrames = CALIBRATION_FRAMES
            lastLandscape = isLandscape
        }
        if (calibratingFrames > 0) calibratingFrames--
        val landmarks = landmarksList[0]
        val cfg = FaceControlConfig.config

        // --- 眨眼检测 ---
        val leftEar = calculateEAR(landmarks, 362, 385, 387, 263, 373, 380)
        val rightEar = calculateEAR(landmarks, 33, 160, 158, 133, 153, 144)
        val avgEar = (leftEar + rightEar) / 2f

        frameCount++
        // 睁眼时基线趋近当前睁眼 EAR（校准期用快学习率，2 秒快速恢复）
        if (!isEyesClosed) {
            val rate = if (calibratingFrames > 0) FAST_LEARN_RATE else BASELINE_LEARN_RATE
            earBaseline += (avgEar - earBaseline) * rate
        }
        // 归一化到参考基线 0.3，使横竖屏睁眼 EAR 对齐；闭眼阈值用固定绝对值
        val normalizedEar = avgEar * (REFERENCE_EAR / earBaseline.coerceAtLeast(0.05f))
        val earCloseThreshold = cfg.blinkEarThreshold
        LiveMetrics.ear = normalizedEar
        LiveMetrics.earThreshold = earCloseThreshold
        handleBlink(normalizedEar < earCloseThreshold)

        if (frameCount % 60L == 0L) {
            Log.d(TAG, "帧${frameCount}: EAR=$avgEar 基线=$earBaseline 归一化=$normalizedEar 闭眼阈值=$earCloseThreshold 闭眼=${normalizedEar < earCloseThreshold}")
        }

        // --- 偏头方向（供准心模式光标移动，输出 -1/0/+1 方向向量）---
        val nose = landmarks[1]
        val rightFaceEdge = landmarks[234]
        val leftFaceEdge = landmarks[454]
        val forehead = landmarks[10]
        val chin = landmarks[152]

        var headDx = 0f  // +1 右, -1 左
        var headDy = 0f  // +1 下, -1 上
        val calibrating = calibratingFrames > 0

        // 摇头（横向）：基础手势用基础阈值，准心方向用准心阈值
        val faceWidth = leftFaceEdge.x() - rightFaceEdge.x()
        if (faceWidth > 0) {
            val ratio = (nose.x() - rightFaceEdge.x()) / faceWidth
            val effRatio = ratio + shakeCenterOffset
            LiveMetrics.shakeRatio = effRatio
            val (shakeLeftTh, shakeRightTh) = normalized(cfg.shakeLeftRatio, cfg.shakeRightRatio)

            if (calibrating) {
                // 快速校准期：强制把中心拉到 0.5，并抑制手势（防止初始偏移误判成持续偏头）
                shakeCenterOffset = (shakeCenterOffset + (0.5f - effRatio) * FAST_LEARN_RATE)
                    .coerceIn(-BASELINE_MAX_OFFSET, BASELINE_MAX_OFFSET)
                handleShake(0)
            } else {
                val shakeDir = when {
                    effRatio > shakeLeftTh -> -1
                    effRatio < shakeRightTh -> 1
                    else -> 0
                }
                handleShake(shakeDir)
                if (shakeDir == 0) {
                    shakeCenterOffset = (shakeCenterOffset + (0.5f - effRatio) * BASELINE_LEARN_RATE)
                        .coerceIn(-BASELINE_MAX_OFFSET, BASELINE_MAX_OFFSET)
                }
                val (crossLeftTh, crossRightTh) = normalized(cfg.crosshairShakeLeftRatio, cfg.crosshairShakeRightRatio)
                if (effRatio > crossLeftTh) {
                    headDx = -1f
                } else if (effRatio < crossRightTh) {
                    headDx = 1f
                }
            }
        }

        // 低头/抬头（纵向）：基础手势用基础阈值，准心方向用准心阈值
        val faceHeight = dist(forehead, chin)
        if (faceHeight > 0) {
            val nodRatio = (nose.y() - forehead.y()) / faceHeight
            val effNod = nodRatio + nodCenterOffset
            LiveMetrics.nodRatio = effNod
            val (nodDownTh, nodUpTh) = normalized(cfg.nodRatio, cfg.lookUpRatio)

            if (calibrating) {
                nodCenterOffset = (nodCenterOffset + (0.5f - effNod) * FAST_LEARN_RATE)
                    .coerceIn(-BASELINE_MAX_OFFSET, BASELINE_MAX_OFFSET)
                handleNod(0)
            } else {
                val nodDir = when {
                    effNod > nodDownTh -> 1
                    effNod < nodUpTh -> -1
                    else -> 0
                }
                handleNod(nodDir)
                if (nodDir == 0) {
                    nodCenterOffset = (nodCenterOffset + (0.5f - effNod) * BASELINE_LEARN_RATE)
                        .coerceIn(-BASELINE_MAX_OFFSET, BASELINE_MAX_OFFSET)
                }
                val (crossDownTh, crossUpTh) = normalized(cfg.crosshairNodRatio, cfg.crosshairLookUpRatio)
                if (effNod > crossDownTh) {
                    headDy = 1f
                } else if (effNod < crossUpTh) {
                    headDy = -1f
                }
            }
        }

        onHeadDirection?.invoke(headDx, headDy)

        // --- 张嘴 ---
        val upperLip = landmarks[13]; val lowerLip = landmarks[14]
        val leftMouth = landmarks[78]; val rightMouth = landmarks[308]
        val mouthHeight = dist(upperLip, lowerLip)
        val mouthWidth = dist(leftMouth, rightMouth)
        if (mouthWidth > 0) {
            val mar = mouthHeight / mouthWidth
            LiveMetrics.mar = mar
            if (mar > cfg.mouthOpenMar) {
                if (!isMouthOpened) { isMouthOpened = true; dispatchNonBlinkAction(FaceAction.MOUTH_OPEN) }
            } else {
                if (isMouthOpened) { isMouthOpened = false; dispatchNonBlinkAction(FaceAction.MOUTH_CLOSE) }
            }
        }
    }

    /** 统一派发动作并记录到实时指标 */
    private fun emit(action: FaceAction) {
        LiveMetrics.lastAction = action.name
        LiveMetrics.lastActionTime = System.currentTimeMillis()
        onActionDetected(action)
    }

    // ============================================================
    // 眨眼状态机 — 闭眼达到阈值立即触发，不等睁眼
    // ============================================================
    private fun handleBlink(eyesClosedNow: Boolean) {
        val now = System.currentTimeMillis()
        val cfg = FaceControlConfig.config

        if (eyesClosedNow) {
            if (!isEyesClosed) {
                isEyesClosed = true
                eyesClosedStartTime = now
                longBlinkFired = false
                Log.d(TAG, "→ 闭眼开始")
            }
            // 闭眼持续中：检查是否达到长闭眼阈值
            if (!longBlinkFired && isBlinkControlEnabled && longBlinkEnabled) {
                val duration = now - eyesClosedStartTime
                if (duration >= cfg.longBlinkMinMs) {
                    longBlinkFired = true
                    Log.i(TAG, "✓ 触发 LONG_BLINK (闭眼${duration}ms)")
                    emit(FaceAction.LONG_BLINK)
                }
            }
        } else {
            if (isEyesClosed) {
                val duration = now - eyesClosedStartTime
                isEyesClosed = false
                Log.d(TAG, "← 睁眼 (持续${duration}ms)")
                if (!isBlinkControlEnabled || longBlinkFired) {
                    lastPhysioBlinkTimestamp = 0L
                } else if (duration < SHORT_BLINK_MAX_MS) {
                    checkDoubleBlink(now)
                } else {
                    // 中等时长眨眼（介于短眨眼与长闭眼之间），重置计数避免误判
                    lastPhysioBlinkTimestamp = 0L
                }
            }
        }
    }

    private fun checkDoubleBlink(now: Long) {
        val cfg = FaceControlConfig.config
        val interval = now - lastPhysioBlinkTimestamp
        if (lastPhysioBlinkTimestamp != 0L &&
            interval in cfg.doubleBlinkMinInterval..cfg.doubleBlinkMaxInterval
        ) {
            emit(FaceAction.DOUBLE_BLINK)
            lastPhysioBlinkTimestamp = 0L
        } else {
            lastPhysioBlinkTimestamp = now
        }
    }

    /**
     * 摇头序列检测：
     * 单次摇头（进入一侧后不再切换）经延迟确认后派发 SHAKE_LEFT/RIGHT；
     * 连续摇头两次（方向交替 4 段，如 左-右-左-右）派发 SHAKE_TWICE。
     */
    private fun handleShake(dir: Int) {
        if (dir == 0) {
            currentShakeSide = 0
            return
        }
        if (dir == currentShakeSide) return
        // 进入新的侧（0→非0 或 -1↔+1）
        currentShakeSide = dir
        shakeSideEntries.add(dir)
        Log.d(TAG, "摇头段 dir=$dir 序列=${shakeSideEntries.joinToString()}")
        val now = System.currentTimeMillis()
        if (shakeSideEntries.size == 1) {
            shakeSeqStartTime = now
        }

        // 超时保护：太久没完成，只保留最后一段重新开始
        if (now - shakeSeqStartTime > SHAKE_SEQUENCE_TIMEOUT_MS) {
            val last = dir
            shakeSideEntries.clear()
            shakeSideEntries.add(last)
            shakeSeqStartTime = now
        }

        // 连续摇头两次 = 最近 4 段方向交替
        if (shakeSideEntries.size >= 4 && isAlternating(shakeSideEntries.takeLast(4))) {
            emit(FaceAction.SHAKE_TWICE)
            shakeSideEntries.clear()
            shakeSeqStartTime = 0L
            mainHandler.removeCallbacks(confirmShakeRunnable)
            return
        }

        // 重置延迟确认定时器：这段时间内没有新切换才确认单次滑动
        mainHandler.removeCallbacks(confirmShakeRunnable)
        mainHandler.postDelayed(confirmShakeRunnable, FaceControlConfig.config.shakeConfirmDelayMs)
    }

    /** 规范化双向阈值：保证高阈值 >= 低阈值 + 最小死区，避免交叉后方向判定失效 */
    private fun normalized(high: Float, low: Float): Pair<Float, Float> {
        val hi = maxOf(high, low + DEAD_ZONE_MIN).coerceAtMost(1f)
        val lo = minOf(low, high - DEAD_ZONE_MIN).coerceAtLeast(0f)
        return hi to lo
    }

    private fun isAlternating(list: List<Int>): Boolean {
        for (i in 1 until list.size) {
            if (list[i] == list[i - 1]) return false
        }
        return true
    }

    /**
     * 点头序列检测：
     * 单次点头/抬头（进入一侧后不再切换）经延迟确认后派发 HEAD_DOWN/LOOK_UP；
     * 连续上下摆头两次（方向交替 4 段，如 下-上-下-上）派发 NOD_TWICE。
     */
    private fun handleNod(dir: Int) {
        if (dir == 0) {
            currentNodSide = 0
            return
        }
        if (dir == currentNodSide) return
        currentNodSide = dir
        nodSideEntries.add(dir)
        Log.d(TAG, "点头段 dir=$dir 序列=${nodSideEntries.joinToString()}")
        val now = System.currentTimeMillis()
        if (nodSideEntries.size == 1) {
            nodSeqStartTime = now
        }

        // 超时保护：太久没完成，只保留最后一段重新开始
        if (now - nodSeqStartTime > SHAKE_SEQUENCE_TIMEOUT_MS) {
            val last = dir
            nodSideEntries.clear()
            nodSideEntries.add(last)
            nodSeqStartTime = now
        }

        // 连续上下摆头两次 = 最近 4 段方向交替
        if (nodSideEntries.size >= 4 && isAlternating(nodSideEntries.takeLast(4))) {
            emit(FaceAction.NOD_TWICE)
            nodSideEntries.clear()
            nodSeqStartTime = 0L
            mainHandler.removeCallbacks(confirmNodRunnable)
            return
        }

        // 重置延迟确认定时器：这段时间内没有新切换才确认单次点头/抬头
        mainHandler.removeCallbacks(confirmNodRunnable)
        mainHandler.postDelayed(confirmNodRunnable, FaceControlConfig.config.shakeConfirmDelayMs)
    }

    private fun dispatchNonBlinkAction(action: FaceAction) {
        if (!nonBlinkActionsEnabled) return
        emit(action)
        disableBlinkTemporarily()
    }

    private fun disableBlinkTemporarily() {
        isBlinkControlEnabled = false
        isEyesClosed = false
        longBlinkFired = false
        lastPhysioBlinkTimestamp = 0L
        mainHandler.removeCallbacks(enableBlinkRunnable)
        mainHandler.postDelayed(enableBlinkRunnable, BLINK_DISABLE_DURATION_MS)
    }

    private fun resetAllStates() {
        isEyesClosed = false
        longBlinkFired = false
        isMouthOpened = false
        lastPhysioBlinkTimestamp = 0L
        // 重置摇头序列与点头序列检测
        shakeSideEntries.clear()
        currentShakeSide = 0
        shakeSeqStartTime = 0L
        mainHandler.removeCallbacks(confirmShakeRunnable)
        nodSideEntries.clear()
        currentNodSide = 0
        nodSeqStartTime = 0L
        mainHandler.removeCallbacks(confirmNodRunnable)
    }

    private fun calculateEAR(l: List<NormalizedLandmark>, p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int): Float =
        FaceMath.calculateEAR(
            l[p1].x(), l[p1].y(), l[p2].x(), l[p2].y(), l[p3].x(), l[p3].y(),
            l[p4].x(), l[p4].y(), l[p5].x(), l[p5].y(), l[p6].x(), l[p6].y()
        )

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        FaceMath.dist(a.x(), a.y(), b.x(), b.y())

    fun close() {
        isReleased = true
        mainHandler.removeCallbacksAndMessages(null)
        try { faceLandmarker?.close() } catch (e: Throwable) { Log.e(TAG, "关闭失败", e) }
        faceLandmarker = null
    }

    enum class FaceAction {
        DOUBLE_BLINK, LONG_BLINK, HEAD_DOWN, LOOK_UP,
        SHAKE_LEFT, SHAKE_RIGHT, SHAKE_TWICE, NOD_TWICE,
        MOUTH_OPEN, MOUTH_CLOSE, BLINK
    }
}
