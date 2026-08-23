package com.lateral

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/** Compact horizontal viewport control placed on the lower edge of the PhoneUI touchpad. */
class WorkspaceViewportSliderView(context: Context) : View(context) {
    var onViewportChanged: ((Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var viewportOffset = .28f
    private var dragging = false

    init {
        isClickable = true
        setBackgroundColor(Color.rgb(9, 10, 11))
    }

    fun setViewportPosition(position: Float) {
        viewportOffset = position.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = dp(14).toFloat()
        val trackY = dp(9).toFloat()
        val trackEnd = (width - side).coerceAtLeast(side)
        val trackWidth = (trackEnd - side).coerceAtLeast(1f)
        val thumbWidth = (trackWidth * .36f).coerceAtLeast(dp(42).toFloat())
        val available = (trackWidth - thumbWidth).coerceAtLeast(1f)
        val thumbLeft = side + available * viewportOffset

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(31, 42, 43)
        canvas.drawRect(side, trackY - dp(1), trackEnd, trackY + dp(1), paint)

        paint.color = InputSettings.accentColor
        canvas.drawRect(side, trackY - dp(1), thumbLeft, trackY + dp(1), paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = InputSettings.accentOutlineColor()
        canvas.drawRoundRect(
            RectF(thumbLeft, dp(4).toFloat(), thumbLeft + thumbWidth, dp(14).toFloat()),
            dp(2).toFloat(),
            dp(2).toFloat(),
            paint,
        )

        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(9).toFloat()
        paint.color = InputSettings.accentColor
        canvas.drawText("BEAST VIEW", width / 2f, height - dp(3).toFloat(), paint)

        paint.color = Color.rgb(37, 48, 49)
        canvas.drawRect(0f, 0f, width.toFloat(), dp(1).toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) updateFromX(event.x)
                dragging = false
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromX(x: Float) {
        val side = dp(14).toFloat()
        val trackWidth = (width - side * 2f).coerceAtLeast(1f)
        val thumbWidth = (trackWidth * .36f).coerceAtLeast(dp(42).toFloat())
        val available = (trackWidth - thumbWidth).coerceAtLeast(1f)
        viewportOffset = ((x - side - thumbWidth / 2f) / available).coerceIn(0f, 1f)
        onViewportChanged?.invoke(viewportOffset)
        invalidate()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
