package com.lateral

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import com.lateral.beast.BeastTask

/** A compact, touch-first representation of the Beast task strip and its viewport. */
class WorkspaceNavigatorView(context: Context) : View(context) {
    private val accent get() = InputSettings.accentColor
    var onTaskSelected: ((Long) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tasks: List<BeastTask> = emptyList()
    private var focusedTaskId: Long? = null
    private var downX = 0f
    private var lastX = 0f
    private var tabDragging = false
    private var tabDragRemainderPx = 0f

    init {
        setBackgroundColor(Color.rgb(9, 10, 11))
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tasks.isEmpty()) {
            paint.typeface = android.graphics.Typeface.MONOSPACE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(11).toFloat()
            paint.color = Color.rgb(91, 104, 105)
            canvas.drawText("NO TASKS — OPEN APPS", width / 2f, dp(30).toFloat(), paint)
            return
        }
        val visibleTasks = visibleTasks()
        val itemWidth = width / visibleTasks.size.toFloat()
        val top = dp(5).toFloat()
        paint.typeface = android.graphics.Typeface.MONOSPACE
        paint.textAlign = Paint.Align.CENTER

        visibleTasks.forEachIndexed { index, task ->
            val center = itemWidth * (index + .5f)
            if (task.id == focusedTaskId) {
                paint.color = accent
                canvas.drawRect(center - itemWidth / 2 + dp(5), dp(2).toFloat(), center + itemWidth / 2 - dp(5), dp(4).toFloat(), paint)
            }
            paint.color = if (task.id == focusedTaskId) Color.rgb(232, 247, 248) else Color.rgb(142, 155, 156)
            paint.textSize = dp(12).toFloat()
            canvas.drawText("%02d".format(tabOffset + index + 1), center, top + dp(15), paint)
            paint.color = if (task.id == focusedTaskId) Color.rgb(164, 223, 229) else Color.rgb(98, 111, 112)
            paint.textSize = dp(11).toFloat()
            canvas.drawText(task.shortLabel.uppercase(), center, top + dp(31), paint)
        }
    }

    fun submitWorkspace(tasks: List<BeastTask>, focusedTaskId: Long?) {
        this.tasks = tasks
        this.focusedTaskId = focusedTaskId
        tabOffset = tabOffset.coerceIn(0, maxTabOffset())
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastX = event.x
                tabDragging = false
                tabDragRemainderPx = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = event.x - lastX
                if (abs(event.x - downX) > dp(5)) tabDragging = true
                if (tabDragging && abs(delta) > 0f) {
                    val step = tabCellWidth().coerceAtLeast(1f)
                    // Movement events are usually only a few pixels apart. Accumulate
                    // their distance so a real drag advances tabs even when no single
                    // sample crosses an entire cell width.
                    tabDragRemainderPx -= delta
                    val cells = (tabDragRemainderPx / step).toInt()
                    if (cells != 0) {
                        tabOffset = (tabOffset + cells).coerceIn(0, maxTabOffset())
                        tabDragRemainderPx -= cells * step
                    }
                    lastX = event.x
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (tabDragging) {
                    val step = tabCellWidth().coerceAtLeast(1f)
                    if (abs(tabDragRemainderPx) >= step * TAB_SWIPE_COMMIT_FRACTION) {
                        val direction = if (tabDragRemainderPx > 0f) 1 else -1
                        tabOffset = (tabOffset + direction).coerceIn(0, maxTabOffset())
                        invalidate()
                    }
                }
                if (!tabDragging && tasks.isNotEmpty()) {
                    val visibleTasks = visibleTasks()
                    val selected = (event.x / (width / visibleTasks.size.toFloat())).toInt()
                        .coerceIn(0, visibleTasks.lastIndex)
                    onTaskSelected?.invoke(visibleTasks[selected].id)
                    invalidate()
                }
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /**
     * A twelve-character task label needs a real minimum cell width on PhoneUI. Rather
     * than allowing adjacent labels to overlap, show as many leading task tabs as
     * can fit cleanly; the viewport control below remains the way to navigate the
     * full Beast strip.
     */
    private fun visibleTasks(): List<BeastTask> {
        if (tasks.isEmpty()) return emptyList()
        val count = visibleTaskCount()
        return tasks.drop(tabOffset).take(count)
    }

    private var tabOffset = 0

    private fun visibleTaskCount(): Int =
        (width / tabCellWidth().coerceAtLeast(1f)).toInt().coerceAtLeast(1).coerceAtMost(MAX_VISIBLE_TASKS)

    private fun tabCellWidth(): Float {
        paint.typeface = android.graphics.Typeface.MONOSPACE
        paint.textSize = dp(TASK_LABEL_TEXT_DP).toFloat()
        val labelWidth = paint.measureText("M".repeat(TASK_LABEL_CHARACTERS))
        return labelWidth + dp(TASK_LABEL_HORIZONTAL_PADDING_DP) * 2f
    }

    private fun maxTabOffset(): Int = (tasks.size - visibleTaskCount()).coerceAtLeast(0)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TASK_LABEL_CHARACTERS = 12
        const val TASK_LABEL_TEXT_DP = 11
        const val TASK_LABEL_HORIZONTAL_PADDING_DP = 8
        const val MAX_VISIBLE_TASKS = 8
        const val TAB_SWIPE_COMMIT_FRACTION = .20f
    }
}
