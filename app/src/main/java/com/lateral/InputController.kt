package com.lateral

import android.view.Display
import android.view.MotionEvent
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import com.lateral.beast.BeastInputRouter
import com.lateral.privileged.PrivilegedService
import kotlin.math.roundToInt

/** Phone-trackpad and raw Bluetooth/USB mouse input for the rendered Beast cursor. */
class InputController(private val onStatusUpdate: (String) -> Unit) {
    var cursorX = 0f
        private set
    var cursorY = 0f
        private set
    var sensitivity = 1.4f

    private var currentDisplay: Display? = null
    private var buttonState = 0
    private var pressedDisplay: Display? = null
    private var directPointerCaptured = false
    private var directPointerButton = 0
    private var coordinateWidth = 0f
    private var coordinateHeight = 0f
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingScrollDisplayId = -1
    private var pendingScrollX = 0
    private var pendingScrollY = 0
    private var pendingScrollAmount = 0f
    private var scrollFrameQueued = false
    private val flushScroll = Runnable {
        scrollFrameQueued = false
        val displayId = pendingScrollDisplayId
        val amount = pendingScrollAmount.coerceIn(-MAX_SCROLL_AXIS, MAX_SCROLL_AXIS)
        pendingScrollAmount = 0f
        if (displayId >= 0 && amount != 0f) {
            if (!BeastInputRouter.scroll(displayId, pendingScrollX, pendingScrollY, amount)) {
                PrivilegedService.scrollOnDisplay(
                    displayId,
                    pendingScrollX,
                    pendingScrollY,
                    amount * InputSettings.scrollSensitivity,
                )
            }
        }
    }

    fun setDisplay(display: Display?) {
        val previous = currentDisplay
        if (previous?.displayId != display?.displayId) {
            flushPendingScroll()
            cancelActiveButtons()
        }
        currentDisplay = display
        if (display != null && previous?.displayId != display.displayId) {
            val (width, height) = logicalBounds(display)
            coordinateWidth = width
            coordinateHeight = height
            cursorX = width / 2f
            cursorY = height / 2f
            publishCursor(display, width, height)
        }
    }

    fun handleEvent(event: TrackpadEvent) {
        val display = currentDisplay ?: return
        when (event) {
            is TrackpadEvent.Move -> moveBy(display, event.dx, event.dy)
            TrackpadEvent.LeftDown -> button(display, BTN_LEFT, true)
            TrackpadEvent.LeftUp -> button(display, BTN_LEFT, false)
            TrackpadEvent.LeftClick -> click(display, BTN_LEFT)
            TrackpadEvent.RightClick -> click(display, BTN_RIGHT)
            is TrackpadEvent.Scroll -> {
                // Edge scrolling behaves like a scrollbar while two-finger input
                // behaves like content scrolling. Each source can be inverted
                // independently in Input Settings.
                val baseDirection = if (event.source == TrackpadEvent.ScrollSource.EDGE) 1f else -1f
                val invert = if (event.source == TrackpadEvent.ScrollSource.EDGE) {
                    InputSettings.invertScrollbarScroll
                } else {
                    InputSettings.invertTwoFingerScroll
                }
                val direction = if (invert) -baseDirection else baseDirection
                scroll(display, event.dy * direction * TOUCHPAD_SCROLL_MULTIPLIER)
            }
            is TrackpadEvent.Pinch -> pinch(display, event.scale)
        }
    }

    /** Raw evdev deltas forwarded by the shell helper after it captures a physical mouse. */
    fun handleMouseDelta(dx: Int, dy: Int, wheel: Int) {
        val display = currentDisplay ?: return
        if (dx != 0 || dy != 0) {
            val (width, height) = syncLogicalBounds(display)
            // Match uxspace's raw-mouse calibration: roughly 800 HID ticks span
            // the workspace once, independent of Android's system cursor.
            moveBy(
                display,
                dx * width / RAW_MOUSE_REFERENCE_TICKS,
                dy * height / RAW_MOUSE_REFERENCE_TICKS,
            )
        }
        if (wheel != 0) scroll(display, wheel.toFloat())
    }

    fun handleMouseButton(code: Int, pressed: Boolean) {
        val display = currentDisplay ?: return
        if (code !in BTN_LEFT..BTN_MIDDLE) return
        button(display, code, pressed)
    }

    fun sendText(text: String) {
        val display = currentDisplay ?: return
        if (PrivilegedService.state == PrivilegedService.State.READY) {
            PrivilegedService.text(display.displayId, text)
        } else {
            onStatusUpdate("Pair the privileged input helper first")
        }
    }

