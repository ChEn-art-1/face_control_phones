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
 *   LONG_BLINK    → 主动长闭眼（>= 800ms，闭眼期间即触发）
 *   DOUBLE_BLINK  → 双眨眼
 *   HEAD_DOWN     → 低头
 *   LOOK_UP       → 抬头
 *   SHAKE_LEFT    → 向左扭头
 *   SHAKE_RIGHT   → 向右扭头
 *   MOUTH_OPEN    → 张嘴
 *   MOUTH_CLOSE   → 闭嘴
 */
class FaceAnalyzer(
    context: Context,
    private val onActionDetected: (FaceAction) -> Unit,
    private val onHeadDirection: ((Float, Float) -> Unit)? = null,
    private val onInitFailed: ((Throwable) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceAnalyzer"

        // 长闭眼时长阈值：闭眼超过该值触发 LONG_BLINK（现用于“进入/退出准心模式”的开关）
        private const val LONG_BLINK_MIN_MS = 2000L
        // 短眨眼（生理性）时长上限：低于此值才计为双眨眼候选
        private const val SHORT_BLINK_MAX_MS = 300L
        // 双眨眼两段间隔窗口（放宽以提高灵敏度）
        private const val DOUBLE_BLINK_MIN_INTERVAL = 80L
        private const val DOUBLE_BLINK_MAX_INTERVAL = 700L
        private const val BLINK_DISABLE_DURATION_MS = 1000L
        private const val SHAKE_LOCK_DURATION_MS = 1000L
        private const val FACE_LOST_TIMEOUT_MS = 2000L
        // 自适应 EAR 基数采集：每 N 帧统计一次平均值
        private const val EAR_BASELINE_INTERVAL = 120

        // 准心模式偏头方向阈值：比基础手势阈值更小（死区更窄），小范围偏头即响应
        private const val CROSSHAIR_SHAKE_LEFT_RATIO = 0.60f
        private const val CROSSHAIR_SHAKE_RIGHT_RATIO = 0.40f
        private const val CROSSHAIR_NOD_RATIO = 0.55f
        private const val CROSSHAIR_LOOK_UP_RATIO = 0.45f
    }

    data class Thresholds(
        var earCloseRatio: Float = 0.50f,     // 当 EAR 低于基线 N% 时判为闭眼 (0.50=一半)
        var shakeLeftRatio: Float = 0.75f,
        var shakeRightRatio: Float = 0.25f,
        var nodRatio: Float = 0.6f,
        var lookUpRatio: Float = 0.35f,
        var mouthOpenMar: Float = 0.5f
    )

    @Volatile var thresholds: Thresholds = Thresholds(); private set

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

    // 自适应 EAR 基线
    private var earBaseline = 0.25f  // 默认睁眼 EAR 典型值，会动态更新
    private var earSampleSum = 0f
    private var earSampleCount = 0

    // 其他动作
    private var isShakeLocked = false
    private var isMouthOpened = false
    private var isHeadDownLocked = false
    private var isHeadUpLocked = false
    private var lastFaceDetectedTime = 0L

    private val enableBlinkRunnable = Runnable { isBlinkControlEnabled = true }
    private val unlockShakeRunnable = Runnable { isShakeLocked = false }
    private val unlockHeadDownRunnable = Runnable { isHeadDownLocked = false }
    private val unlockHeadUpRunnable = Runnable { isHeadUpLocked = false }

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
            val rotatedBitmap = if (image.imageInfo.rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
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
            if (System.currentTimeMillis() - lastFaceDetectedTime > FACE_LOST_TIMEOUT_MS) {
                resetAllStates()
            }
            return
        }
        lastFaceDetectedTime = System.currentTimeMillis()
        val landmarks = landmarksList[0]
        val th = thresholds

        // --- 眨眼检测 ---
        val leftEar = calculateEAR(landmarks, 362, 385, 387, 263, 373, 380)
        val rightEar = calculateEAR(landmarks, 33, 160, 158, 133, 153, 144)
        val avgEar = (leftEar + rightEar) / 2f

        frameCount++
        // 自适应基线更新
        if (!isEyesClosed) {
            earSampleSum += avgEar
            earSampleCount++
            if (earSampleCount >= EAR_BASELINE_INTERVAL) {
                earBaseline = earSampleSum / earSampleCount
                Log.d(TAG, "EAR 基线更新: $earBaseline")
                earSampleSum = 0f; earSampleCount = 0
            }
        }
        val earCloseThreshold = earBaseline * th.earCloseRatio
        handleBlink(avgEar < earCloseThreshold)

        if (frameCount % 60L == 0L) {
            Log.d(TAG, "帧${frameCount}: EAR=$avgEar 基线=$earBaseline 闭眼阈值=$earCloseThreshold 闭眼=${avgEar < earCloseThreshold}")
        }

        // --- 偏头方向（供准心模式光标移动，输出 -1/0/+1 方向向量）---
        val nose = landmarks[1]
        val rightFaceEdge = landmarks[234]
        val leftFaceEdge = landmarks[454]
        val forehead = landmarks[10]
        val chin = landmarks[152]

        var headDx = 0f  // +1 右, -1 左
        var headDy = 0f  // +1 下, -1 上

        // 摇头（横向）：基础手势用 th 阈值（不变），准心方向用更小的阈值
        val faceWidth = leftFaceEdge.x() - rightFaceEdge.x()
        if (faceWidth > 0) {
            val ratio = (nose.x() - rightFaceEdge.x()) / faceWidth
            if (ratio > th.shakeLeftRatio) {
                triggerShake(FaceAction.SHAKE_LEFT)
            } else if (ratio < th.shakeRightRatio) {
                triggerShake(FaceAction.SHAKE_RIGHT)
            }

            if (ratio > CROSSHAIR_SHAKE_LEFT_RATIO) {
                headDx = -1f
            } else if (ratio < CROSSHAIR_SHAKE_RIGHT_RATIO) {
                headDx = 1f
            }
        }

        // 低头/抬头（纵向）：基础手势用 th 阈值（不变），准心方向用更小的阈值
        val faceHeight = dist(forehead, chin)
        if (faceHeight > 0) {
            val nodRatio = (nose.y() - forehead.y()) / faceHeight
            if (nodRatio > th.nodRatio) {
                triggerHeadDown()
            } else if (nodRatio < th.lookUpRatio) {
                triggerLookUp()
            } else {
                isHeadDownLocked = false; isHeadUpLocked = false
            }

            if (nodRatio > CROSSHAIR_NOD_RATIO) {
                headDy = 1f
            } else if (nodRatio < CROSSHAIR_LOOK_UP_RATIO) {
                headDy = -1f
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
            if (mar > th.mouthOpenMar) {
                if (!isMouthOpened) { isMouthOpened = true; dispatchNonBlinkAction(FaceAction.MOUTH_OPEN) }
            } else {
                if (isMouthOpened) { isMouthOpened = false; dispatchNonBlinkAction(FaceAction.MOUTH_CLOSE) }
            }
        }
    }

    // ============================================================
    // 眨眼状态机 — 闭眼达到阈值立即触发，不等睁眼
    // ============================================================
    private fun handleBlink(eyesClosedNow: Boolean) {
        val now = System.currentTimeMillis()

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
                if (duration >= LONG_BLINK_MIN_MS) {
                    longBlinkFired = true
                    Log.i(TAG, "✓ 触发 LONG_BLINK (闭眼${duration}ms)")
                    onActionDetected(FaceAction.LONG_BLINK)
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
        val interval = now - lastPhysioBlinkTimestamp
        if (lastPhysioBlinkTimestamp != 0L && interval in DOUBLE_BLINK_MIN_INTERVAL..DOUBLE_BLINK_MAX_INTERVAL) {
            onActionDetected(FaceAction.DOUBLE_BLINK)
            lastPhysioBlinkTimestamp = 0L
        } else {
            lastPhysioBlinkTimestamp = now
        }
    }

    private fun triggerShake(action: FaceAction) {
        if (isShakeLocked) return
        isShakeLocked = true
        dispatchNonBlinkAction(action)
        mainHandler.postDelayed(unlockShakeRunnable, SHAKE_LOCK_DURATION_MS)
    }

    private fun triggerHeadDown() {
        if (isHeadDownLocked) return
        isHeadDownLocked = true
        dispatchNonBlinkAction(FaceAction.HEAD_DOWN)
        mainHandler.postDelayed(unlockHeadDownRunnable, SHAKE_LOCK_DURATION_MS)
    }

    private fun triggerLookUp() {
        if (isHeadUpLocked) return
        isHeadUpLocked = true
        dispatchNonBlinkAction(FaceAction.LOOK_UP)
        mainHandler.postDelayed(unlockHeadUpRunnable, SHAKE_LOCK_DURATION_MS)
    }

    private fun dispatchNonBlinkAction(action: FaceAction) {
        if (!nonBlinkActionsEnabled) return
        onActionDetected(action)
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
        isShakeLocked = false
        isHeadDownLocked = false
        isHeadUpLocked = false
        lastPhysioBlinkTimestamp = 0L
        earSampleSum = 0f; earSampleCount = 0
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
        SHAKE_LEFT, SHAKE_RIGHT, MOUTH_OPEN, MOUTH_CLOSE, BLINK
    }
}
