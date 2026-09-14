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

class FaceAnalyzer(
    context: Context,
    private val onActionDetected: (FaceAction) -> Unit,
    private val onPoseUpdate: ((Float, Float) -> Unit)? = null,
    private val onInitFailed: ((Throwable) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceAnalyzer"
        private const val ENTER_CONTROL_DELAY_MS = 5000L
        private const val SHORT_PRESS_MAX_MS = 500L

        private const val PHYSIO_BLINK_MAX_MS = 180L
        private const val IGNORE_BLINK_MAX_MS = 350L
        private const val LONG_BLINK_MAX_MS = 600L
        private const val DOUBLE_BLINK_MIN_INTERVAL = 100L
        private const val DOUBLE_BLINK_MAX_INTERVAL = 569L
        private const val SHAKE_LOCK_DURATION_MS = 1000L
        private const val FACE_LOST_TIMEOUT_MS = 2000L
    }

    private var faceLandmarker: FaceLandmarker? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val headPoseTracker = HeadPoseTracker()

    // ========== 核心状态 ==========
    private var isInControlMode = false
    private var isReleased = false

    // 进入计时
    private var enterControlStartTime = 0L

    // 眨眼状态
    private var isEyesClosed = false
    private var eyesClosedStartTime = 0L
    private var lastPhysioBlinkTimestamp = 0L
    private var isBlinkControlEnabled = true

    // 张嘴状态
    private var isMouthOpened = false
    private var mouthOpenStartTime = 0L

    // 摇头防抖
    private var isShakeLocked = false
    private var lastFaceDetectedTime = 0L

    // 阈值配置
    data class Thresholds(
        var earClose: Float = 0.2f,
        var shakeLeftRatio: Float = 0.75f,
        var shakeRightRatio: Float = 0.25f,
        var nodRatio: Float = 0.6f,
        var mouthOpenMar: Float = 0.5f
    )

    @Volatile
    var thresholds: Thresholds = Thresholds()
        private set

    fun updateThresholds(newThresholds: Thresholds) {
        thresholds = newThresholds
    }

    init {
        Log.d(TAG, "🔥 FaceAnalyzer 初始化成功！")
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ -> processResult(result) }
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Throwable) {
            Log.e(TAG, "FaceLandmarker 初始化失败", e)
            faceLandmarker = null
            onInitFailed?.invoke(e)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val landmarker = faceLandmarker
        if (isReleased || landmarker == null) {
            image.close()
            return
        }

        try {
            val originalBitmap = image.toBitmap()
            val rotatedBitmap = if (image.imageInfo.rotationDegrees != 0) {
                val matrix = Matrix().apply {
                    postRotate(image.imageInfo.rotationDegrees.toFloat())
                }
                Bitmap.createBitmap(
                    originalBitmap, 0, 0,
                    originalBitmap.width, originalBitmap.height,
                    matrix, true
                )
            } else {
                originalBitmap
            }

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            val timestampMs = image.imageInfo.timestamp / 1_000_000
            landmarker.detectAsync(mpImage, timestampMs)
        } catch (e: Throwable) {
            Log.e(TAG, "analyze frame failed", e)
        } finally {
            image.close()
        }
    }

    // ============================================================
    // 核心处理逻辑
    // ============================================================
    private fun processResult(result: FaceLandmarkerResult) {
        if (isReleased) return

        val landmarksList = result.faceLandmarks()
        if (landmarksList.isNullOrEmpty()) {
            if (System.currentTimeMillis() - lastFaceDetectedTime > FACE_LOST_TIMEOUT_MS) {
                isEyesClosed = false
                isMouthOpened = false
                isShakeLocked = false
                lastPhysioBlinkTimestamp = 0L
                lastFaceDetectedTime = System.currentTimeMillis()
            }
            return
        }
        lastFaceDetectedTime = System.currentTimeMillis()

        val landmarks = landmarksList[0]
        val th = thresholds

        // ---- 1. 检测睁眼/闭眼 ----
        val leftEar = calculateEAR(landmarks, 362, 385, 387, 263, 373, 380)
        val rightEar = calculateEAR(landmarks, 33, 160, 158, 133, 153, 144)
        val avgEar = (leftEar + rightEar) / 2f
        val eyesClosedNow = avgEar < th.earClose

        // ---- 2. 检测张嘴/闭嘴 ----
        val upperLip = landmarks[13]
        val lowerLip = landmarks[14]
        val leftMouth = landmarks[78]
        val rightMouth = landmarks[308]
        val mouthHeight = dist(upperLip, lowerLip)
        val mouthWidth = dist(leftMouth, rightMouth)
        val mar = if (mouthWidth > 0) mouthHeight / mouthWidth else 0f
        val mouthOpenNow = mar > th.mouthOpenMar

        // ---- 3. 核心状态机 ----
        handleStateMachine(eyesClosedNow, mouthOpenNow, landmarks)

        // ---- 4. 眨眼检测（两种模式下都运行：普通模式触发原有手势，光标模式下双眨眼退出）----
        handleBlink(eyesClosedNow)

        // ---- 5. 在空闲模式下执行原有动作 ----
        if (!isInControlMode) {
            handleOriginalActions(eyesClosedNow, mouthOpenNow, landmarks)
        }
    }

    // ============================================================
    // 状态机
    // ============================================================
    private fun handleStateMachine(eyesClosedNow: Boolean, mouthOpenNow: Boolean, landmarks: List<NormalizedLandmark>) {
        val now = System.currentTimeMillis()

        if (!isInControlMode) {
            // ---- 空闲模式：检测进入条件（闭眼≥5秒） ----
            if (eyesClosedNow) {
                if (enterControlStartTime == 0L) {
                    enterControlStartTime = now
                    Log.d(TAG, "⏱️ 开始计时进入控制模式...")
                } else if (now - enterControlStartTime >= ENTER_CONTROL_DELAY_MS) {
                    isInControlMode = true
                    enterControlStartTime = 0L
                    headPoseTracker.reset()
                    onActionDetected(FaceAction.ENTER_CONTROL)
                    Log.d(TAG, "🚀 进入虚拟光标控制模式")
                }
            } else {
                enterControlStartTime = 0L
            }
        } else {
            // ---- 控制模式 ----
            // 1. 光标移动（头部姿态）
            val pose = headPoseTracker.calculatePose(landmarks)
            val isLookingAway = kotlin.math.abs(pose.yaw) > 0.02f || kotlin.math.abs(pose.pitch) > 0.02f
            if (isLookingAway) {
                onPoseUpdate?.invoke(pose.yaw, pose.pitch)
            } else {
                onPoseUpdate?.invoke(0f, 0f)
            }

            // 2. 张嘴/闭嘴 → 短按/长按
            if (mouthOpenNow && !isMouthOpened) {
                isMouthOpened = true
                mouthOpenStartTime = now
                Log.d(TAG, "👄 张嘴开始")
            } else if (!mouthOpenNow && isMouthOpened) {
                isMouthOpened = false
                val duration = now - mouthOpenStartTime
                if (duration < SHORT_PRESS_MAX_MS) {
                    onActionDetected(FaceAction.CLICK)
                    Log.d(TAG, "👆 短按点击 (${duration}ms)")
                } else {
                    onActionDetected(FaceAction.PRESS)
                    Log.d(TAG, "👇 长按 (${duration}ms)")
                }
                mouthOpenStartTime = 0L
            }

            // 3. 退出条件：双眨眼（在 handleBlink/checkDoubleBlink 中检测并触发 exitControlMode）
        }
    }

    /**
     * 退出虚拟光标控制模式（由光标模式下的双眨眼触发）
     */
    private fun exitControlMode() {
        isInControlMode = false
        if (isMouthOpened) {
            isMouthOpened = false
            mouthOpenStartTime = 0L
            // 如果退出时还张着嘴，不触发释放
        }
        headPoseTracker.reset()
        onActionDetected(FaceAction.EXIT_CONTROL)
        Log.d(TAG, "🛑 双眨眼退出虚拟光标控制模式")
    }

    // ============================================================
    // 原有动作（仅在空闲模式下执行）
    // ============================================================
    private fun handleOriginalActions(eyesClosedNow: Boolean, mouthOpenNow: Boolean, landmarks: List<NormalizedLandmark>) {
        val th = thresholds

        // 眨眼检测已移至 processResult，两种模式下统一运行

        val nose = landmarks[1]
        val rightFaceEdge = landmarks[234]
        val leftFaceEdge = landmarks[454]
        val faceWidth = (leftFaceEdge.x() - rightFaceEdge.x())
        if (faceWidth > 0) {
            val ratio = (nose.x() - rightFaceEdge.x()) / faceWidth
            if (ratio > th.shakeLeftRatio) {
                triggerShake(FaceAction.SHAKE_LEFT)
            } else if (ratio < th.shakeRightRatio) {
                triggerShake(FaceAction.SHAKE_RIGHT)
            }
        }

        val noseTip = landmarks[1]
        val forehead = landmarks[10]
        val chin = landmarks[152]
        val faceHeight = dist(forehead, chin)
        if (faceHeight > 0) {
            val nodRatio = (noseTip.y() - forehead.y()) / faceHeight
            if (nodRatio > th.nodRatio) {
                triggerShake(FaceAction.NOD)
            }
        }

        if (mouthOpenNow && !isMouthOpened) {
            isMouthOpened = true
            onActionDetected(FaceAction.MOUTH_OPEN)
        } else if (!mouthOpenNow && isMouthOpened) {
            isMouthOpened = false
            onActionDetected(FaceAction.MOUTH_CLOSE)
        }
    }

    // ============================================================
    // 眨眼逻辑
    // ============================================================
    private fun handleBlink(eyesClosedNow: Boolean) {
        if (!isBlinkControlEnabled) return
        val now = System.currentTimeMillis()

        if (eyesClosedNow) {
            if (!isEyesClosed) {
                isEyesClosed = true
                eyesClosedStartTime = now
            }
            return
        }

        if (isEyesClosed) {
            isEyesClosed = false
            val duration = now - eyesClosedStartTime

            when {
                duration < PHYSIO_BLINK_MAX_MS -> checkDoubleBlink(now)
                duration < IGNORE_BLINK_MAX_MS -> lastPhysioBlinkTimestamp = 0L
                duration < LONG_BLINK_MAX_MS -> {
                    lastPhysioBlinkTimestamp = 0L
                    onActionDetected(FaceAction.LONG_BLINK)
                }
                else -> lastPhysioBlinkTimestamp = 0L
            }
        }
    }

    private fun checkDoubleBlink(now: Long) {
        val interval = now - lastPhysioBlinkTimestamp
        if (lastPhysioBlinkTimestamp != 0L &&
            interval in DOUBLE_BLINK_MIN_INTERVAL..DOUBLE_BLINK_MAX_INTERVAL
        ) {
            lastPhysioBlinkTimestamp = 0L
            if (isInControlMode) {
                // 光标模式下：双眨眼 → 退出光标模式
                exitControlMode()
            } else {
                // 普通模式下：双眨眼 → 原有手势（向上滑动），保持不变
                onActionDetected(FaceAction.DOUBLE_BLINK)
            }
        } else {
            lastPhysioBlinkTimestamp = now
            onActionDetected(FaceAction.BLINK)
        }
    }

    private fun triggerShake(action: FaceAction) {
        if (isShakeLocked) return
        isShakeLocked = true
        onActionDetected(action)
        mainHandler.postDelayed({ isShakeLocked = false }, SHAKE_LOCK_DURATION_MS)
    }

    // ============================================================
    // 辅助方法
    // ============================================================
    private fun calculateEAR(
        l: List<NormalizedLandmark>,
        p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int
    ): Float {
        return FaceMath.calculateEAR(
            l[p1].x(), l[p1].y(),
            l[p2].x(), l[p2].y(),
            l[p3].x(), l[p3].y(),
            l[p4].x(), l[p4].y(),
            l[p5].x(), l[p5].y(),
            l[p6].x(), l[p6].y(),
        )
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        FaceMath.dist(a.x(), a.y(), b.x(), b.y())

    fun close() {
        isReleased = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            faceLandmarker?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "关闭 FaceLandmarker 失败", e)
        } finally {
            faceLandmarker = null
        }
    }

    fun resetHeadPoseTracker() {
        headPoseTracker.reset()
        Log.d(TAG, "🔄 HeadPoseTracker 已重置")
    }

    // ============================================================
    // 动作枚举
    // ============================================================
    enum class FaceAction {
        BLINK,
        DOUBLE_BLINK,
        LONG_BLINK,
        NOD,
        SHAKE_LEFT,
        SHAKE_RIGHT,
        MOUTH_OPEN,
        MOUTH_CLOSE,
        ENTER_CONTROL,
        EXIT_CONTROL,
        CLICK,      // 短按点击
        PRESS,      // 长按
        RELEASE     // 释放长按
    }
}