    private fun moveBy(display: Display, dx: Float, dy: Float) {
        val (width, height) = syncLogicalBounds(display)
        val moveX = (dx * sensitivity).roundToInt()
        val moveY = (dy * sensitivity).roundToInt()
        if (moveX == 0 && moveY == 0) return
        cursorX = (cursorX + moveX).coerceIn(0f, width - 1f)
        cursorY = (cursorY + moveY).coerceIn(0f, height - 1f)
        publishCursor(display, width, height)
        if (buttonState != 0) {
            // A deliberate hold followed by movement is a real drag. Keep the
            // pressed pointer moving instead of only moving Beast's visual cursor.
            val (inputX, inputY) = inputPoint(display, width)
            if (directPointerCaptured) {
                BeastInputRouter.pointer(
                    display.displayId,
                    inputX,
                    inputY,
                    MotionEvent.ACTION_MOVE,
                    directPointerButton,
                    buttonState,
                )
            } else {
                PrivilegedService.injectMouse(
                    display.displayId,
                    inputX,
                    inputY,
                    MotionEvent.ACTION_MOVE,
                    buttonState,
                )
            }
        }
        onStatusUpdate("Beast cursor ${cursorX.toInt()}, ${cursorY.toInt()}")
    }

    private fun button(display: Display, code: Int, pressed: Boolean) {
        val androidButton = when (code) {
            BTN_LEFT -> MotionEvent.BUTTON_PRIMARY
            BTN_RIGHT -> MotionEvent.BUTTON_SECONDARY
            BTN_MIDDLE -> MotionEvent.BUTTON_TERTIARY
            else -> return
        }
        val oldButtonState = buttonState
        if (pressed && oldButtonState == 0) pressedDisplay = display
        val targetDisplay = pressedDisplay ?: display
        val (width, _) = if (targetDisplay.displayId == currentDisplay?.displayId) {
            syncLogicalBounds(targetDisplay)
        } else {
            logicalBounds(targetDisplay)
        }
        buttonState = if (pressed) oldButtonState or androidButton else oldButtonState and androidButton.inv()
        val (inputX, inputY) = inputPoint(targetDisplay, width)
        if (pressed && oldButtonState == 0) {
            directPointerCaptured = BeastInputRouter.pointer(
                targetDisplay.displayId,
                inputX,
                inputY,
                MotionEvent.ACTION_DOWN,
                androidButton,
                buttonState,
            )
            directPointerButton = if (directPointerCaptured) androidButton else 0
            if (!directPointerCaptured) {
                PrivilegedService.injectMouse(
                    targetDisplay.displayId,
                    inputX,
                    inputY,
                    MotionEvent.ACTION_DOWN,
                    buttonState,
                )
            }
        } else if (directPointerCaptured) {
            // Keep a captured stream on one hosted surface. Additional simultaneous
            // physical buttons are ignored rather than leaking a mismatched DOWN to
            // BeastActivity; the first released button ends the captured gesture.
            if (!pressed) {
                BeastInputRouter.pointer(
                    targetDisplay.displayId,
                    inputX,
                    inputY,
                    MotionEvent.ACTION_UP,
                    androidButton,
                    buttonState,
                )
            }
        } else {
            PrivilegedService.injectMouse(
                targetDisplay.displayId,
                inputX,
                inputY,
                if (pressed) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,
                buttonState,
            )
        }
        if (buttonState == 0) {
            pressedDisplay = null
            directPointerCaptured = false
            directPointerButton = 0
        }
        onStatusUpdate("Beast button $code ${if (pressed) "down" else "up"}")
    }

    /** End an in-flight stream on the display that received DOWN, never on a new display. */
    fun cancelActiveButtons() {
        val target = pressedDisplay ?: run {
            buttonState = 0
            directPointerCaptured = false
            directPointerButton = 0
            return
        }
        if (buttonState != 0) {
            val (width, _) = logicalBounds(target)
            val (inputX, inputY) = inputPoint(target, width)
            if (directPointerCaptured) {
                BeastInputRouter.pointer(
                    target.displayId,
                    inputX,
                    inputY,
                    MotionEvent.ACTION_CANCEL,
                    directPointerButton,
                    0,
                )
            } else {
                PrivilegedService.injectMouse(
                    target.displayId,
                    inputX,
                    inputY,
                    MotionEvent.ACTION_CANCEL,
                    0,
                )
            }
        }
        buttonState = 0
        pressedDisplay = null
        directPointerCaptured = false
        directPointerButton = 0
    }

    private fun click(display: Display, code: Int) {
        val (width, _) = syncLogicalBounds(display)
        val androidButton = androidButton(code) ?: return
        if (buttonState and androidButton != 0) return
        // Keep DOWN and UP in one helper operation. Previously two independent
        // worker submissions could leave DOWN waiting far longer than intended.
        val (inputX, inputY) = inputPoint(display, width)
        if (!BeastInputRouter.click(display.displayId, inputX, inputY, androidButton)) {
            PrivilegedService.clickMouse(
                display.displayId,
                inputX,
                inputY,
                androidButton,
            )
        }
        onStatusUpdate("Beast ${if (code == BTN_RIGHT) "right click" else "click"}")
    }

