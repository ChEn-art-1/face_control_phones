package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import android.os.Handler
import android.os.Looper
import java.util.Optional
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ============================================================
 * 人脸动作识别引擎 — 本 App 的核心算法模块
 *
 * 职责：
 *   1. 加载 MediaPipe FaceLandmarker 模型（face_landmarker.task）
 *   2. 接收 CameraX 的每一帧图像
 *   3. 用 468 个面部关键点检测：眨眼、扭头、点头、张嘴
 *   4. 将识别结果通过 onActionDetected 回调通知出去
 *
 * 检测的 5 种动作：
 *   BLINK         → 单次眨眼
 *   DOUBLE_BLINK  → 双眨眼（500ms 内两次眨眼）
 *   SHAKE_LEFT    → 向左扭头
 *   SHAKE_RIGHT   → 向右扭头
 *   NOD           → 点头（低头）
 *   MOUTH_OPEN    → 张嘴
 *   MOUTH_CLOSE   → 闭嘴
 * ============================================================
 */
class FaceAnalyzer(
    context: Context,
    /** 当检测到人脸动作时回调，由 FaceControlForegroundService 处理后续手势 */
    private val onActionDetected: (FaceAction) -> Unit
) : ImageAnalysis.Analyzer {

    // ---------- MediaPipe 人脸关键点检测器 ----------
    private var faceLandmarker: FaceLandmarker? = null

    // ---------- 状态变量 ----------
    private var isEyesClosed = false                 // 当前眼睛是否闭合（用于检测眨眼完成）
    private var lastBlinkTimestamp: Long = 0          // 上次眨眼时间戳（用于区分单击/双击）
    private var lastShakeTime = 0L                    // 上次扭头时间戳（防抖，1s 内不重复）
    private var lastNodTime = 0L                      // 上次点头时间戳（防抖，1s 内不重复）
    private var isMouthOpened = false                 // 当前嘴巴是否张开（用于检测张嘴/闭嘴转换）
    private var lastFaceDetectedTime = 0L             // 上次检测到人脸的时间（用于超时重置）

    // MediaPipe 回调在内部线程，无障碍 API 需切回主线程
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // ---------- 1. 加载 MediaPipe 模型 ----------
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")  // 模型文件在 app/src/main/assets/ 下
            .build()

        // ---------- 2. 配置检测器 ----------
        // LIVE_STREAM 模式：每帧都会回调，适合实时视频流
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ -> processResult(result) }
            .setOutputFaceBlendshapes(true)   // 输出 ML 表情系数，比几何计算更鲁棒
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    // ============================================================
    // CameraX 分析器接口 — 每来一帧就调用一次
    // ============================================================
    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        // ---------- 1. 将相机帧转成 Bitmap ----------
        val bitmap = image.toBitmap()

        // ---------- 2. 处理旋转 ----------
        // 前置摄像头通常有旋转角度，需要矫正方向后再喂给 MediaPipe
        val rotatedBitmap = if (image.imageInfo.rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        // ---------- 3. 转成 MediaPipe 的 MP Image 格式 ----------
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        // ---------- 4. 异步检测 ----------
        // 结果会回调到上面注册的 setResultListener → processResult()
        faceLandmarker?.detectAsync(mpImage, image.imageInfo.timestamp)

        // ---------- 5. 释放相机帧 ----------
        // 必须调用 close() 否则 CameraX 不会再发新帧
        image.close()
    }

    // ============================================================
    // MediaPipe 检测结果处理器 — 每帧检测完成后回调
    // ============================================================
    private fun processResult(result: FaceLandmarkerResult) {
        val landmarksList = result.faceLandmarks()
        if (landmarksList.isNullOrEmpty()) {
            // 超过 2 秒没检测到人脸 → 重置所有状态，防止回到画面时误触发
            if (System.currentTimeMillis() - lastFaceDetectedTime > 2000) {
                isEyesClosed = false
                isMouthOpened = false
                lastShakeTime = 0
                lastNodTime = 0
                lastBlinkTimestamp = 0
            }
            return
        }
        lastFaceDetectedTime = System.currentTimeMillis()

        // 只处理第一张人脸（单用户场景）
        val landmarks = landmarksList[0]

        // ---------- 1. 眨眼检测（带迟滞）----------
        // 迟滞设计：EAR < 0.18 判定闭合，EAR > 0.25 判定睁开
        // 中间 0.18~0.25 为迟滞区间，防止阈值附近抖动误触发
        val leftEar = calculateEAR(landmarks, 362, 385, 387, 263, 373, 380)
        val rightEar = calculateEAR(landmarks, 33, 160, 158, 133, 153, 144)
        val avgEar = (leftEar + rightEar) / 2f

        // 闭合阈值（低于此值判定为闭眼）
        if (avgEar < 0.18f) {
            isEyesClosed = true
        }
        // 睁开阈值（高于此值且之前是闭眼 → 完成一次眨眼）
        // 设为 0.2 与原版一致，保证双眨眼时眼睛微张也能识别第二次眨眼
        if (avgEar > 0.2f && isEyesClosed) {
            isEyesClosed = false
            handleBlinkTiming()
        }

        // ---------- 2. 扭头检测 + 3. 点头检测（互斥）----------
        // 互斥设计：同一帧内扭头和点头只触发一个，防止斜头时同时触发
        var headActionTriggered = false
        val nose = landmarks[1]
        val rightFaceEdge = landmarks[234]
        val leftFaceEdge = landmarks[454]
        val faceWidth = (leftFaceEdge.x() - rightFaceEdge.x())
        val noseTip = landmarks[1]
        val forehead = landmarks[10]       // 眉间
        val chin = landmarks[152]          // 下巴
        val faceHeight = dist(forehead, chin)

        if (faceWidth > 0) {
            val ratio = (nose.x() - rightFaceEdge.x()) / faceWidth
            if (ratio > 0.75f) {          // 鼻尖偏左 → 向左扭头
                triggerShake(FaceAction.SHAKE_LEFT)
                headActionTriggered = true
            } else if (ratio < 0.25f) {   // 鼻尖偏右 → 向右扭头
                triggerShake(FaceAction.SHAKE_RIGHT)
                headActionTriggered = true
            }
        }
        // 如果扭头已触发，点头不触发（同一帧互斥）
        if (!headActionTriggered && faceHeight > 0) {
            val nodRatio = (noseTip.y() - forehead.y()) / faceHeight
            if (nodRatio > 0.6f) {
                triggerShake(FaceAction.NOD)
            }
        }

        // ---------- 4. 张嘴检测（Blendshape ML 置信度）----------
        // faceBlendshapes() 返回 Optional<List<List<Category>>>
        // 外层 List: 每张人脸，内层 List<Category>: 52 个表情系数
        // 索引 25 = "mouthOpen" 置信度 (0~1)
        val blendshapesOpt = result.faceBlendshapes()
        if (blendshapesOpt.isPresent) {
            val categories = blendshapesOpt.get().getOrNull(0)
            if (categories != null && categories.size > 25) {
                val mouthConfidence = categories[25].score()
                if (mouthConfidence > 0.5f) {
                    if (!isMouthOpened) {
                        dispatchAction(FaceAction.MOUTH_OPEN)
                        isMouthOpened = true
                    }
                } else {
                    if (isMouthOpened) {
                        dispatchAction(FaceAction.MOUTH_CLOSE)
                        isMouthOpened = false
                    }
                }
            }
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 通过主线程派发动作事件，避免在 MediaPipe 内部线程调用无障碍 API
     */
    private fun dispatchAction(action: FaceAction) {
        mainHandler.post { onActionDetected(action) }
    }

    /**
     * 触发扭头/点头动作，各自带 1 秒独立防抖
     * 防止一帧内多次触发或轻微晃动误触
     * 扭头和点头不再互相阻塞
     */
    private val cooldownMs = 1000L

    private fun triggerShake(action: FaceAction) {
        val now = System.currentTimeMillis()
        val isCooledDown = when (action) {
            FaceAction.NOD -> now - lastNodTime > cooldownMs
            else -> now - lastShakeTime > cooldownMs  // SHAKE_LEFT / SHAKE_RIGHT
        }
        if (isCooledDown) {
            dispatchAction(action)
            when (action) {
                FaceAction.NOD -> lastNodTime = now
                else -> lastShakeTime = now
            }
        }
    }

    /**
     * 基于时间差区分 单眨眼 和 双眨眼
     * 逻辑：两次眨眼间隔 < 500ms → DOUBLE_BLINK，否则 → BLINK
     */
    private fun handleBlinkTiming() {
        val currentTime = System.currentTimeMillis()
        val interval = currentTime - lastBlinkTimestamp
        if (interval in 100..500) {
            // 500ms 内连续两次眨眼 → 双眨眼
            dispatchAction(FaceAction.DOUBLE_BLINK)
            lastBlinkTimestamp = 0
        } else {
            // 单次眨眼
            lastBlinkTimestamp = currentTime
            dispatchAction(FaceAction.BLINK)
        }
    }

    /**
     * 计算 Eye Aspect Ratio (EAR)
     * 用于判断眼睛开合状态，值越小表示眼睛闭合程度越高
     *
     * 公式：(dist(p2,p6) + dist(p3,p5)) / (2 * dist(p1,p4))
     *   p1/p4 → 眼睛左右眼角
     *   p2-p6、p3-p5 → 上下眼睑对应点
     */
    private fun calculateEAR(
        l: List<NormalizedLandmark>,
        p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int
    ): Float {
        val v1 = dist(l[p2], l[p6])
        val v2 = dist(l[p3], l[p5])
        val h = dist(l[p1], l[p4])
        return (v1 + v2) / (2f * h)
    }

    /**
     * 计算两个关键点之间的欧氏距离
     */
    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        sqrt((a.x() - b.x()).toDouble().pow(2.0) + (a.y() - b.y()).toDouble().pow(2.0)).toFloat()

    /**
     * 人脸动作枚举 — 所有可能的识别结果
     * 由 FaceControlForegroundService 接收后执行相应手势
     */
    enum class FaceAction {
        BLINK,          // 单次眨眼
        DOUBLE_BLINK,   // 双眨眼（快速眨两次）
        NOD,            // 点头（低头）
        SHAKE_LEFT,     // 向左扭头（鼻尖偏左）
        SHAKE_RIGHT,    // 向右扭头（鼻尖偏右）
        MOUTH_OPEN,     // 张嘴
        MOUTH_CLOSE     // 闭嘴
    }
}
