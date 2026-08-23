package com.lateral

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.sqrt

sealed class TrackpadEvent {

    data class Move(
        val dx: Float,
        val dy: Float
    ) : TrackpadEvent()

    data object LeftClick : TrackpadEvent()
    data object RightClick : TrackpadEvent()

    data object LeftDown : TrackpadEvent()
    data object LeftUp : TrackpadEvent()

    data class Scroll(
        val dx: Float,
        val dy: Float,
        /** Edge scrolling behaves like moving a scrollbar; two fingers behave naturally. */
        val source: ScrollSource,
    ) : TrackpadEvent()

    enum class ScrollSource { EDGE, TWO_FINGER }

    /** Incremental scale update of a two-finger gesture at the Beast cursor position. */
    data class Pinch(val scale: Float) : TrackpadEvent()
}

class TrackpadView(
    context: Context
) : View(context) {

    var onMouseEvent: ((TrackpadEvent) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var lastX = 0f
    private var lastY = 0f

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    private var lastTwoFingerX = 0f
    private var lastTwoFingerY = 0f
    private var initialTwoFingerSpan = 0f
    private var lastTwoFingerSpan = 0f
    private var lastPinchDispatchSpan = 0f
    private var lastPinchDispatchAt = 0L
    private var twoFingerTravel = 0f
    private var twoFingerStartedAt = 0L
    private var twoFingerScrollActive = false
    private var twoFingerPinchActive = false

    private var dragging = false
    private var secondTapCandidate = false
    private var rightClickConsumed = false
    private var lastTapUpAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var twoFingerTap = false
    private var suppressTapForGesture = false
    private var suppressSingleFingerUntil = 0L

    private var lastMultiFingerTime = 0L
    private val multiFingerCooldown = 120L // ms

    private var isScrollMode = false
    private var isEdgeScrollMode = false
    private val edgeZoneWidthDp = 24f

    private var hasMoved = false
    private val viewConfiguration = ViewConfiguration.get(context)
    private val moveSlop = viewConfiguration.scaledTouchSlop.toFloat()
    private val tapDistance = viewConfiguration.scaledTouchSlop * 2f
    private val doubleTapSlop = viewConfiguration.scaledDoubleTapSlop.toFloat()
    private val tapTime = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTime = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val rightClickHoldTime = ViewConfiguration.getLongPressTimeout().toLong()

    private val tapSequenceExpiry = Runnable { clearTapSequence() }
    private val rightClickRunnable = Runnable {
        if (secondTapCandidate && !dragging && !isScrollMode && !rightClickConsumed) {
            secondTapCandidate = false
            rightClickConsumed = true
            clearFirstTap()
            onMouseEvent?.invoke(TrackpadEvent.RightClick)
        }
    }

    init {
        isClickable = true
        // PhoneUI's touch surface must never become a local focus target. MainActivity's
        // controller window is also normally non-focusable so touching it cannot dismiss
        // selection/action-mode UI owned by a hosted app on another display.
        isFocusable = false
        isFocusableInTouchMode = false
        keepScreenOn = true
        setBackgroundColor(0xFF0F1213.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val density = resources.displayMetrics.density
        val edgeWidth = edgeZoneWidthDp * density
        val edgeInset = 7f * density
        val outline = InputSettings.accentOutlineColor()

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(11, 14, 15)
        canvas.drawRoundRect(
            .5f,
            .5f,
            width - .5f,
            height - .5f,
            6f * density,
            6f * density,
            paint,
        )

        paint.color = Color.rgb(18, 26, 27)
        canvas.drawRect(0f, 0f, edgeWidth, height.toFloat(), paint)
        canvas.drawRect(width - edgeWidth, 0f, width.toFloat(), height.toFloat(), paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = Color.rgb(36, 49, 50)
        canvas.drawRoundRect(
            .5f,
            .5f,
            width - .5f,
            height - .5f,
            6f * density,
            6f * density,
            paint,
        )
        paint.color = outline
        canvas.drawLine(edgeWidth - edgeInset, edgeInset, edgeWidth - edgeInset, height - edgeInset, paint)
        canvas.drawLine(width - edgeWidth + edgeInset, edgeInset, width - edgeWidth + edgeInset, height - edgeInset, paint)
        paint.color = Color.rgb(55, 72, 73)
        canvas.drawLine(edgeWidth / 2f, edgeInset, edgeWidth / 2f, height - edgeInset, paint)
        canvas.drawLine(width - edgeWidth / 2f, edgeInset, width - edgeWidth / 2f, height - edgeInset, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(121, 145, 145)
        paint.textSize = 10f * density
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.CENTER
        canvas.save()
        canvas.rotate(-90f, edgeWidth / 2, height / 2f)
        canvas.drawText("SCROLL", edgeWidth / 2, height / 2f, paint)
        canvas.restore()
        canvas.save()
        canvas.rotate(90f, width - edgeWidth / 2, height / 2f)
        canvas.drawText("SCROLL", width - edgeWidth / 2, height / 2f, paint)
        canvas.restore()

        paint.color = Color.rgb(66, 88, 89)
        paint.textSize = 12f * density
        canvas.drawText("TOUCHPAD", width / 2f, height / 2f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        if (!isEnabled)
            return true

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                downX = event.x
                downY = event.y

                lastX = event.x
                lastY = event.y

                downTime = SystemClock.uptimeMillis()
                hasMoved = false
                isScrollMode = false
                suppressTapForGesture = downTime < suppressSingleFingerUntil
                rightClickConsumed = false

                val edgeWidth = edgeZoneWidthDp * resources.displayMetrics.density
                isEdgeScrollMode = event.x < edgeWidth || event.x > width - edgeWidth

                secondTapCandidate = !isEdgeScrollMode && !suppressTapForGesture &&
                    downTime - lastTapUpAt in 0..doubleTapTime &&
                    distance(lastTapX, lastTapY, event.x, event.y) <= doubleTapSlop
                removeCallbacks(tapSequenceExpiry)
                if (secondTapCandidate) {
                    postDelayed(rightClickRunnable, rightClickHoldTime)
                } else {
                    clearFirstTap()
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {

                removeCallbacks(rightClickRunnable)
                removeCallbacks(tapSequenceExpiry)
                clearFirstTap()
                secondTapCandidate = false
                rightClickConsumed = false
                val now = SystemClock.uptimeMillis()
                lastMultiFingerTime = now
                suppressSingleFingerUntil = now + multiFingerCooldown
                isScrollMode = true
                isEdgeScrollMode = false

                // A second finger cancels a pending/active drag immediately. This
                // prevents the transition into scrolling from leaving BTN_LEFT held.
                if (dragging) {
                    onMouseEvent?.invoke(TrackpadEvent.LeftUp)
                    dragging = false
                }

                if (event.pointerCount == 2) {
                    lastTwoFingerX = averageX(event)
                    lastTwoFingerY = averageY(event)
                    initialTwoFingerSpan = pointerSpan(event)
                    lastTwoFingerSpan = initialTwoFingerSpan
                    lastPinchDispatchSpan = initialTwoFingerSpan
                    lastPinchDispatchAt = 0L
                    twoFingerTravel = 0f
                    twoFingerStartedAt = now
                    twoFingerScrollActive = false
                    twoFingerPinchActive = false
                    twoFingerTap = true
                } else {
                    twoFingerScrollActive = false
                    twoFingerPinchActive = false
                    twoFingerTap = false
                }
            }

            MotionEvent.ACTION_MOVE -> {

                val dist = distance(downX, downY, event.x, event.y)
                if (rightClickConsumed && event.pointerCount == 1) return true

                if (event.pointerCount == 1 && secondTapCandidate && dist > moveSlop) {
                    removeCallbacks(rightClickRunnable)
                    secondTapCandidate = false
                    clearFirstTap()
                    dragging = true
                    onMouseEvent?.invoke(TrackpadEvent.LeftDown)
                }

                if (event.pointerCount == 1) {

                    if (isScrollMode) return true

                    // Ignore movement immediately after multi-finger lifting to prevent jumps
                    val timeSinceMulti = SystemClock.uptimeMillis() - lastMultiFingerTime
                    if (timeSinceMulti < multiFingerCooldown) {
                        lastX = event.x
                        lastY = event.y
                        return true
                    }

                    if (!hasMoved) {
                        if (dist > moveSlop) {
                            hasMoved = true
                        } else {
                            lastX = event.x
                            lastY = event.y
                            return true
                        }
                    }

                    val dx = event.x - lastX
                    val dy = event.y - lastY

                    lastX = event.x
                    lastY = event.y

                    if (isEdgeScrollMode) {
                        if (abs(dy) > 0.1f) {
                            onMouseEvent?.invoke(
                                TrackpadEvent.Scroll(0f, dy, TrackpadEvent.ScrollSource.EDGE)
                            )
                        }
                    } else {
                        if (abs(dx) > 0.1f || abs(dy) > 0.1f) {
                            onMouseEvent?.invoke(
                                TrackpadEvent.Move(dx, dy)
                            )
                        }
                    }

                } else if (event.pointerCount == 2) {
                    val averageX = averageX(event)
                    val averageY = averageY(event)
                    val dx = averageX - lastTwoFingerX
                    val dy = averageY - lastTwoFingerY
                    val span = pointerSpan(event)
                    val spanDelta = span - lastTwoFingerSpan
                    lastTwoFingerX = averageX
                    lastTwoFingerY = averageY
                    lastTwoFingerSpan = span
                    twoFingerTravel += sqrt(dx * dx + dy * dy)
                    val scrollSlop = TWO_FINGER_SCROLL_SLOP_DP * resources.displayMetrics.density
                    val pinchSlop = TWO_FINGER_PINCH_SLOP_DP * resources.displayMetrics.density
                    val translation = sqrt(dx * dx + dy * dy)
                    if (!twoFingerPinchActive && !twoFingerScrollActive &&
                        abs(span - initialTwoFingerSpan) >= pinchSlop &&
                        abs(spanDelta) > translation
                    ) {
                        twoFingerPinchActive = true
                        twoFingerTap = false
                    } else if (!twoFingerPinchActive && !twoFingerScrollActive && twoFingerTravel >= scrollSlop) {
                        // Drop the activation displacement so placing the second
                        // finger cannot create an initial scroll jump.
                        twoFingerScrollActive = true
                        twoFingerTap = false
                    } else if (twoFingerScrollActive && (abs(dx) > 0.1f || abs(dy) > 0.1f)) {
                        val maxStep = MAX_TWO_FINGER_STEP_DP * resources.displayMetrics.density
                        onMouseEvent?.invoke(
                            TrackpadEvent.Scroll(
                                dx.coerceIn(-maxStep, maxStep),
                                dy.coerceIn(-maxStep, maxStep),
                                TrackpadEvent.ScrollSource.TWO_FINGER,
                            ),
                        )
                    }
                    if (twoFingerPinchActive && lastPinchDispatchSpan > 0f) {
                        val now = SystemClock.uptimeMillis()
                        val incrementalScale = span / lastPinchDispatchSpan
                        if (now - lastPinchDispatchAt >= PINCH_UPDATE_INTERVAL_MS &&
                            abs(incrementalScale - 1f) >= MIN_PINCH_SCALE_DELTA
                        ) {
                            onMouseEvent?.invoke(TrackpadEvent.Pinch(
                                incrementalScale.coerceIn(MIN_INCREMENTAL_PINCH, MAX_INCREMENTAL_PINCH),
                            ))
                            lastPinchDispatchSpan = span
                            lastPinchDispatchAt = now
                        }
                    }
                    lastMultiFingerTime = SystemClock.uptimeMillis()
                } else if (event.pointerCount > 2) {
                    // Pause rather than averaging a third contact into the centroid;
                    // doing so would create a large artificial scroll sample.
                    twoFingerTap = false
                    lastMultiFingerTime = SystemClock.uptimeMillis()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val now = SystemClock.uptimeMillis()
                lastMultiFingerTime = now
                suppressSingleFingerUntil = now + multiFingerCooldown

                // Prevent cursor jump by resetting lastX/lastY to the remaining finger
                if (event.pointerCount == 2) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                } else if (event.pointerCount > 2) {
                    // Three-to-two transition: establish a fresh centroid and require
                    // the scroll slop again instead of emitting a discontinuity.
                    lastTwoFingerX = averageXExcluding(event, event.actionIndex)
                    lastTwoFingerY = averageYExcluding(event, event.actionIndex)
                    initialTwoFingerSpan = pointerSpan(event)
                    lastTwoFingerSpan = initialTwoFingerSpan
                    lastPinchDispatchSpan = initialTwoFingerSpan
                    lastPinchDispatchAt = 0L
                    twoFingerTravel = 0f
                    twoFingerScrollActive = false
                    twoFingerPinchActive = false
                    twoFingerTap = false
                }

                if (event.pointerCount == 2 && twoFingerPinchActive) {
                    val scale = if (lastPinchDispatchSpan > 0f) {
                        (lastTwoFingerSpan / lastPinchDispatchSpan)
                            .coerceIn(MIN_INCREMENTAL_PINCH, MAX_INCREMENTAL_PINCH)
                    } else 1f
                    if (abs(scale - 1f) >= MIN_FINAL_PINCH_SCALE_DELTA) {
                        onMouseEvent?.invoke(TrackpadEvent.Pinch(scale))
                    }
                } else if (event.pointerCount == 2 && twoFingerTap && !twoFingerScrollActive && !hasMoved) {
                    val elapsed = now - twoFingerStartedAt
                    val totalElapsed = now - downTime
                    val scrollSlop = TWO_FINGER_SCROLL_SLOP_DP * resources.displayMetrics.density
                    if (elapsed in MIN_TWO_FINGER_TAP_MS..tapTime &&
                        totalElapsed < tapTime && twoFingerTravel < scrollSlop
                    ) {
                        onMouseEvent?.invoke(TrackpadEvent.RightClick)
                    }
                }
                twoFingerTap = false
                twoFingerPinchActive = false
            }

            MotionEvent.ACTION_UP -> {

                removeCallbacks(rightClickRunnable)

                val elapsed =
                    SystemClock.uptimeMillis() - downTime

                val distance =
                    distance(
                        downX,
                        downY,
                        event.x,
                        event.y
                    )

                val validTap =
                    !isScrollMode &&
                    !isEdgeScrollMode &&
                    !suppressTapForGesture &&
                    elapsed < tapTime &&
                    distance < tapDistance

                if (dragging) {
                    onMouseEvent?.invoke(
                        TrackpadEvent.LeftUp
                    )
                    dragging = false
                    clearTapSequence()
                } else if (rightClickConsumed) {
                    clearTapSequence()
                } else if (validTap) {
                    onMouseEvent?.invoke(TrackpadEvent.LeftClick)
                    if (secondTapCandidate) {
                        clearTapSequence()
                    } else {
                        armFirstTap(SystemClock.uptimeMillis(), event.x, event.y)
                    }
                } else {
                    clearTapSequence()
                }

                isScrollMode = false
                isEdgeScrollMode = false
                twoFingerScrollActive = false
                twoFingerPinchActive = false
                twoFingerTap = false
                secondTapCandidate = false
                rightClickConsumed = false
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelActiveGesture()
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun averageX(event: MotionEvent): Float {

        var total = 0f

        for (i in 0 until event.pointerCount)
            total += event.getX(i)

        return total / event.pointerCount
    }

    private fun averageY(event: MotionEvent): Float {

        var total = 0f

        for (i in 0 until event.pointerCount)
            total += event.getY(i)

        return total / event.pointerCount
    }

    /** Cancel timers and release a primary stream before display/lifecycle transitions. */
    fun cancelActiveGesture() {
        removeCallbacks(rightClickRunnable)
        removeCallbacks(tapSequenceExpiry)
        if (dragging) onMouseEvent?.invoke(TrackpadEvent.LeftUp)
        dragging = false
        secondTapCandidate = false
        rightClickConsumed = false
        isScrollMode = false
        isEdgeScrollMode = false
        twoFingerScrollActive = false
        twoFingerPinchActive = false
        twoFingerTap = false
        clearFirstTap()
    }

    override fun onDetachedFromWindow() {
        cancelActiveGesture()
        super.onDetachedFromWindow()
    }

    private fun armFirstTap(upAt: Long, x: Float, y: Float) {
        lastTapUpAt = upAt
        lastTapX = x
        lastTapY = y
        removeCallbacks(tapSequenceExpiry)
        postDelayed(tapSequenceExpiry, doubleTapTime)
    }

    private fun clearTapSequence() {
        removeCallbacks(rightClickRunnable)
        removeCallbacks(tapSequenceExpiry)
        secondTapCandidate = false
        clearFirstTap()
    }

    private fun clearFirstTap() {
        lastTapUpAt = 0L
        lastTapX = 0f
        lastTapY = 0f
    }

    private fun averageXExcluding(event: MotionEvent, excludedIndex: Int): Float {
        var total = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludedIndex) continue
            total += event.getX(i)
            count++
        }
        return if (count == 0) 0f else total / count
    }

    private fun averageYExcluding(event: MotionEvent, excludedIndex: Int): Float {
        var total = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludedIndex) continue
            total += event.getY(i)
            count++
        }
        return if (count == 0) 0f else total / count
    }

    private fun pointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return distance(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
    }

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {

        val dx = x2 - x1
        val dy = y2 - y1

        return sqrt(
            dx * dx + dy * dy
        )
    }

    private companion object {
        const val TWO_FINGER_SCROLL_SLOP_DP = 8f
        const val TWO_FINGER_PINCH_SLOP_DP = 10f
        const val MAX_TWO_FINGER_STEP_DP = 24f
        const val MIN_TWO_FINGER_TAP_MS = 40L
        const val MIN_PINCH_SCALE_DELTA = .04f
        const val MIN_FINAL_PINCH_SCALE_DELTA = .015f
        const val PINCH_UPDATE_INTERVAL_MS = 40L
        const val MIN_INCREMENTAL_PINCH = .75f
        const val MAX_INCREMENTAL_PINCH = 1.33f
    }
}
