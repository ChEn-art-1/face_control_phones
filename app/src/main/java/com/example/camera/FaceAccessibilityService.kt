package com.example.camera

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
<<<<<<< HEAD
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手势执行结果回调
 * success: 是否执行成功
 * reason: 失败原因（成功时为 null）
 */
typealias GestureCallback = (success: Boolean, reason: String?) -> Unit

class FaceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FaceAccessibilityService"
        @Volatile
        var instance: FaceAccessibilityService? = null
    }

    // 所有手势状态的变更都通过 mainHandler.post 串行化到主线程执行，避免多线程竞争
    // （dispatchGesture 本身也要求在主线程调用）
    private val mainHandler = Handler(Looper.getMainLooper())

    // 用 AtomicBoolean 保证「检查 + 设置」的原子性，防止并发调用 start/stop 出现竞态
    private val isPressing = AtomicBoolean(false)

    // 只在主线程（mainHandler 的任务中）读写，天然线程安全，不需要额外加锁
    @Volatile
    private var currentStroke: GestureDescription.StrokeDescription? = null
=======
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class FaceAccessibilityService : AccessibilityService() {

    private var currentStroke: GestureDescription.StrokeDescription? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPressing = false
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

<<<<<<< HEAD
=======
    fun performClickAction(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // 开始持续按压
    fun startContinuousPress(x: Float, y: Float) {
        if (isPressing) return
        isPressing = true
        
        val path = Path().apply { moveTo(x, y) }
        // 初始按压
        currentStroke = GestureDescription.StrokeDescription(path, 0, 200, true)
        val gesture = GestureDescription.Builder().addStroke(currentStroke!!).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (isPressing) {
                    continuePress(x, y)
                }
            }
        }, null)
    }

    // 续期按压
    private fun continuePress(x: Float, y: Float) {
        if (!isPressing) return
        
        val path = Path().apply { moveTo(x, y) }
        currentStroke = currentStroke?.continueStroke(path, 0, 200, true)
        currentStroke?.let {
            val gesture = GestureDescription.Builder().addStroke(it).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.postDelayed({
                        if (isPressing) continuePress(x, y)
                    }, 10)
                }
            }, null)
        }
    }

    // 停止按压
    fun stopContinuousPress(x: Float, y: Float) {
        isPressing = false
        currentStroke?.let {
            val path = Path().apply { moveTo(x, y) }
            val lastStroke = it.continueStroke(path, 0, 100, false)
            val gesture = GestureDescription.Builder().addStroke(lastStroke).build()
            dispatchGesture(gesture, null, null)
            currentStroke = null
        }
    }

    fun performSwipeAction(startX: Float, startY: Float, endX: Float, endY: Float) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        var instance: FaceAccessibilityService? = null
    }

>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

<<<<<<< HEAD
    // ---------------------- 点击 ----------------------

    fun performClickAction(x: Float, y: Float, callback: GestureCallback? = null) {
        mainHandler.post {
            try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 100)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGestureWithCallback(gesture, callback)
            } catch (e: Exception) {
                Log.e(TAG, "performClickAction failed", e)
                callback?.invoke(false, e.message)
            }
        }
    }

    // ---------------------- 滑动 ----------------------

    fun performSwipeAction(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        callback: GestureCallback? = null
    ) {
        mainHandler.post {
            try {
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 300)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGestureWithCallback(gesture, callback)
            } catch (e: Exception) {
                Log.e(TAG, "performSwipeAction failed", e)
                callback?.invoke(false, e.message)
            }
        }
    }

    // ---------------------- 持续按压 ----------------------

    /**
     * 开始持续按压。
     * 用 AtomicBoolean.compareAndSet 保证并发调用时只有一个真正开始，其余直接失败返回，
     * 避免出现两条按压链路同时跑导致状态错乱。
     */
    fun startContinuousPress(x: Float, y: Float, callback: GestureCallback? = null) {
        if (!isPressing.compareAndSet(false, true)) {
            callback?.invoke(false, "already pressing")
            return
        }

        mainHandler.post {
            try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 200, true)
                currentStroke = stroke
                val gesture = GestureDescription.Builder().addStroke(stroke).build()

                val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        callback?.invoke(true, null)
                        if (isPressing.get()) {
                            continuePress(x, y)
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        callback?.invoke(false, "gesture cancelled")
                        resetPressState()
                    }
                }, null)

                if (!dispatched) {
                    callback?.invoke(false, "dispatchGesture returned false")
                    resetPressState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "startContinuousPress failed", e)
                callback?.invoke(false, e.message)
                resetPressState()
            }
        }
    }

    // 续期按压，只会在主线程中被调用（mainHandler 内部触发），天然串行，不需要额外加锁
    private fun continuePress(x: Float, y: Float) {
        if (!isPressing.get()) return
        val stroke = currentStroke ?: return

        try {
            val path = Path().apply { moveTo(x, y) }
            val nextStroke = stroke.continueStroke(path, 0, 200, true)
            currentStroke = nextStroke

            val gesture = GestureDescription.Builder().addStroke(nextStroke).build()
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    mainHandler.postDelayed({
                        if (isPressing.get()) continuePress(x, y)
                    }, 10)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    resetPressState()
                }
            }, null)

            if (!dispatched) {
                resetPressState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "continuePress failed", e)
            resetPressState()
        }
    }

    /**
     * 停止持续按压
     */
    fun stopContinuousPress(x: Float, y: Float, callback: GestureCallback? = null) {
        // 本来就没在按压，直接幂等返回成功
        if (!isPressing.compareAndSet(true, false)) {
            callback?.invoke(true, null)
            return
        }

        mainHandler.post {
            val stroke = currentStroke
            currentStroke = null

            if (stroke == null) {
                callback?.invoke(false, "no active stroke")
                return@post
            }

            try {
                val path = Path().apply { moveTo(x, y) }
                val lastStroke = stroke.continueStroke(path, 0, 100, false)
                val gesture = GestureDescription.Builder().addStroke(lastStroke).build()
                dispatchGestureWithCallback(gesture, callback)
            } catch (e: Exception) {
                Log.e(TAG, "stopContinuousPress failed", e)
                callback?.invoke(false, e.message)
            }
        }
    }

    private fun resetPressState() {
        isPressing.set(false)
        currentStroke = null
    }

    // ---------------------- 公共分发逻辑 ----------------------

    private fun dispatchGestureWithCallback(
        gesture: GestureDescription,
        callback: GestureCallback?
    ) {
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true, null)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false, "gesture cancelled")
            }
        }, null)

        if (!dispatched) {
            callback?.invoke(false, "dispatchGesture returned false")
        }
    }

    // ---------------------- 生命周期清理 ----------------------

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    /**
     * 统一清理逻辑：
     * 1. 移除所有未执行的 Handler 任务（尤其是 continuePress 的 postDelayed 循环），
     *    防止持有旧的 x、y、stroke 闭包引用，造成内存泄漏或在错误时机执行手势。
     * 2. 复位按压状态。
     * 3. 清空静态引用，避免外部持有已失效的 Service 实例。
     */
    private fun cleanup() {
        mainHandler.removeCallbacksAndMessages(null)
        isPressing.set(false)
        currentStroke = null
        instance = null
    }
=======
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
>>>>>>> e46afea8527987e1b8151d4c41ad95606d0f8fce
}