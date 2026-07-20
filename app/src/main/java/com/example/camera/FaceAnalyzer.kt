package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
<<<<<<< HEAD
import android.os.Handler
import android.os.Looper
import android.util.Log
=======
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
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
import kotlin.math.pow
import kotlin.math.sqrt

class FaceAnalyzer(
    context: Context,
<<<<<<< HEAD
    private val onActionDetected: (FaceAction) -> Unit,
    /** 初始化失败回调，交由上层决定后续动作（例如停止 Service 并通知用户） */
    private val onInitFailed: ((Throwable) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceAnalyzer"

        // ------- 眨眼时间分类阈值（ms）-------
        private const val PHYSIO_BLINK_MAX_MS = 180L      // < 180ms 视为生理性眨眼（可参与双眨眼判定）
        private const val IGNORE_BLINK_MAX_MS = 350L      // 180~350ms 忽略，避免误触
        private const val LONG_BLINK_MAX_MS = 600L        // 350~600ms 主动长闭眼
        // > 600ms 视为闭眼休息，忽略

        // ------- 双眨眼时间窗口 -------
        private const val DOUBLE_BLINK_MIN_INTERVAL = 100L
        private const val DOUBLE_BLINK_MAX_INTERVAL = 569L

        // ------- 其他动作后眨眼禁用时长 -------
        private const val BLINK_DISABLE_DURATION_MS = 1000L

        // ------- 摇头动作节流 -------
        private const val SHAKE_LOCK_DURATION_MS = 1000L
    }

    /**
     * 可运行时调节的阈值集合。通过 [updateThresholds] 修改。
     */
    data class Thresholds(
        var earClose: Float = 0.2f,          // 眼睛闭合的 EAR 阈值
        var shakeLeftRatio: Float = 0.75f,   // 摇头（向一侧）阈值
        var shakeRightRatio: Float = 0.25f,  // 摇头（向另一侧）阈值
        var nodRatio: Float = 0.6f,          // 点头阈值
        var mouthOpenMar: Float = 0.5f       // 张嘴 MAR 阈值
    )

    @Volatile
    var thresholds: Thresholds = Thresholds()
        private set

    fun updateThresholds(newThresholds: Thresholds) {
        thresholds = newThresholds
    }

    private var faceLandmarker: FaceLandmarker? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // -------- 眨眼状态 --------
    private var isEyesClosed = false
    private var eyesClosedStartTime: Long = 0L
    private var lastPhysioBlinkTimestamp: Long = 0L

    // 眨眼控制开关：识别到其他动作后临时禁用
    @Volatile
    private var isBlinkControlEnabled = true

    // -------- 其他动作状态 --------
    private var isShakeLocked = false
    private var isMouthOpened = false

    // 用于禁用眨眼的延迟任务，方便统一清理
    private val enableBlinkRunnable = Runnable { isBlinkControlEnabled = true }
    private val unlockShakeRunnable = Runnable { isShakeLocked = false }

    // 标记 Analyzer 是否已释放，防止在关闭后再处理帧
    @Volatile
    private var isReleased = false

    init {
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
            // 抛给上层去处理（停止服务 / 通知用户等）
            onInitFailed?.invoke(e)
        }
=======
    private val onActionDetected: (FaceAction) -> Unit
) : ImageAnalysis.Analyzer {

    private var faceLandmarker: FaceLandmarker? = null
    private var isEyesClosed = false
    private var lastBlinkTimestamp: Long = 0
    private var isShakeLocked = false
    private var isMouthOpened = false

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ -> processResult(result) }
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
<<<<<<< HEAD
        val landmarker = faceLandmarker
        if (isReleased || landmarker == null) {
            image.close()
            return
        }

        var originalBitmap: Bitmap? = null
        var rotatedBitmap: Bitmap? = null
        try {
            originalBitmap = image.toBitmap()
            rotatedBitmap = if (image.imageInfo.rotationDegrees != 0) {
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
            landmarker.detectAsync(mpImage, image.imageInfo.timestamp)
        } catch (e: Throwable) {
            Log.e(TAG, "analyze frame failed", e)
        } finally {
            // 释放临时 bitmap，避免累积占用内存
            // 注意：如果没有旋转，rotatedBitmap === originalBitmap，只 recycle 一次
            if (rotatedBitmap != null && rotatedBitmap !== originalBitmap) {
                rotatedBitmap.recycle()
            }
            originalBitmap?.recycle()
            image.close()
        }
    }

    private fun processResult(result: FaceLandmarkerResult) {
        if (isReleased) return

        val landmarksList = result.faceLandmarks()
        if (landmarksList.isNullOrEmpty()) return
        val landmarks = landmarksList[0]
        val th = thresholds

        // ---------------- 1. 眨眼逻辑 ----------------
        val leftEar = calculateEAR(landmarks, 362, 385, 387, 263, 373, 380)
        val rightEar = calculateEAR(landmarks, 33, 160, 158, 133, 153, 144)
        val avgEar = (leftEar + rightEar) / 2f
        handleBlink(avgEar < th.earClose)

        // ---------------- 2. 摇头逻辑 ----------------
=======
        val bitmap = image.toBitmap()
        val rotatedBitmap = if (image.imageInfo.rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        faceLandmarker?.detectAsync(mpImage, image.imageInfo.timestamp)
        image.close()
    }

    private fun processResult(result: FaceLandmarkerResult) {
        val landmarksList = result.faceLandmarks()
        if (landmarksList.isNullOrEmpty()) return
        val landmarks = landmarksList[0]

        // 1. 眨眼逻辑
        val leftEar = calculateEAR(landmarks, 362, 385, 387, 263, 373, 380)
        val rightEar = calculateEAR(landmarks, 33, 160, 158, 133, 153, 144)
        val avgEar = (leftEar + rightEar) / 2f
        if (avgEar < 0.2f) {
            isEyesClosed = true
        } else if (isEyesClosed) {
            isEyesClosed = false
            handleBlinkTiming()
        }

        // 2. 摇头逻辑 (区分左右)
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
        val nose = landmarks[1]
        val rightFaceEdge = landmarks[234]
        val leftFaceEdge = landmarks[454]
        val faceWidth = (leftFaceEdge.x() - rightFaceEdge.x())
        if (faceWidth > 0) {
            val ratio = (nose.x() - rightFaceEdge.x()) / faceWidth
<<<<<<< HEAD
            if (ratio > th.shakeLeftRatio) {
                triggerShake(FaceAction.SHAKE_LEFT)
            } else if (ratio < th.shakeRightRatio) {
=======
            if (ratio > 0.75f) { // 扭向一侧
                triggerShake(FaceAction.SHAKE_LEFT)
            } else if (ratio < 0.25f) { // 扭向另一侧
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
                triggerShake(FaceAction.SHAKE_RIGHT)
            }
        }

<<<<<<< HEAD
        // ---------------- 3. 点头逻辑 ----------------
=======
        // 3. 点头逻辑 (Simplified Pitch detection)
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
        val noseTip = landmarks[1]
        val forehead = landmarks[10]
        val chin = landmarks[152]
        val faceHeight = dist(forehead, chin)
        if (faceHeight > 0) {
            val nodRatio = (noseTip.y() - forehead.y()) / faceHeight
<<<<<<< HEAD
            if (nodRatio > th.nodRatio) {
=======
            if (nodRatio > 0.6f) { // 鼻尖位置下移，判定为点头
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
                triggerShake(FaceAction.NOD)
            }
        }

<<<<<<< HEAD
        // ---------------- 4. 张嘴逻辑 ----------------
=======
        // 4. 张嘴逻辑
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
        val upperLip = landmarks[13]
        val lowerLip = landmarks[14]
        val leftMouth = landmarks[78]
        val rightMouth = landmarks[308]
        val mouthHeight = dist(upperLip, lowerLip)
        val mouthWidth = dist(leftMouth, rightMouth)
        if (mouthWidth > 0) {
            val mar = mouthHeight / mouthWidth
<<<<<<< HEAD
            if (mar > th.mouthOpenMar) {
                if (!isMouthOpened) {
                    isMouthOpened = true
                    dispatchNonBlinkAction(FaceAction.MOUTH_OPEN)
                }
            } else {
                if (isMouthOpened) {
                    isMouthOpened = false
                    dispatchNonBlinkAction(FaceAction.MOUTH_CLOSE)
                }
=======
            if (mar > 0.5f) {
                if (!isMouthOpened) { onActionDetected(FaceAction.MOUTH_OPEN); isMouthOpened = true }
            } else {
                if (isMouthOpened) { onActionDetected(FaceAction.MOUTH_CLOSE); isMouthOpened = false }
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
            }
        }
    }

<<<<<<< HEAD
    /**
     * 眨眼状态机：
     * - 记录闭眼开始时间
     * - 睁眼时按持续时长分类处理
     */
    private fun handleBlink(eyesClosedNow: Boolean) {
        val now = System.currentTimeMillis()

        if (eyesClosedNow) {
            if (!isEyesClosed) {
                isEyesClosed = true
                eyesClosedStartTime = now
            }
            return
        }

        // 从闭 → 睁
        if (isEyesClosed) {
            isEyesClosed = false
            val duration = now - eyesClosedStartTime

            // 眨眼控制被禁用时，直接丢弃本次事件
            if (!isBlinkControlEnabled) {
                lastPhysioBlinkTimestamp = 0L
                return
            }

            when {
                duration < PHYSIO_BLINK_MAX_MS -> {
                    // 生理性眨眼：不直接触发动作，但参与双眨眼判定
                    checkDoubleBlink(now)
                }
                duration < IGNORE_BLINK_MAX_MS -> {
                    // 180~350ms 忽略，同时重置双眨眼计时避免误配
                    lastPhysioBlinkTimestamp = 0L
                }
                duration < LONG_BLINK_MAX_MS -> {
                    // 350~600ms 主动长闭眼
                    lastPhysioBlinkTimestamp = 0L
                    onActionDetected(FaceAction.LONG_BLINK)
                }
                else -> {
                    // > 600ms 视为休息，忽略
                    lastPhysioBlinkTimestamp = 0L
                }
            }
        }
    }

    private fun checkDoubleBlink(now: Long) {
        val interval = now - lastPhysioBlinkTimestamp
        if (lastPhysioBlinkTimestamp != 0L &&
            interval in DOUBLE_BLINK_MIN_INTERVAL..DOUBLE_BLINK_MAX_INTERVAL
        ) {
            onActionDetected(FaceAction.DOUBLE_BLINK)
            lastPhysioBlinkTimestamp = 0L
        } else {
            lastPhysioBlinkTimestamp = now
            // 单次生理性眨眼保留 BLINK 事件以便上层可选使用
            onActionDetected(FaceAction.BLINK)
        }
    }

    private fun triggerShake(action: FaceAction) {
        if (isShakeLocked) return
        isShakeLocked = true
        dispatchNonBlinkAction(action)
        // 使用成员 Handler，避免每次 new
        mainHandler.postDelayed(unlockShakeRunnable, SHAKE_LOCK_DURATION_MS)
    }

    /**
     * 非眨眼动作触发：
     * 1. 通知上层
     * 2. 临时禁用眨眼识别，避免头动/张嘴瞬间的眼形变化被误判成眨眼
     */
    private fun dispatchNonBlinkAction(action: FaceAction) {
        onActionDetected(action)
        disableBlinkTemporarily()
    }

    private fun disableBlinkTemporarily() {
        isBlinkControlEnabled = false
        // 重置眨眼相关状态
        isEyesClosed = false
        lastPhysioBlinkTimestamp = 0L
        mainHandler.removeCallbacks(enableBlinkRunnable)
        mainHandler.postDelayed(enableBlinkRunnable, BLINK_DISABLE_DURATION_MS)
    }

    private fun calculateEAR(
        l: List<NormalizedLandmark>,
        p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int
    ): Float {
        val v1 = dist(l[p2], l[p6])
        val v2 = dist(l[p3], l[p5])
        val h = dist(l[p1], l[p4])
        return if (h == 0f) 0f else (v1 + v2) / (2f * h)
=======
    private fun triggerShake(action: FaceAction) {
        if (!isShakeLocked) {
            onActionDetected(action)
            isShakeLocked = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ isShakeLocked = false }, 1000)
        }
    }

    private fun handleBlinkTiming() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlinkTimestamp in 100..569) {
            onActionDetected(FaceAction.DOUBLE_BLINK); lastBlinkTimestamp = 0
        } else {
            lastBlinkTimestamp = currentTime; onActionDetected(FaceAction.BLINK)
        }
    }

    private fun calculateEAR(l: List<NormalizedLandmark>, p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int): Float {
        val v1 = dist(l[p2], l[p6]); val v2 = dist(l[p3], l[p5]); val h = dist(l[p1], l[p4])
        return (v1 + v2) / (2f * h)
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        sqrt((a.x() - b.x()).toDouble().pow(2.0) + (a.y() - b.y()).toDouble().pow(2.0)).toFloat()

<<<<<<< HEAD
    /**
     * 释放资源。上层（Service.onDestroy）必须调用，否则 FaceLandmarker
     * 和 Handler 延迟任务会导致内存泄漏。
     */
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

    enum class FaceAction {
        BLINK,          // 单次生理性眨眼（可选使用）
        DOUBLE_BLINK,   // 双眨眼
        LONG_BLINK,     // 主动长闭眼（350~600ms）
        NOD,
        SHAKE_LEFT,
        SHAKE_RIGHT,
        MOUTH_OPEN,
        MOUTH_CLOSE
    }
=======
    enum class FaceAction { BLINK, DOUBLE_BLINK, NOD, SHAKE_LEFT, SHAKE_RIGHT, MOUTH_OPEN, MOUTH_CLOSE }
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
}