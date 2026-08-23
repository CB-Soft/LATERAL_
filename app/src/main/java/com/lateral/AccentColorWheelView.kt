package com.lateral

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** A compact HSV hue/saturation wheel with brightness fixed to the LATERAL_ accent level. */
class AccentColorWheelView(context: Context) : View(context) {
    var onColorChanged: ((hue: Float, saturation: Float, color: Int) -> Unit)? = null

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var wheelBitmap: Bitmap? = null
    private var wheelBitmapSize = 0
    private var hue = InputSettings.accentHue
    private var saturation = InputSettings.accentSaturation
    private var wheelRadius = 0f

    init {
        isClickable = true
        contentDescription = "Accent color wheel"
    }

    fun setSelection(hue: Float, saturation: Float) {
        this.hue = ((hue % 360f) + 360f) % 360f
        this.saturation = saturation.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val centerX = width / 2f
        val centerY = height / 2f
        wheelRadius = min(width, height) / 2f - dp(12)
        val size = (wheelRadius * 2f).toInt().coerceAtLeast(1)
        if (wheelBitmap == null || wheelBitmapSize != size) {
            wheelBitmap?.recycle()
            wheelBitmap = createWheel(size)
            wheelBitmapSize = size
        }
        wheelBitmap?.let {
            canvas.drawBitmap(it, centerX - wheelRadius, centerY - wheelRadius, wheelPaint)
        }

        val angle = Math.toRadians(hue.toDouble())
        val markerX = centerX + cos(angle).toFloat() * wheelRadius * saturation
        val markerY = centerY + sin(angle).toFloat() * wheelRadius * saturation
        markerPaint.style = Paint.Style.STROKE
        markerPaint.strokeWidth = dp(3).toFloat()
        markerPaint.color = Color.BLACK
        canvas.drawCircle(markerX, markerY, dp(9).toFloat(), markerPaint)
        markerPaint.strokeWidth = dp(2).toFloat()
        markerPaint.color = Color.WHITE
        canvas.drawCircle(markerX, markerY, dp(9).toFloat(), markerPaint)
        markerPaint.style = Paint.Style.FILL
        markerPaint.color = InputSettings.accentColor
        canvas.drawCircle(markerX, markerY, dp(5).toFloat(), markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val centerX = width / 2f
        val centerY = height / 2f
        val dx = event.x - centerX
        val dy = event.y - centerY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance > wheelRadius + dp(16)) return true

        val clampedDistance = distance.coerceAtMost(wheelRadius)
        hue = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 360f) % 360f)
        saturation = if (wheelRadius == 0f) 0f else clampedDistance / wheelRadius
        val color = Color.HSVToColor(floatArrayOf(hue, saturation, InputSettings.accentValue()))
        onColorChanged?.invoke(hue, saturation, color)
        invalidate()
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun createWheel(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val center = size / 2f
        val radius = center
        val value = InputSettings.accentValue()
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x + .5f - center
                val dy = y + .5f - center
                val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val index = y * size + x
                if (distance <= radius) {
                    val pixelHue = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 360f) % 360f
                    val pixelSaturation = (distance / radius).coerceIn(0f, 1f)
                    pixels[index] = Color.HSVToColor(floatArrayOf(pixelHue, pixelSaturation, value))
                } else {
                    pixels[index] = Color.TRANSPARENT
                }
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
