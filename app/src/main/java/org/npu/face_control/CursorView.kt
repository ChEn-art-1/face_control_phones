package org.npu.face_control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class CursorView(context: Context) : View(context) {

    private val paint = Paint().apply {
        color = Color.parseColor("#00BFFF")
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val crossPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val centerX = 40f
    private val centerY = 40f
    private val radius = 30f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 外圈
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#00BFFF")
        canvas.drawCircle(centerX, centerY, radius, paint)

        // 中心点
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#00BFFF")
        canvas.drawCircle(centerX, centerY, 4f, paint)

        // 十字准星
        canvas.drawLine(centerX - 16, centerY, centerX + 16, centerY, crossPaint)
        canvas.drawLine(centerX, centerY - 16, centerX, centerY + 16, crossPaint)

        // 外圈小刻度
        for (i in 0..7) {
            val angle = Math.PI / 4 * i
            val startX = centerX + (radius - 4) * Math.cos(angle).toFloat()
            val startY = centerY + (radius - 4) * Math.sin(angle).toFloat()
            val endX = centerX + (radius - 10) * Math.cos(angle).toFloat()
            val endY = centerY + (radius - 10) * Math.sin(angle).toFloat()
            canvas.drawLine(startX, startY, endX, endY, crossPaint)
        }
    }
}