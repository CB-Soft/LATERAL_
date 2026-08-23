package com.lateral.beast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Draws the visible fraction of the task strip; the taskbar remains a true workspace map. */
class ViewportMapView(context: Context) : View(context) {
    var viewportWidthPx: Int = 0
        set(value) { field = value; invalidate() }
    var contentWidthPx: Int = 0
        set(value) { field = value; invalidate() }
    var scrollOffsetPx: Int = 0
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Color.rgb(46, 56, 57)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        if (contentWidthPx <= 0 || viewportWidthPx <= 0) return
        val visibleFraction = (viewportWidthPx.toFloat() / contentWidthPx).coerceIn(.04f, 1f)
        val indicatorWidth = width * visibleFraction
        val maxContentScroll = (contentWidthPx - viewportWidthPx).coerceAtLeast(1)
        val progress = (scrollOffsetPx.toFloat() / maxContentScroll).coerceIn(0f, 1f)
        val left = progress * (width - indicatorWidth)
        paint.color = Color.rgb(164, 211, 197)
        canvas.drawRect(left, 0f, left + indicatorWidth, height.toFloat(), paint)
    }
}