    private fun scroll(display: Display, amount: Float) {
        val (width, _) = syncLogicalBounds(display)
        if (amount == 0f) return
        if (pendingScrollDisplayId != -1 && pendingScrollDisplayId != display.displayId) flushPendingScroll()
        pendingScrollDisplayId = display.displayId
        val (inputX, inputY) = inputPoint(display, width)
        pendingScrollX = inputX
        pendingScrollY = inputY
        pendingScrollAmount += amount
        if (!scrollFrameQueued) {
            scrollFrameQueued = true
            mainHandler.postDelayed(flushScroll, SCROLL_BATCH_INTERVAL_MS)
        }
        onStatusUpdate("Beast scroll")
    }

    private fun pinch(display: Display, scale: Float) {
        val (width, height) = syncLogicalBounds(display)
        flushPendingScroll()
        val adjustedScale = adjustPinchScale(scale)
        val baseSpan = (minOf(width, height) * PINCH_BASE_SPAN_FRACTION).roundToInt()
        val targetSpan = (baseSpan * adjustedScale).roundToInt()
        val inputX = WorkspaceCursor.inputX(display.displayId, cursorX, width).roundToInt()
        if (BeastInputRouter.pinch(display.displayId, inputX, cursorY.roundToInt(), scale)) {
            onStatusUpdate("Beast ${if (adjustedScale > 1f) "zoom in" else "zoom out"}")
            return
        }
        PrivilegedService.pinchOnDisplay(
            display.displayId,
            inputX,
            cursorY.roundToInt(),
            baseSpan,
            targetSpan.coerceIn(PINCH_MIN_SPAN_PX, PINCH_MAX_SPAN_PX),
            PINCH_DURATION_MS,
        )
        onStatusUpdate("Beast ${if (adjustedScale > 1f) "zoom in" else "zoom out"}")
    }

    private fun adjustPinchScale(scale: Float): Float =
        (1f + (scale - 1f) * InputSettings.pinchZoomSensitivity)
            .coerceIn(MIN_PINCH_SCALE, MAX_PINCH_SCALE)

    private fun flushPendingScroll() {
        mainHandler.removeCallbacks(flushScroll)
        if (!scrollFrameQueued && pendingScrollAmount == 0f) return
        flushScroll.run()
    }

    private fun androidButton(code: Int): Int? = when (code) {
        BTN_LEFT -> MotionEvent.BUTTON_PRIMARY
        BTN_RIGHT -> MotionEvent.BUTTON_SECONDARY
        BTN_MIDDLE -> MotionEvent.BUTTON_TERTIARY
        else -> null
    }

    private fun publishCursor(display: Display, width: Float, height: Float) {
        WorkspaceCursor.moveTo(
            cursorX,
            cursorY,
            width,
            height,
        )
    }

    private fun inputPoint(display: Display, width: Float): Pair<Int, Int> =
        WorkspaceCursor.inputX(display.displayId, cursorX, width).roundToInt() to cursorY.roundToInt()

    private fun syncLogicalBounds(display: Display): Pair<Float, Float> {
        val (width, height) = logicalBounds(display)
        if (coordinateWidth > 0f && coordinateHeight > 0f &&
            (coordinateWidth != width || coordinateHeight != height)
        ) {
            cursorX = cursorX / coordinateWidth * width
            cursorY = cursorY / coordinateHeight * height
        }
        coordinateWidth = width
        coordinateHeight = height
        return width to height
    }

    @Suppress("DEPRECATION")
    private fun logicalBounds(display: Display): Pair<Float, Float> {
        WorkspaceCursor.viewport(display.displayId)?.let {
            return it.width.toFloat() to it.height.toFloat()
        }
        val size = Point()
        display.getRealSize(size)
        return size.x.coerceAtLeast(1).toFloat() to size.y.coerceAtLeast(1).toFloat()
    }

    private companion object {
        const val BTN_LEFT = 272
        const val BTN_RIGHT = 273
        const val BTN_MIDDLE = 274
        const val RAW_MOUSE_REFERENCE_TICKS = 800f
        const val TOUCHPAD_SCROLL_MULTIPLIER = .20f
        const val SCROLL_BATCH_INTERVAL_MS = 16L
        const val MAX_SCROLL_AXIS = 4f
        const val PINCH_BASE_SPAN_FRACTION = .18f
        const val PINCH_MIN_SPAN_PX = 96
        const val PINCH_MAX_SPAN_PX = 1_200
        const val PINCH_DURATION_MS = 32
        const val MIN_PINCH_SCALE = .5f
        const val MAX_PINCH_SCALE = 2f
    }
}
