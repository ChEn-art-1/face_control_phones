package com.example.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
<<<<<<< HEAD
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
=======
import android.os.IBinder
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceControlForegroundService : LifecycleService() {

    private lateinit var cameraExecutor: ExecutorService
<<<<<<< HEAD
    private var cameraProvider: ProcessCameraProvider? = null

    // 屏幕尺寸，用于百分比坐标换算
    private var screenWidth = 1080
    private var screenHeight = 2400

    companion object {
        private const val TAG = "FaceControlService"
        private const val CHANNEL_ID = "face_control_channel"
        private const val NOTIFICATION_ID = 1
        private const val ERROR_NOTIFICATION_ID = 2
    }
=======
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
<<<<<<< HEAD
        updateScreenSize()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startCamera()
    }

    /**
     * 动态获取屏幕真实尺寸，兼容不同 API 版本
     */
    private fun updateScreenSize() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕方向变化时重新获取尺寸
        updateScreenSize()
    }

    private fun createNotificationChannel() {
        val channelName = "Face Control Service"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
=======
        createNotificationChannel()
        startForeground(1, createNotification())
        startCamera()
    }

    private fun createNotificationChannel() {
        val channelId = "face_control_channel"
        val channelName = "Face Control Service"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
<<<<<<< HEAD
        return NotificationCompat.Builder(this, CHANNEL_ID)
=======
        val channelId = "face_control_channel"
        return NotificationCompat.Builder(this, channelId)
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
            .setContentTitle("FaceControl is active")
            .setContentText("Monitoring face gestures...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
<<<<<<< HEAD
            try {
                cameraProvider = cameraProviderFuture.get()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                val analyzer = FaceAnalyzer(this) { action ->
                    handleFaceAction(action)
                }

                imageAnalysis.setAnalyzer(cameraExecutor, analyzer)

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "摄像头启动失败", e)
                handleCameraFailure()
=======
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            val analyzer = FaceAnalyzer(this) { action ->
                handleFaceAction(action)
            }

            imageAnalysis.setAnalyzer(cameraExecutor, analyzer)

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                e.printStackTrace()
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
            }
        }, ContextCompat.getMainExecutor(this))
    }

<<<<<<< HEAD
    /**
     * 摄像头打开失败时的处理：通知用户 + 停止服务
     */
    private fun handleCameraFailure() {
        Toast.makeText(
            this,
            "无法启动摄像头，FaceControl 服务已停止",
            Toast.LENGTH_LONG
        ).show()

        showErrorNotification()

        // 停止前台服务，触发 onDestroy 释放资源
        stopSelf()
    }

    private fun showErrorNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FaceControl 启动失败")
            .setContentText("无法访问摄像头，请检查权限或摄像头是否被占用")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .build()
        manager.notify(ERROR_NOTIFICATION_ID, notification)
    }

=======
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
    private fun handleFaceAction(action: FaceAnalyzer.FaceAction) {
        val service = FaceAccessibilityService.instance ?: return
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        if (isPortrait) {
            handlePortraitMode(action, service)
        } else {
            handleLandscapeMode(action, service)
        }
    }

