package org.npu.face_control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
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

/**
 * ============================================================
 * 前台服务 + 调度中枢 — 本 App 的"大脑"
 *
 * 职责链：
 *   CameraX (前置摄像头)
 *       ↓ 逐帧
 *   FaceAnalyzer (MediaPipe 人脸检测)
 *       ↓ 识别出 FaceAction
 *   当前方法 (判断横/竖屏，映射为具体手势)
 *       ↓ 调用
 *   FaceAccessibilityService (无障碍手势执行)
 *       ↓
 *   系统触摸事件 → 被控制 App 响应
 * ============================================================
 */
class FaceControlForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "FaceControlService"
        private const val CHANNEL_ID = "face_control_channel"
        private const val NOTIFICATION_ID = 1
        private const val ERROR_NOTIFICATION_ID = 2

        // ============================================================
        // 手势坐标常量（竖屏模式）
        // ============================================================

        // 滑动坐标
        private const val SWIPE_START_X_CENTER = 0.5f
        private const val SWIPE_END_X_CENTER = 0.5f
        private const val SWIPE_START_Y_BOTTOM = 0.75f
        private const val SWIPE_END_Y_TOP = 0.25f
        private const val SWIPE_START_Y_TOP = 0.25f
        private const val SWIPE_END_Y_BOTTOM = 0.75f

        // 左右滑动坐标
        private const val SWIPE_X_RIGHT = 0.9f
        private const val SWIPE_X_LEFT = 0.1f
        private const val SWIPE_Y_CENTER = 0.5f

        // 点击/按压坐标
        private const val CLICK_X_CENTER = 0.5f
        private const val CLICK_Y_CENTER = 0.5f

        // 准心光标移动：每帧步长（像素）与屏幕边距（像素，避免光标贴边）
        private const val CROSSHAIR_STEP_PX = 12f
        private const val CROSSHAIR_MARGIN = 30f

        // 命中检测（高亮命中目标）的节流间隔（毫秒）
        private const val HIT_TEST_INTERVAL_MS = 400L
    }

    /** 相机分析运行在独立线程，不阻塞主线程 */
    private lateinit var cameraExecutor: ExecutorService

    /** CameraX 相机提供者，用于资源释放 */
    private var cameraProvider: ProcessCameraProvider? = null

    /** 人脸分析器实例，用于释放资源 */
    private var faceAnalyzer: FaceAnalyzer? = null

    /** 屏幕尺寸，用于百分比坐标换算（动态更新） */
    private var screenWidth = 1080
    private var screenHeight = 2400

    /** 图像分析用例：保留引用，横竖屏切换时同步 targetRotation，使人脸关键点始终处于“显示坐标系” */
    private var imageAnalysis: ImageAnalysis? = null

    /** 当前是否竖屏（由显示旋转监听判定，用于手势模板路由，比 resources.configuration 可靠） */
    @Volatile private var isDevicePortrait = true

    /** 显示旋转监听：设备旋转时同步 CameraX targetRotation，从根上解耦手势与朝向 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) applyDisplayOrientation()
        }
    }

    // ================================================================
    // 准心模式（用户自选点击/长按位置）
    // ================================================================

    /** 准心模式是否开启 */
    @Volatile private var crosshairMode = false

    /** 准心悬浮窗实例（主线程创建/移除，相机线程读取，用 @Volatile 保证可见性） */
    @Volatile private var crosshairView: CrosshairOverlayView? = null

    /** 用户选定的位置（像素），-1 表示未设置 */
    @Volatile private var selectedX = -1f
    @Volatile private var selectedY = -1f

    /** 准心光标当前位置（像素），仅在准心模式下有效 */
    @Volatile private var cursorX = 0f
    @Volatile private var cursorY = 0f

    /** 上次命中检测时间（用于节流，避免每帧遍历节点树） */
    private var lastHitTestTime = 0L

    /** 命中检测是否正在执行（防止上一次未完成时再排队） */
    @Volatile private var hitTestInProgress = false

    /** 命中检测线程：节点树遍历较重，放后台线程避免阻塞主线程导致光标卡顿 */
    private val hitTestExecutor = Executors.newSingleThreadExecutor()

    /** 当前长按落点（张嘴时记录，闭嘴时复用同一落点） */
    private var pressPointX = 0f
    private var pressPointY = 0f

    /** 主线程 Handler：悬浮窗挂载/移除、Toast 必须在主线程执行 */
    private val mainHandler = Handler(Looper.getMainLooper())

    // ================================================================
    // 生命周期
    // ================================================================

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        updateScreenSize()

        // 1. 创建前台服务通知（系统必须，否则 Android 8+ 会崩溃）
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // 2. 启动 CameraX + 人脸检测流水线
        startCamera()

        // 3. 注册显示旋转监听：横竖屏切换时同步 CameraX targetRotation，解耦手势与朝向
        registerDisplayOrientationListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 如果相机被释放了，重新启动
        if (cameraProvider == null) {
            startCamera()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            Log.w(TAG, "反注册显示监听失败", e)
        }
        releaseResources()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null  // 非绑定服务
    }

    /**
     * 屏幕方向变化时重新获取尺寸，保证坐标计算准确
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenSize()
    }

    // ================================================================
    // 屏幕尺寸工具
    // ================================================================

    /**
     * 动态获取屏幕真实尺寸，兼容不同 API 版本。
     */
    private fun updateScreenSize() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        Log.d(TAG, "屏幕尺寸更新: ${screenWidth}x${screenHeight}")
    }

    /** 当前默认显示屏旋转常量（Surface.ROTATION_0/90/180/270） */
    private fun currentDisplayRotation(): Int {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplay(Display.DEFAULT_DISPLAY).rotation
    }

    /**
     * 应用当前显示朝向：刷新屏幕尺寸与路由标志，并同步 CameraX 目标旋转。
     * 关键点：同步 targetRotation 让 MediaPipe 关键点始终处于“显示坐标系”，
     * 这样无论横屏竖屏，低头/摇头/光标移动的方向语义都一致，实现与朝向解耦。
     */
    private fun applyDisplayOrientation() {
        updateScreenSize()
        val rot = currentDisplayRotation()
        isDevicePortrait = (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180)
        imageAnalysis?.targetRotation = rot
        Log.i(TAG, "显示朝向更新: rotation=$rot 竖屏=$isDevicePortrait")
    }

    /** 注册显示旋转监听（系统已融合陀螺仪/加速度计给出显示旋转，比 resources.configuration 可靠） */
    private fun registerDisplayOrientationListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, null)
        applyDisplayOrientation()
    }

    /** 百分比 X 坐标 → 像素值 */
    private fun px(percentX: Float): Float = screenWidth * percentX

    /** 百分比 Y 坐标 → 像素值 */
    private fun py(percentY: Float): Float = screenHeight * percentY

    // ================================================================
    // 前台服务通知
    // ================================================================

    /** 创建通知渠道（Android 8+ 必须） */
    private fun createNotificationChannel() {
        val channelName = "Face Control Service"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    /** 构建通知内容 */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FaceControl is active")
            .setContentText("Monitoring face gestures...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    /**
     * 显示错误通知（摄像头启动失败时）
     */
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

    // ================================================================
    // CameraX 相机流水线
    // ================================================================

    /**
     * 启动前置摄像头 + 人脸分析
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // ----- 图像分析器配置 -----
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                // 初始目标旋转跟随当前显示方向，保证关键点一开始就处于显示坐标系
                analysis.targetRotation = currentDisplayRotation()
                imageAnalysis = analysis

                // ----- 人脸分析器 -----
                val analyzer = FaceAnalyzer(
                    context = this,
                    onActionDetected = { action ->
                        handleFaceAction(action)
                    },
                    onHeadDirection = { dx, dy ->
                        handleHeadDirection(dx, dy)
                    },
                    onInitFailed = { error ->
                        Log.e(TAG, "人脸识别模型初始化失败", error)
                        handleCameraFailure()
                    }
                )
                faceAnalyzer = analyzer
                analysis.setAnalyzer(cameraExecutor, analyzer)

                // ----- 选择摄像头：优先前置，不可用时用后置 -----
                val cameraSelector = try {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } catch (e: Exception) {
                    Log.w(TAG, "无前置摄像头，尝试后置", e)
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                // 先解绑所有用例
                cameraProvider?.unbindAll()

                try {
                    // 绑定到当前 Service 的 Lifecycle
                    cameraProvider?.bindToLifecycle(this, cameraSelector, analysis)
                    Log.d(TAG, "摄像头启动成功")
                } catch (e: IllegalArgumentException) {
                    // 前置失败，回退到后置
                    Log.w(TAG, "前置启动失败，回退后置摄像头")
                    cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                    Log.d(TAG, "摄像头启动成功(后置)")
                }

            } catch (e: Exception) {
                Log.e(TAG, "摄像头启动失败", e)
                handleCameraFailure()
            }
        }, ContextCompat.getMainExecutor(this))
    }

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
        stopSelf()
    }

    // ================================================================
    // 资源释放
    // ================================================================

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

        try {
            faceAnalyzer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭 FaceAnalyzer 时出错", e)
        } finally {
            faceAnalyzer = null
        }

        removeCrosshairOverlay()

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        hitTestExecutor.shutdown()
    }

    // ================================================================
    // 动作→手势 映射调度
    // ================================================================

    private fun handleFaceAction(action: FaceAnalyzer.FaceAction) {
        val service = FaceAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "FaceAccessibilityService 未启动，无法执行手势")
            return
        }

        // 闭眼超过 2 秒（LONG_BLINK）→ 进入/退出准心模式（进出同一姿势）
        if (action == FaceAnalyzer.FaceAction.LONG_BLINK) {
            toggleCrosshairMode()
            return
        }

        // ---- 准心模式内：单击=双眨眼；屏蔽滑动等其它手势 ----
        if (crosshairMode) {
            when (action) {
                // 单击：双眨眼（DOUBLE_BLINK）在光标处单击
                FaceAnalyzer.FaceAction.DOUBLE_BLINK -> {
                    Log.i(TAG, "准心模式 单击(双眨眼) @ ($cursorX,$cursorY)")
                    service.performSmartClick(cursorX, cursorY)
                }
                // 抬头/左右扭头 等在准心模式下忽略，避免误触
                else -> {
                    Log.d(TAG, "准心模式中忽略动作: $action")
                }
            }
            return
        }

        // ---- 非准心模式：正常手势路由 ----
        // 每次动作前刷新屏幕尺寸（供 px/py 换算）；朝向路由使用显示监听判定的 isDevicePortrait，避免滞后
        updateScreenSize()
        val isPortrait = isDevicePortrait

        Log.d(TAG, "收到动作: $action 横屏=${(!isPortrait)}")
        if (isPortrait) {
            handlePortraitMode(action, service)
        } else {
            handleLandscapeMode(action, service)
        }
    }

    // ================================================================
    // 准心模式
    // ================================================================

    /**
     * 切换准心模式：由「闭眼超过 2 秒」(LONG_BLINK) 触发，进入/退出为同一姿势。
     * 准心模式内：双眨眼(DOUBLE_BLINK)=单击。
     * 注意：进入后不再关闭 nonBlinkActionsEnabled / longBlinkEnabled，
     * 否则准心模式内的双眨眼(单击)/长闭眼(退出)无法被识别。
     */
    private fun toggleCrosshairMode() {
        crosshairMode = !crosshairMode
        if (crosshairMode) {
            // 光标起始位置：上次选定的位置，否则屏幕中心
            cursorX = if (selectedX > 0) selectedX else screenWidth / 2f
            cursorY = if (selectedY > 0) selectedY else screenHeight / 2f
            mainHandler.post {
                showCrosshairOverlay()
                crosshairView?.updateTarget(cursorX, cursorY)
                Toast.makeText(
                    this,
                    "准心模式：偏头移动光标 · 双眨眼=单击 · 闭眼2秒=退出",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            mainHandler.post {
                removeCrosshairOverlay()
                if (selectedX > 0 && selectedY > 0) {
                    Toast.makeText(this, "已退出准心模式", Toast.LENGTH_SHORT).show()
                }
            }
        }
        Log.i(TAG, "准心模式: $crosshairMode")
    }

    /**
     * 鼻尖位置回调（任意线程），换算为屏幕像素后更新光标。
     * dx/dy 已由 FaceAnalyzer 在“显示坐标系”下给出（CameraX targetRotation 同步保证了这一点），
     * 因此横竖屏无需额外旋转，光标方向与设备朝向自动一致。
     */
    private fun handleHeadDirection(dx: Float, dy: Float) {
        if (!crosshairMode) return
        if (!dx.isFinite() || !dy.isFinite()) return
        // 增量移动：方向向量 × 每帧步长，并夹在屏幕边距内，光标不越界
        cursorX = (cursorX + dx * CROSSHAIR_STEP_PX)
            .coerceIn(CROSSHAIR_MARGIN, screenWidth - CROSSHAIR_MARGIN)
        cursorY = (cursorY + dy * CROSSHAIR_STEP_PX)
            .coerceIn(CROSSHAIR_MARGIN, screenHeight - CROSSHAIR_MARGIN)
        crosshairView?.updateTarget(cursorX, cursorY)
        selectedX = cursorX
        selectedY = cursorY

        // 节流命中检测：更新「识别到的按钮」高亮
        val now = SystemClock.uptimeMillis()
        if (now - lastHitTestTime >= HIT_TEST_INTERVAL_MS) {
            lastHitTestTime = now
            performHitTestOverlay()
        }
    }

    /**
     * 命中检测：在后台线程遍历无障碍节点树，结果回主线程更新高亮。
     * 遍历较重（视频页节点很多），绝不能在主线程执行，否则光标会卡住。
     */
    private fun performHitTestOverlay() {
        if (!crosshairMode || hitTestInProgress) return
        val service = FaceAccessibilityService.instance ?: return
        val x = clickX()
        val y = clickY()
        hitTestInProgress = true
        hitTestExecutor.execute {
            try {
                val result = service.hitTest(x, y)
                mainHandler.post {
                    if (crosshairMode) {
                        crosshairView?.updateHitTest(result.clickTarget, result.longClickTarget)
                    }
                }
            } finally {
                hitTestInProgress = false
            }
        }
    }

    private fun showCrosshairOverlay() {
        if (crosshairView != null) return
        try {
            crosshairView = CrosshairOverlayView.createAndAttach(this, screenWidth, screenHeight)
        } catch (e: Exception) {
            Log.e(TAG, "显示准心悬浮窗失败", e)
        }
    }

    private fun removeCrosshairOverlay() {
        val view = crosshairView ?: return
        crosshairView = null
        view.removeFromWindow()
    }

    /** 当前点击/长按位置：用户选定过则用选定位置，否则用屏幕中心 */
    private fun clickX(): Float =
        if (selectedX > 0) selectedX else px(CLICK_X_CENTER)

    private fun clickY(): Float =
        if (selectedY > 0) selectedY else py(CLICK_Y_CENTER)

    // ================================================================
    // 方案一：竖屏手势映射
    // ================================================================
    private fun handlePortraitMode(
        action: FaceAnalyzer.FaceAction,
        service: FaceAccessibilityService
    ) {
        when (action) {
            // 低头 → 下滑
            FaceAnalyzer.FaceAction.HEAD_DOWN -> {
                service.performSwipeAction(
                    px(SWIPE_START_X_CENTER), py(SWIPE_START_Y_TOP),
                    px(SWIPE_END_X_CENTER), py(SWIPE_END_Y_BOTTOM)
                )
            }
            // 抬头 → 上滑
            FaceAnalyzer.FaceAction.LOOK_UP -> {
                service.performSwipeAction(
                    px(SWIPE_START_X_CENTER), py(SWIPE_START_Y_BOTTOM),
                    px(SWIPE_END_X_CENTER), py(SWIPE_END_Y_TOP)
                )
            }
            FaceAnalyzer.FaceAction.SHAKE_LEFT -> {
                service.performSwipeAction(
                    px(SWIPE_X_RIGHT), py(SWIPE_Y_CENTER),
                    px(SWIPE_X_LEFT), py(SWIPE_Y_CENTER)
                )
            }
            FaceAnalyzer.FaceAction.SHAKE_RIGHT -> {
                service.performSwipeAction(
                    px(SWIPE_X_LEFT), py(SWIPE_Y_CENTER),
                    px(SWIPE_X_RIGHT), py(SWIPE_Y_CENTER)
                )
            }
            FaceAnalyzer.FaceAction.MOUTH_OPEN -> {
                if (!service.performSmartLongClick(clickX(), clickY())) {
                    val (px, py) = service.resolveTargetPoint(clickX(), clickY())
                    pressPointX = px
                    pressPointY = py
                    service.startContinuousPress(px, py)
                }
            }
            FaceAnalyzer.FaceAction.MOUTH_CLOSE -> {
                service.stopContinuousPress(pressPointX, pressPointY)
            }
            else -> {}
        }
    }

    // ================================================================
    // 方案二：横屏手势映射
    // ================================================================
    private fun handleLandscapeMode(
        action: FaceAnalyzer.FaceAction,
        service: FaceAccessibilityService
    ) {
        when (action) {
            FaceAnalyzer.FaceAction.HEAD_DOWN -> {
                service.performSwipeAction(
                    px(SWIPE_START_X_CENTER), py(SWIPE_START_Y_TOP),
                    px(SWIPE_END_X_CENTER), py(SWIPE_END_Y_BOTTOM)
                )
            }
            FaceAnalyzer.FaceAction.LOOK_UP -> {
                service.performSwipeAction(
                    px(SWIPE_START_X_CENTER), py(SWIPE_START_Y_BOTTOM),
                    px(SWIPE_END_X_CENTER), py(SWIPE_END_Y_TOP)
                )
            }
            FaceAnalyzer.FaceAction.SHAKE_LEFT -> {
                service.performSwipeAction(
                    px(SWIPE_X_RIGHT), py(SWIPE_Y_CENTER),
                    px(SWIPE_X_LEFT), py(SWIPE_Y_CENTER)
                )
            }
            FaceAnalyzer.FaceAction.SHAKE_RIGHT -> {
                service.performSwipeAction(
                    px(SWIPE_X_LEFT), py(SWIPE_Y_CENTER),
                    px(SWIPE_X_RIGHT), py(SWIPE_Y_CENTER)
                )
            }
            FaceAnalyzer.FaceAction.MOUTH_OPEN -> {
                if (!service.performSmartLongClick(clickX(), clickY())) {
                    val (px, py) = service.resolveTargetPoint(clickX(), clickY())
                    pressPointX = px
                    pressPointY = py
                    service.startContinuousPress(px, py)
                }
            }
            FaceAnalyzer.FaceAction.MOUTH_CLOSE -> {
                service.stopContinuousPress(pressPointX, pressPointY)
            }
            else -> {}
        }
    }
}