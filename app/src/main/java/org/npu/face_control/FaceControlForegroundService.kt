package org.npu.face_control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceControlForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "FaceControlService"
        private const val CHANNEL_ID = "face_control_channel"
        private const val NOTIFICATION_ID = 1
    }

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var faceAnalyzer: FaceAnalyzer? = null

    // ========== 光标相关 ==========
    private lateinit var windowManager: WindowManager
    private var cursorView: CursorView? = null
    private var cursorLayoutParams: WindowManager.LayoutParams? = null
    private var isCursorVisible = false
    private var screenWidth = 1080
    private var screenHeight = 2400

    // ========== 光标移动参数 ==========
    private var currentX = 0f
    private var currentY = 0f
    private val CURSOR_SPEED = 35f
    private val CURSOR_SIZE = 80
    private val DEAD_ZONE = 0.02f

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 Service onCreate")
        cameraExecutor = Executors.newSingleThreadExecutor()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenSize()

        currentX = screenWidth / 2f
        currentY = screenHeight / 2f

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🛑 Service onDestroy")
        hideCursor()
        releaseResources()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenSize()
    }

    private fun updateScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        Log.d(TAG, "📱 屏幕尺寸: ${screenWidth}x${screenHeight}")
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    // ========== 通知 ==========
    private fun createNotificationChannel() {
        val channelName = getString(R.string.notification_channel_name)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    // ========== 相机 ==========
    private fun startCamera() {
        Log.d(TAG, "📷 startCamera 开始")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "📷 CameraProvider 获取成功")

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                val analyzer = FaceAnalyzer(
                    context = this,
                    onActionDetected = { action ->
                        Log.d(TAG, "📢 收到动作: $action")
                        handleFaceAction(action)
                    },
                    onPoseUpdate = { yaw, pitch ->
                        Log.d(TAG, "📍 姿态更新: yaw=$yaw, pitch=$pitch")
                        handlePoseUpdate(yaw, pitch)
                    },
                    onInitFailed = { error ->
                        Log.e(TAG, "人脸识别模型初始化失败", error)
                        handleCameraFailure()
                    }
                )
                faceAnalyzer = analyzer
                imageAnalysis.setAnalyzer(cameraExecutor, analyzer)

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, imageAnalysis)

                Log.d(TAG, "📷 相机已启动")

            } catch (e: Exception) {
                Log.e(TAG, "摄像头启动失败", e)
                handleCameraFailure()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleCameraFailure() {
        showToast("无法启动摄像头，FaceControl 服务已停止")
        stopSelf()
    }

    private fun releaseResources() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "解绑摄像头时出错", e)
        } finally {
            cameraProvider = null
        }

        try {
            faceAnalyzer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭 FaceAnalyzer 时出错", e)
        } finally {
            faceAnalyzer = null
        }

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }

    // ================================================================
    // 光标控制
    // ================================================================

    private fun showCursor() {
        Log.d(TAG, "🖱️ showCursor 被调用, isCursorVisible=$isCursorVisible")
        if (isCursorVisible) {
            Log.d(TAG, "⚠️ 光标已显示，跳过")
            return
        }

        mainHandler.post {
            try {
                if (isCursorVisible) return@post

                val cursor = CursorView(this@FaceControlForegroundService)
                cursorView = cursor

                val params = WindowManager.LayoutParams(
                    CURSOR_SIZE,
                    CURSOR_SIZE,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    x = currentX.toInt() - CURSOR_SIZE / 2
                    y = currentY.toInt() - CURSOR_SIZE / 2
                }

                cursorLayoutParams = params
                windowManager.addView(cursor, params)
                isCursorVisible = true
                Log.d(TAG, "✅ 光标已显示 at (${currentX.toInt()}, ${currentY.toInt()})")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 显示光标失败", e)
            }
        }
    }

    private fun hideCursor() {
        Log.d(TAG, "🖱️ hideCursor 被调用, isCursorVisible=$isCursorVisible")
        if (!isCursorVisible) {
            Log.d(TAG, "⚠️ 光标已隐藏，跳过")
            return
        }

        mainHandler.post {
            try {
                cursorView?.let { windowManager.removeView(it) }
            } catch (e: Exception) {
                Log.e(TAG, "移除光标时出错", e)
            }
            cursorView = null
            cursorLayoutParams = null
            isCursorVisible = false
            Log.d(TAG, "✅ 光标已隐藏")
        }
    }

    private fun moveCursor(deltaX: Float, deltaY: Float) {
        if (!isCursorVisible) return

        val moveX = deltaX * CURSOR_SPEED
        val moveY = deltaY * CURSOR_SPEED

        currentX = (currentX + moveX).coerceIn(0f, screenWidth.toFloat())
        currentY = (currentY + moveY).coerceIn(0f, screenHeight.toFloat())

        cursorLayoutParams?.let { params ->
            params.x = currentX.toInt() - CURSOR_SIZE / 2
            params.y = currentY.toInt() - CURSOR_SIZE / 2
            try {
                mainHandler.post {
                    cursorView?.let { windowManager.updateViewLayout(it, params) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "更新光标位置时出错", e)
            }
        }
    }

    // ================================================================
    // 姿态更新处理
    // ================================================================

    private fun handlePoseUpdate(yaw: Float, pitch: Float) {
        if (!isCursorVisible) return

        if (kotlin.math.abs(yaw) > DEAD_ZONE || kotlin.math.abs(pitch) > DEAD_ZONE) {
            moveCursor(-yaw, pitch)
        }
    }

    // ================================================================
    // 动作处理
    // ================================================================

    private fun handleFaceAction(action: FaceAnalyzer.FaceAction) {
        Log.d(TAG, "🎯 处理动作: $action")
        val service = FaceAccessibilityService.instance

        when (action) {
            FaceAnalyzer.FaceAction.ENTER_CONTROL -> {
                Log.d(TAG, "🎯 进入控制模式")
                faceAnalyzer?.resetHeadPoseTracker()
                showCursor()
                showToast("🎯 进入虚拟光标模式")
            }
            FaceAnalyzer.FaceAction.EXIT_CONTROL -> {
                Log.d(TAG, "🎯 退出控制模式")
                hideCursor()
                faceAnalyzer?.resetHeadPoseTracker()
                showToast("🛑 退出虚拟光标模式")
            }

            // ---- 短按点击 ----
            FaceAnalyzer.FaceAction.CLICK -> {
                Log.d(TAG, "👆 点击 at (${currentX.toInt()}, ${currentY.toInt()})")
                service?.performClickAction(currentX, currentY)
            }
            // ---- 长按按压 ----
            FaceAnalyzer.FaceAction.PRESS -> {
                Log.d(TAG, "👇 长按开始 at (${currentX.toInt()}, ${currentY.toInt()})")
                service?.startContinuousPress(currentX, currentY)
            }
            // ---- 释放长按 ----
            FaceAnalyzer.FaceAction.RELEASE -> {
                Log.d(TAG, "👆 释放长按")
                service?.stopContinuousPress(currentX, currentY)
            }

            // ---- 原有功能（仅在空闲模式下生效） ----
            FaceAnalyzer.FaceAction.DOUBLE_BLINK -> {
                Log.d(TAG, "😉😉 双眨眼")
                if (!isCursorVisible) {
                    service?.let { performSwipeUp(it) }
                }
            }
            FaceAnalyzer.FaceAction.SHAKE_LEFT -> {
                Log.d(TAG, "👈 左摇头")
                if (!isCursorVisible) {
                    service?.let { performSwipeLeft(it) }
                }
            }
            FaceAnalyzer.FaceAction.SHAKE_RIGHT -> {
                Log.d(TAG, "👉 右摇头")
                if (!isCursorVisible) {
                    service?.let { performSwipeRight(it) }
                }
            }
            FaceAnalyzer.FaceAction.NOD -> {
                Log.d(TAG, "👆 点头")
                if (!isCursorVisible) {
                    service?.let { performClick(it) }
                }
            }
            else -> {
                Log.d(TAG, "忽略动作: $action")
            }
        }
    }

    // ================================================================
    // 原有手势方法
    // ================================================================

    private fun performSwipeUp(service: FaceAccessibilityService) {
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (isPortrait) {
            service.performSwipeAction(
                screenWidth * 0.5f, screenHeight * 0.75f,
                screenWidth * 0.5f, screenHeight * 0.25f
            )
        }
    }

    private fun performSwipeLeft(service: FaceAccessibilityService) {
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (isPortrait) {
            service.performSwipeAction(
                screenWidth * 0.8f, screenHeight * 0.5f,
                screenWidth * 0.2f, screenHeight * 0.5f
            )
        }
    }

    private fun performSwipeRight(service: FaceAccessibilityService) {
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (isPortrait) {
            service.performSwipeAction(
                screenWidth * 0.2f, screenHeight * 0.5f,
                screenWidth * 0.8f, screenHeight * 0.5f
            )
        }
    }

    private fun performClick(service: FaceAccessibilityService) {
        service.performClickAction(screenWidth * 0.5f, screenHeight * 0.5f)
    }
}