<<<<<<< HEAD
    // ------- 百分比坐标转换工具方法 -------
    private fun px(percentX: Float): Float = screenWidth * percentX
    private fun py(percentY: Float): Float = screenHeight * percentY

    private fun handlePortraitMode(action: FaceAnalyzer.FaceAction, service: FaceAccessibilityService) {
        when (action) {
            FaceAnalyzer.FaceAction.DOUBLE_BLINK -> {
                // 向上滑动
                service.performSwipeAction(
                    px(0.5f), py(0.75f),
                    px(0.5f), py(0.25f)
                )
            }
            FaceAnalyzer.FaceAction.SHAKE_LEFT -> {
                // 左扭头 -> 向左滑动
                service.performSwipeAction(
                    px(0.9f), py(0.5f),
                    px(0.1f), py(0.5f)
                )
            }
            FaceAnalyzer.FaceAction.SHAKE_RIGHT -> {
                // 右扭头 -> 向右滑动
                service.performSwipeAction(
                    px(0.1f), py(0.5f),
                    px(0.9f), py(0.5f)
                )
            }
            FaceAnalyzer.FaceAction.MOUTH_OPEN -> {
                service.startContinuousPress(px(0.5f), py(0.5f))
            }
            FaceAnalyzer.FaceAction.MOUTH_CLOSE -> {
                service.stopContinuousPress(px(0.5f), py(0.5f))
            }
            FaceAnalyzer.FaceAction.NOD -> {
                service.performClickAction(px(0.5f), py(0.5f))
=======
    private fun handlePortraitMode(action: FaceAnalyzer.FaceAction, service: FaceAccessibilityService) {
        when (action) {
            FaceAnalyzer.FaceAction.DOUBLE_BLINK -> {
                // 原始方案：向上滑动
                service.performSwipeAction(500f, 1500f, 500f, 500f)
            }
            FaceAnalyzer.FaceAction.SHAKE_LEFT -> {
                // 左扭头 -> 向左滑动
                service.performSwipeAction(900f, 1000f, 100f, 1000f)
            }
            FaceAnalyzer.FaceAction.SHAKE_RIGHT -> {
                // 右扭头 -> 向右滑动
                service.performSwipeAction(100f, 1000f, 900f, 1000f)
            }
            FaceAnalyzer.FaceAction.MOUTH_OPEN -> {
                service.startContinuousPress(500f, 1000f)
            }
            FaceAnalyzer.FaceAction.MOUTH_CLOSE -> {
                service.stopContinuousPress(500f, 1000f)
            }
            FaceAnalyzer.FaceAction.NOD -> {
                service.performClickAction(500f, 1000f)
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
            }
            else -> {}
        }
    }

    private fun handleLandscapeMode(action: FaceAnalyzer.FaceAction, service: FaceAccessibilityService) {
<<<<<<< HEAD
        when (action) {
            FaceAnalyzer.FaceAction.DOUBLE_BLINK -> {
                // 双眨眼 -> 从左向右拖动 (快进)
                service.performSwipeAction(
                    px(0.2f), py(0.5f),
                    px(0.8f), py(0.5f)
                )
            }
            FaceAnalyzer.FaceAction.NOD -> {
                // 点头 -> 从右向左拖动 (倒退)
                service.performSwipeAction(
                    px(0.8f), py(0.5f),
                    px(0.2f), py(0.5f)
                )
            }
            FaceAnalyzer.FaceAction.MOUTH_OPEN -> {
                service.startContinuousPress(px(0.5f), py(0.5f))
            }
            FaceAnalyzer.FaceAction.MOUTH_CLOSE -> {
                service.stopContinuousPress(px(0.5f), py(0.5f))
=======
        // 横屏方案 (假设屏幕宽 2400, 高 1080)
        when (action) {
            FaceAnalyzer.FaceAction.DOUBLE_BLINK -> {
                // 双眨眼 -> 从左向右拖动 (快进)
                service.performSwipeAction(500f, 540f, 1900f, 540f)
            }
            FaceAnalyzer.FaceAction.NOD -> {
                // 点头 -> 从右向左拖动 (倒退)
                service.performSwipeAction(1900f, 540f, 500f, 540f)
            }
            FaceAnalyzer.FaceAction.MOUTH_OPEN -> {
                // 张嘴 -> 持续长按屏幕中心
                service.startContinuousPress(1200f, 540f)
            }
            FaceAnalyzer.FaceAction.MOUTH_CLOSE -> {
                service.stopContinuousPress(1200f, 540f)
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
            }
            else -> {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
<<<<<<< HEAD
        releaseResources()
    }

    /**
     * 统一释放摄像头及线程资源
     */
    private fun releaseResources() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "解绑摄像头时出错", e)
        } finally {
            cameraProvider = null
        }

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
=======
        cameraExecutor.shutdown()
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
<<<<<<< HEAD
}

// 1) 创建 analyzer 时传入初始化失败回调
private var faceAnalyzer: FaceAnalyzer? = null

// startCamera() 内：
val analyzer = FaceAnalyzer(
    context = this,
    onActionDetected = { action -> handleFaceAction(action) },
    onInitFailed = { error ->
        Log.e(TAG, "人脸识别模型初始化失败", error)
        handleCameraFailure()  // 复用上一轮的失败处理：Toast + 通知 + stopSelf
    }
)
faceAnalyzer = analyzer
imageAnalysis.setAnalyzer(cameraExecutor, analyzer)

// 2) releaseResources() 内追加：
faceAnalyzer?.close()
faceAnalyzer = null
=======
}
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
