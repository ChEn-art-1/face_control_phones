package org.npu.face_control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * 准心调整悬浮窗 — 进入「准心模式」时显示
 *
 * 用途：让用户决定点击/长按的位置。进入模式后显示十字光标（随偏头方向移动），
 * 同时实时高亮命中目标：绿色框 = 点击将命中，橙色框 = 长按将命中。
 *
 * 仅作视觉指示，不拦截任何触摸/手势（FLAG_NOT_TOUCHABLE）。
 */
class CrosshairOverlayView(
    context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int
) : View(context) {

    /** 光标目标位置（屏幕像素坐标），-1 表示尚未有数据 */
    @Volatile private var targetX = -1f
    @Volatile private var targetY = -1f

    /** 平滑显示位置 */
    private var displayX = -1f
    private var displayY = -1f

    /** 平滑系数：数值越小越跟手（0~1） */
    private val smoothing = 0.25f

    /** 命中目标矩形（屏幕坐标，主线程更新） */
    @Volatile private var clickTarget: Rect? = null
    @Volatile private var longClickTarget: Rect? = null

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 255, 120)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 255, 120)
        style = Paint.Style.FILL
    }

    // 点击命中目标：绿色高亮
    private val clickStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 0, 255, 80)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val clickFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 255, 80)
        style = Paint.Style.FILL
    }
    // 长按命中目标：橙色高亮
    private val longClickStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 140, 0)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val longClickFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 140, 0)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
    }
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val screenLoc = IntArray(2)

    /**
     * 更新光标目标位置（可在任意线程调用）
     */
    fun updateTarget(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        targetX = x.coerceIn(0f, screenWidth.toFloat())
        targetY = y.coerceIn(0f, screenHeight.toFloat())
        postInvalidate()
    }

    /**
     * 更新命中目标（主线程调用）
     */
    fun updateHitTest(click: Rect?, longClick: Rect?) {
        clickTarget = click
        longClickTarget = longClick
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (targetX > 0 && targetY > 0) {
            if (displayX < 0) { displayX = targetX; displayY = targetY }
            displayX += (targetX - displayX) * smoothing
            displayY += (targetY - displayY) * smoothing
        }

        if (displayX < 0 || displayY < 0) return

        // 关键：把 canvas 坐标系对齐到「屏幕坐标系」。
        // 悬浮窗在不同设备/系统栏高度下，窗口原点可能不在屏幕 (0,0)，
        // 直接用屏幕坐标绘制会导致光标与高亮框整体偏移。
        getLocationOnScreen(screenLoc)
        canvas.save()
        canvas.translate(-screenLoc[0].toFloat(), -screenLoc[1].toFloat())

        val cx = displayX
        val cy = displayY

        // 高亮命中目标（先画，光标十字在最上层）
        clickTarget?.let {
            canvas.drawRect(it, clickFillPaint)
            canvas.drawRect(it, clickStrokePaint)
        }
        longClickTarget?.let {
            canvas.drawRect(it, longClickFillPaint)
            canvas.drawRect(it, longClickStrokePaint)
        }

        val r = 22f
        // 十字
        canvas.drawLine(cx - r, cy, cx + r, cy, crossPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, crossPaint)
        // 中心圆点
        canvas.drawCircle(cx, cy, 8f, dotPaint)
        // 外圈
        canvas.drawCircle(cx, cy, r, crossPaint)

        // 坐标标签
        val label = "(${cx.roundToInt()}, ${cy.roundToInt()})"
        val padX = 12f
        val padY = 8f
        val textWidth = textPaint.measureText(label)
        val labelX = cx + r + 14f
        val labelY = cy - r - 10f

        val bx = if (labelX + textWidth + padX * 2 > screenWidth) {
            cx - r - 14f - textWidth - padX * 2
        } else {
            labelX
        }
        val by = if (labelY - padY - textPaint.textSize < 0) {
            cy + r + 14f
        } else {
            labelY
        }

        canvas.drawRoundRect(
            bx, by - textPaint.textSize - padY,
            bx + textWidth + padX * 2, by + padY,
            8f, 8f, textBgPaint
        )
        canvas.drawText(label, bx + padX, by, textPaint)

        canvas.restore()
    }

    companion object {
        fun createAndAttach(context: Context, screenW: Int, screenH: Int): CrosshairOverlayView {
            val view = CrosshairOverlayView(context, screenW, screenH)
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                // FLAG_LAYOUT_IN_SCREEN / NO_LIMITS：窗口坐标系对齐整个屏幕（含状态栏）
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                format = PixelFormat.TRANSLUCENT
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(view, params)
            return view
        }
    }

    /**
     * 从窗口移除（须在主线程调用）
     */
    fun removeFromWindow() {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(this)
        } catch (e: Exception) {
            // 忽略：view 可能已被移除
        }
    }
}
