package com.lateral.beast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import com.lateral.InputSettings
import com.lateral.MainActivity
import com.lateral.privileged.PrivilegedService
import java.util.concurrent.ConcurrentHashMap

/** Virtual displays outlive a physical Beast mode re-enumeration and accept a new surface. */
private object RetainedTaskDisplays {
    private val displays = ConcurrentHashMap<Long, Int>()
    fun retain(taskId: Long, displayId: Int) { displays[taskId] = displayId }
    fun take(taskId: Long): Int? = displays.remove(taskId)
}

/**
 * Content host for one Android task. TextureView is intentional: on the NX789J the
 * external-display hardware composer presents nested VirtualDisplay SurfaceViews as
 * black even though their producer queues contain live frames. A TextureView keeps the
 * same Surface contract while composing the buffer through BeastActivity's GPU layer.
 */
class TaskSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    var task: BeastTask? = null
    var onDisplayReady: ((Int) -> Unit)? = null
    var onAvailabilityChanged: ((Boolean) -> Unit)? = null
    var onTaskHosted: (() -> Unit)? = null
    var onRestoreFailed: ((PrivilegedService.FocusRestoreResult) -> Unit)? = null
    var onInteraction: (() -> Unit)? = null
    var onKeyboardRoutingUnavailable: (() -> Unit)? = null
    var targetDensityDpi: Int = BASE_DISPLAY_DPI
        set(value) {
            val next = value.coerceIn(MIN_DISPLAY_DPI, MAX_DISPLAY_DPI)
            if (field == next) return
            field = next
            if (isAvailable) resizeIfNeeded(width, height)
        }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayId: Int? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastDensityDpi = 0
    private var released = false
    private var launchSent = false
    private var routingBlocked = false
    private var displayCreationInFlight = false
    private var attempts = 0
    private var outputSurface: Surface? = null
    private var surfaceGeneration = 0
    private var renderedGeneration = -1
    private var frameRecoveryAttempts = 0
    private var secondaryMouseDown = false
    private var secondaryMouseX = 0
    private var secondaryMouseY = 0
    private var primaryMouseDown = false
    private var primaryMouseStreamed = false
    private var primaryMouseX = 0
    private var primaryMouseY = 0
    private var routedPrimaryTouch = false
    private var routedSecondaryClick = false
    private var routedPointerX = 0
    private var routedPointerY = 0
    private var editorProbeGeneration = 0

    init {
        surfaceTextureListener = this
        isOpaque = true
        isFocusable = true
        isFocusableInTouchMode = true
        setOnTouchListener { _, event -> forwardTouch(event) }
        setOnGenericMotionListener { _, event -> forwardGenericMotion(event) }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        val generation = ++surfaceGeneration
        renderedGeneration = -1
        frameRecoveryAttempts = 0
        outputSurface?.release()
        val surface = Surface(texture)
        outputSurface = surface
        onAvailabilityChanged?.invoke(true)
        displayId?.let {
            if (!PrivilegedService.ensureHostedDisplayImeRouting(it)) {
                rejectDisplayForImeRouting(it)
                return
            }
            PrivilegedService.setVirtualDisplaySurface(it, surface)
            if (!PrivilegedService.ensureHostedDisplayImeRouting(it)) {
                rejectDisplayForImeRouting(it)
                return
            }
            resizeIfNeeded(width, height)
        } ?: createDisplayWhenReady()
        scheduleFrameWatchdog(generation)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        resizeIfNeeded(width, height)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        surfaceGeneration++
        renderedGeneration = -1
        onAvailabilityChanged?.invoke(false)
        outputSurface?.release()
        outputSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        renderedGeneration = surfaceGeneration
        frameRecoveryAttempts = 0
    }

    private fun scheduleFrameWatchdog(generation: Int) {
        mainHandler.postDelayed({
            if (released || generation != surfaceGeneration || !isAvailable ||
                renderedGeneration == generation
            ) return@postDelayed
            recoverMissingFrame(generation)
        }, FRAME_WATCHDOG_MS)
    }

    /** Recover a producer that remained attached to a stale TextureView surface. */
    private fun recoverMissingFrame(generation: Int) {
        val id = displayId ?: run {
            createDisplayWhenReady()
            scheduleFrameWatchdog(generation)
            return
        }
        val surface = outputSurface?.takeIf(Surface::isValid) ?: return
        if (frameRecoveryAttempts++ < MAX_FRAME_REATTACH_ATTEMPTS) {
            if (!PrivilegedService.ensureHostedDisplayImeRouting(id)) {
                rejectDisplayForImeRouting(id)
                return
            }
            PrivilegedService.setVirtualDisplaySurface(id, surface)
            PrivilegedService.resizeVirtualDisplay(
                id, width.coerceAtLeast(1), height.coerceAtLeast(1), targetDensityDpi,
            )
            task?.androidTaskId?.let { taskId ->
                // Frame production recovery is background maintenance. It must never
                // re-front PhoneUI or install a phone touch guard.
                PrivilegedService.restoreRecentTaskOnDisplay(taskId, id) { }
            }
            scheduleFrameWatchdog(generation)
            return
        }

        // A verified display that cannot produce a frame after reattachment is stale.
        // Releasing it returns the exact Android task to the phone before a clean display
        // is created and the same task id is restored.
        PrivilegedService.releaseVirtualDisplay(id)
        displayId = null
        launchSent = false
        lastWidth = 0
        lastHeight = 0
        lastDensityDpi = 0
        attempts = 0
        createDisplayWhenReady()
        scheduleFrameWatchdog(generation)
    }

    /** Copies the current hosted app frame without including BeastUI chrome. */
    fun captureBitmap(): Bitmap? {
        if (!canCapture()) return null
        return runCatching { getBitmap() }.getOrNull()
    }

    fun canCapture(): Boolean = isAvailable && displayId != null && width > 0 && height > 0

    /** Routes a wheel frame directly to this app's virtual display when the cursor is over it. */
    fun routeScrollAt(globalX: Int, globalY: Int, amount: Float): Boolean {
        val id = displayId ?: return false
        val local = localPointForGlobal(globalX, globalY) ?: return false
        onInteraction?.invoke()
        PrivilegedService.scrollOnDisplayImmediate(
            id, local.first, local.second, amount * InputSettings.scrollSensitivity,
        )
        return true
    }

    /** Injects a genuine two-pointer gesture into the hosted display, bypassing Beast chrome. */
    fun routePinchAt(globalX: Int, globalY: Int, scale: Float): Boolean {
        val id = displayId ?: return false
        val local = localPointForGlobal(globalX, globalY) ?: return false
        onInteraction?.invoke()
        val adjustedScale = adjustPinchScale(scale)
        val baseSpan = (minOf(width, height) * PINCH_BASE_SPAN_FRACTION).toInt().coerceAtLeast(1)
        val targetSpan = (baseSpan * adjustedScale).toInt().coerceIn(PINCH_MIN_SPAN_PX, PINCH_MAX_SPAN_PX)
        PrivilegedService.pinchOnDisplay(
            id, local.first, local.second, baseSpan, targetSpan, PINCH_DURATION_MS,
        )
        return true
    }

    /** Bypass BeastActivity so hosted contextual windows receive this click without losing focus. */
    fun routeClickAt(globalX: Int, globalY: Int, button: Int): Boolean {
        val id = displayId ?: return false
        val local = localPointForGlobal(globalX, globalY) ?: return false
        onInteraction?.invoke()
        when (button) {
            MotionEvent.BUTTON_PRIMARY -> {
                PrivilegedService.clickTouch(id, local.first, local.second)
                scheduleEditorProbe(id)
            }
            MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY ->
                PrivilegedService.clickMouse(id, local.first, local.second, button)
            else -> return false
        }
        return true
    }

    /** A captured primary drag is a continuous touchscreen stream for live text selection. */
    fun routePointerAt(
        globalX: Int,
        globalY: Int,
        action: Int,
        button: Int,
        buttonState: Int,
    ): Boolean {
        val id = displayId ?: return routedPrimaryTouch || routedSecondaryClick
        val local = localPointForGlobal(
            globalX,
            globalY,
            requireInside = action == MotionEvent.ACTION_DOWN,
        )
        if (local != null) {
            routedPointerX = local.first
            routedPointerY = local.second
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (local == null) return false
                onInteraction?.invoke()
                when (button) {
                    MotionEvent.BUTTON_PRIMARY -> {
                        routedPrimaryTouch = true
                        routedSecondaryClick = false
                        PrivilegedService.injectTouch(
                            id, routedPointerX, routedPointerY, MotionEvent.ACTION_DOWN,
                        )
                    }
                    MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY -> {
                        routedPrimaryTouch = false
                        routedSecondaryClick = true
                    }
                    else -> return false
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (routedPrimaryTouch && buttonState and MotionEvent.BUTTON_PRIMARY != 0) {
                    PrivilegedService.injectTouch(
                        id, routedPointerX, routedPointerY, MotionEvent.ACTION_MOVE,
                    )
                    return true
                }
                return routedSecondaryClick
            }
            MotionEvent.ACTION_UP -> {
                if (routedPrimaryTouch) {
                    PrivilegedService.injectTouch(
                        id, routedPointerX, routedPointerY, MotionEvent.ACTION_UP,
                    )
                    scheduleEditorProbe(id)
                } else if (routedSecondaryClick) {
                    PrivilegedService.clickMouse(id, routedPointerX, routedPointerY, button)
                } else {
                    return false
                }
                routedPrimaryTouch = false
                routedSecondaryClick = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelRoutedPointer()
                return true
            }
        }
        return false
    }

    fun cancelRoutedPointer() {
        val id = displayId
        if (id != null && routedPrimaryTouch) {
            PrivilegedService.injectTouch(
                id, routedPointerX, routedPointerY, MotionEvent.ACTION_CANCEL,
            )
        }
        routedPrimaryTouch = false
        routedSecondaryClick = false
    }

    private fun localPointForGlobal(
        globalX: Int,
        globalY: Int,
        requireInside: Boolean = true,
    ): Pair<Int, Int>? {
        if (!isShown || !isAvailable || width <= 0 || height <= 0) return null
        val visible = Rect()
        if (!getGlobalVisibleRect(visible) || visible.width() <= 0 || visible.height() <= 0 ||
            (requireInside && !visible.contains(globalX, globalY))
        ) return null
        val x = ((globalX - visible.left).toFloat() / visible.width() * width)
            .toInt().coerceIn(0, width - 1)
        val y = ((globalY - visible.top).toFloat() / visible.height() * height)
            .toInt().coerceIn(0, height - 1)
        return x to y
    }

    private fun adjustPinchScale(scale: Float): Float =
        (1f + (scale - 1f) * InputSettings.pinchZoomSensitivity)
            .coerceIn(MIN_PINCH_SCALE, MAX_PINCH_SCALE)

    private fun createDisplayWhenReady() {
        val surface = outputSurface
        if (released || routingBlocked || displayCreationInFlight || surface == null ||
            !surface.isValid || displayId != null
        ) return
        val item = task ?: return
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        RetainedTaskDisplays.take(item.id)?.let { retainedId ->
            if (!PrivilegedService.ensureHostedDisplayImeRouting(retainedId)) {
                rejectDisplayForImeRouting(retainedId)
                return
            }
            displayId = retainedId
            launchSent = true
            lastWidth = w
            lastHeight = h
            PrivilegedService.setVirtualDisplaySurface(retainedId, surface)
            PrivilegedService.resizeVirtualDisplay(retainedId, w, h, targetDensityDpi)
            lastDensityDpi = targetDensityDpi
            if (!PrivilegedService.ensureHostedDisplayImeRouting(retainedId)) {
                rejectDisplayForImeRouting(retainedId)
                return
            }
            onDisplayReady?.invoke(retainedId)
            requestFocus()
            return
        }
        displayCreationInFlight = true
        val result = try {
            PrivilegedService.createHostedVirtualDisplay(
                "LATERAL_${item.id}_${item.shortLabel}", w, h, targetDensityDpi, surface,
                // Releasing the display reparents its task to display 0. This preserves both
                // imported and Beast-launched app stacks and lets Android restore phone metrics.
                destroyContentOnRemoval = false,
            )
        } finally {
            displayCreationInFlight = false
        }
        val id = when (result) {
            is PrivilegedService.HostedDisplayResult.Ready -> result.displayId
            PrivilegedService.HostedDisplayResult.KeyboardRoutingUnavailable -> {
                rejectDisplayForImeRouting(null)
                return
            }
            PrivilegedService.HostedDisplayResult.HelperUnavailable,
            PrivilegedService.HostedDisplayResult.DisplayCreationFailed -> {
                if (++attempts < MAX_ATTEMPTS) {
                    mainHandler.postDelayed(::createDisplayWhenReady, RETRY_MS)
                }
                return
            }
        }
        displayId = id
        lastWidth = w
        lastHeight = h
        lastDensityDpi = targetDensityDpi
        if (!launchSent) {
            if (!PrivilegedService.ensureHostedDisplayImeRouting(id)) {
                rejectDisplayForImeRouting(id)
                return
            }
            onDisplayReady?.invoke(id)
            launchSent = true
            val exactTaskId = item.androidTaskId
            val resumed = exactTaskId?.let { taskId ->
                val phoneTaskId = MainActivity.currentPhoneTaskId()
                val explicitMove = android.os.SystemClock.uptimeMillis() < item.beastRequestUntil
                if (phoneTaskId >= 0 && explicitMove) {
                    val result = PrivilegedService.restoreTaskPreservingPhoneFocus(
                        taskId, id, phoneTaskId,
                    )
                    if (result != PrivilegedService.FocusRestoreResult.SUCCESS) {
                        onRestoreFailed?.invoke(result)
                    }
                    result.targetRestored
                } else {
                    // Cold-start compatibility: do not duplicate an existing Android task
                    // merely because PhoneUI has not published its task id yet.
                    PrivilegedService.startRecentTaskOnDisplay(taskId, id)
                }
            } ?: false
            if (!resumed && exactTaskId == null) {
                PrivilegedService.launchApp(id, item.packageName, item.activityName)
                onTaskHosted?.invoke()
            } else if (resumed) {
                onTaskHosted?.invoke()
            }
        }
    }

    private fun resizeIfNeeded(width: Int, height: Int) {
        val id = displayId ?: return
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (w == lastWidth && h == lastHeight && targetDensityDpi == lastDensityDpi) return
        lastWidth = w
        lastHeight = h
        lastDensityDpi = targetDensityDpi
        PrivilegedService.resizeVirtualDisplay(id, w, h, targetDensityDpi)
        if (!PrivilegedService.ensureHostedDisplayImeRouting(id)) {
            rejectDisplayForImeRouting(id)
        }
    }

    private fun rejectDisplayForImeRouting(id: Int?) {
        id?.let { PrivilegedService.releaseVirtualDisplay(it) }
        displayId = null
        launchSent = false
        routingBlocked = true
        onKeyboardRoutingUnavailable?.invoke()
    }

    /** Called after helper recovery to recreate or re-verify the hosted display safely. */
    fun retryImeRouting() {
        if (released || displayCreationInFlight) return
        routingBlocked = false
        attempts = 0
        val id = displayId
        if (id != null && !PrivilegedService.ensureHostedDisplayImeRouting(id)) {
            rejectDisplayForImeRouting(id)
            return
        }
        if (id == null) createDisplayWhenReady()
    }

    private fun forwardTouch(event: MotionEvent): Boolean {
        val id = displayId ?: return true
        requestFocus()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onInteraction?.invoke()

        // Mouse secondary-button events must remain right clicks. Converting them
        // through the ordinary touch path silently turns them into left clicks.
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val secondaryPressed = event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
            if (event.actionMasked == MotionEvent.ACTION_DOWN && secondaryPressed) {
                secondaryMouseDown = true
                secondaryMouseX = event.x.toInt()
                secondaryMouseY = event.y.toInt()
                return true
            }
            if (secondaryMouseDown) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        PrivilegedService.clickMouse(
                            id, secondaryMouseX, secondaryMouseY, MotionEvent.BUTTON_SECONDARY,
                        )
                        secondaryMouseDown = false
                    }
                    MotionEvent.ACTION_CANCEL -> secondaryMouseDown = false
                }
                return true
            }

            val primaryPressed = event.buttonState and MotionEvent.BUTTON_PRIMARY != 0
            if (event.actionMasked == MotionEvent.ACTION_DOWN && primaryPressed) {
                primaryMouseDown = true
                primaryMouseStreamed = false
                primaryMouseX = event.x.toInt()
                primaryMouseY = event.y.toInt()
                return true
            }
            if (primaryMouseDown) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        if (!primaryMouseStreamed) {
                            PrivilegedService.injectTouch(
                                id, primaryMouseX, primaryMouseY, MotionEvent.ACTION_DOWN,
                            )
                            primaryMouseStreamed = true
                        }
                        PrivilegedService.injectTouch(
                            id, event.x.toInt(), event.y.toInt(), MotionEvent.ACTION_MOVE,
                        )
                    }
                    MotionEvent.ACTION_UP -> {
                        if (primaryMouseStreamed) {
                            PrivilegedService.injectTouch(
                                id, event.x.toInt(), event.y.toInt(), MotionEvent.ACTION_UP,
                            )
                        } else {
                            // A tap crosses the physical Beast display and this hosted
                            // virtual display. Pair the final DOWN/UP in one helper call.
                            PrivilegedService.clickTouch(id, event.x.toInt(), event.y.toInt())
                            scheduleEditorProbe(id)
                        }
                        primaryMouseDown = false
                        primaryMouseStreamed = false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (primaryMouseStreamed) {
                            PrivilegedService.injectTouch(
                                id, event.x.toInt(), event.y.toInt(), MotionEvent.ACTION_CANCEL,
                            )
                        }
                        primaryMouseDown = false
                        primaryMouseStreamed = false
                    }
                }
                return true
            }
        }
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_CANCEL
        }
        PrivilegedService.injectTouch(id, event.x.toInt(), event.y.toInt(), action)
        if (action == MotionEvent.ACTION_UP) {
            scheduleEditorProbe(id)
        }
        return true
    }

    private fun scheduleEditorProbe(displayId: Int) {
        val generation = ++editorProbeGeneration
        fun probe(attempt: Int) {
            if (released || generation != editorProbeGeneration || this.displayId != displayId) return
            PrivilegedService.queryFocusedEditor(displayId) { editor ->
                if (released || generation != editorProbeGeneration || this.displayId != displayId) {
                    return@queryFocusedEditor
                }
                if (editor != null) {
                    val item = task ?: return@queryFocusedEditor
                    HostedTextInputSession.begin(
                        HostedTextInputSession.Target(
                            displayId = displayId,
                            taskId = item.id,
                            androidTaskId = item.androidTaskId ?: item.id.toInt(),
                            packageName = item.packageName,
                            label = item.shortLabel.ifBlank { item.label },
                            inputType = editor.inputType,
                            imeOptions = editor.imeOptions,
                        ),
                    )
                } else {
                    if (attempt < EDITOR_PROBE_DELAYS_MS.lastIndex) {
                        mainHandler.postDelayed(
                            { probe(attempt + 1) },
                            EDITOR_PROBE_DELAYS_MS[attempt + 1],
                        )
                    } else {
                        HostedTextInputSession.close(displayId)
                    }
                }
            }
        }
        mainHandler.postDelayed({ probe(0) }, EDITOR_PROBE_DELAYS_MS[0])
    }

    private fun forwardGenericMotion(event: MotionEvent): Boolean {
        val id = displayId ?: return false
        if (secondaryMouseDown &&
            event.actionButton == MotionEvent.BUTTON_SECONDARY &&
            (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE)
        ) {
            return true
        }
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            onInteraction?.invoke()
            PrivilegedService.scrollOnDisplay(
                id,
                event.x.toInt(),
                event.y.toInt(),
                event.getAxisValue(MotionEvent.AXIS_VSCROLL) * InputSettings.scrollSensitivity,
            )
            return true
        }
        return false
    }

    fun release() {
        if (released) return
        released = true
        cancelRoutedPointer()
        mainHandler.removeCallbacksAndMessages(null)
        displayId?.let {
            HostedTextInputSession.close(it)
            PrivilegedService.releaseVirtualDisplay(it)
        }
        displayId = null
        outputSurface?.release()
        outputSurface = null
    }

    fun retainForReattach() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        val item = task
        val id = displayId
        id?.let(HostedTextInputSession::close)
        if (item != null && id != null) RetainedTaskDisplays.retain(item.id, id)
        displayId = null
        outputSurface?.release()
        outputSurface = null
    }

    fun sendKey(keyCode: Int): Boolean {
        val id = displayId ?: return false
        PrivilegedService.key(id, keyCode)
        return true
    }

    fun queryBackAvailability(callback: (Boolean) -> Unit) {
        val id = displayId
        if (id == null) {
            callback(false)
        } else {
            PrivilegedService.queryDisplayHasBackStack(id, callback)
        }
    }

    /**
     * Catches a root Back that would otherwise reveal the default home activity, then restores
     * the tracked app task to this display. The callback runs on the main thread.
     */
    fun catchBackExit(callback: (Boolean) -> Unit) {
        val id = displayId
        val item = task
        if (id == null || item == null) {
            callback(false)
            return
        }
        if (!PrivilegedService.ensureHostedDisplayImeRouting(id)) {
            rejectDisplayForImeRouting(id)
            callback(false)
            return
        }

        PrivilegedService.queryDisplayHasPackage(id, item.packageName) { present ->
            if (present) {
                callback(false)
                return@queryDisplayHasPackage
            }

            val recentTaskId = item.androidTaskId
            if (recentTaskId != null) {
                // Back recovery repairs only the hosted display. It is not a request to
                // take focus from whichever app the user selected on the phone.
                PrivilegedService.restoreRecentTaskOnDisplay(recentTaskId, id) {
                    mainHandler.postDelayed({ callback(true) }, RECOVERY_SETTLE_MS)
                }
            } else {
                PrivilegedService.launchApp(id, item.packageName, item.activityName)
                mainHandler.postDelayed({ callback(true) }, RECOVERY_SETTLE_MS)
            }
        }
    }

    companion object {
        private const val BASE_DISPLAY_DPI = 240
        private const val MIN_DISPLAY_DPI = 72
        private const val MAX_DISPLAY_DPI = 640
        private const val RETRY_MS = 250L
        private const val MAX_ATTEMPTS = 40
        private const val RECOVERY_SETTLE_MS = 500L
        private const val FRAME_WATCHDOG_MS = 1_500L
        private const val MAX_FRAME_REATTACH_ATTEMPTS = 2
        private const val PINCH_BASE_SPAN_FRACTION = .18f
        private const val PINCH_MIN_SPAN_PX = 96
        private const val PINCH_MAX_SPAN_PX = 1_200
        private const val PINCH_DURATION_MS = 32
        private const val MIN_PINCH_SCALE = .5f
        private const val MAX_PINCH_SCALE = 2f
        // A focused editor is commonly published within the first frame after the
        // injected tap. Probe quickly, then back off; the old 75→275 ms cadence made
        // the embedded keyboard feel needlessly late even when Floris was pre-warmed.
        private val EDITOR_PROBE_DELAYS_MS = longArrayOf(40L, 80L, 160L, 320L)
    }
}
