package com.lateral.beast

import android.content.Context
import android.app.ActivityOptions
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.hardware.display.DisplayManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.graphics.Rect
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.OverScroller
import android.text.TextUtils
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.atomic.AtomicInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import androidx.appcompat.app.AppCompatActivity
import com.lateral.SimpleTextWatcher
import com.lateral.BarAlignment
import com.lateral.InputSettings
import com.lateral.InputSettingsPanel
import com.lateral.MainActivity
import com.lateral.WorkspaceCursor
import com.lateral.TransientPanelCoordinator
import com.lateral.privileged.PrivilegedService

/** The external-display workspace: an ordered one-dimensional strip of Android tasks. */
class BeastActivity : AppCompatActivity(), DisplayManager.DisplayListener, BeastInputRouter.Handler {
    companion object {
        const val ACTION_ENSURE_WORKSPACE = "com.lateral.action.ENSURE_BEAST_WORKSPACE"
        const val ACTION_SHOW_LAUNCHER = "com.lateral.action.SHOW_BEAST_LAUNCHER"
        private const val MOMENTUM_DELAY_MS = 70L
        private const val MIN_FLING_VELOCITY = 120f
        private const val SHOW_ALL_BATCH_TIMEOUT_MS = 6_000L
        private const val TASKBAR_TEXT_SIZE = 11f
        private const val TOOLBAR_TEXT_SIZE = 11f
        private const val TASKBAR_LABEL_CHARACTERS = 12
        private const val TASKBAR_HEIGHT_DP = 44
        private const val TASKBAR_STEP_CONTROL_WIDTH_DP = 28
        private const val TASKBAR_SCROLL_STEP_FRACTION = .68f
        private const val SYSTEM_STATUS_INTERVAL_MS = 1_000L
        private const val APP_DECORATOR_HEIGHT_DP = 32
        private const val APP_DECORATOR_MIN_WIDTH_DP = 300
        private const val TAG = "Lateral/Beast"
        private val BACKGROUND = Color.rgb(7, 8, 9)
        private val ACCENT get() = InputSettings.accentColor
        private val activeInstances = AtomicInteger(0)
        private val resumedInstances = AtomicInteger(0)
        @Volatile private var visibleDisplayId = -1

        fun isWorkspaceActive(): Boolean = activeInstances.get() > 0
        fun isWorkspaceVisible(): Boolean = resumedInstances.get() > 0
        fun workspaceDisplayId(): Int = visibleDisplayId

        /** Finds the active physical presentation display, preferring VITURE when present. */
        fun findExternalDisplay(context: Context): Display? {
            val displays = context.getSystemService(DisplayManager::class.java).displays
            return displays.firstOrNull { display ->
                display.displayId != Display.DEFAULT_DISPLAY &&
                    display.state == Display.STATE_ON &&
                    display.name.contains("VITURE Beast", ignoreCase = true)
            } ?: displays.firstOrNull { display ->
                display.displayId != Display.DEFAULT_DISPLAY &&
                    display.state == Display.STATE_ON &&
                    !display.name.startsWith("LATERAL_")
            }
        }
    }

    private lateinit var root: FrameLayout
    /** Scales rendered content, including the cursor, but leaves Android's logical input grid intact. */
    private lateinit var aspectLayer: FrameLayout
    private lateinit var workspaceScroll: HorizontalScrollView
    private lateinit var taskStrip: LinearLayout
    private lateinit var toolbar: FrameLayout
    private lateinit var taskbar: FrameLayout
    private lateinit var taskbarScroll: HorizontalScrollView
    private lateinit var taskbarContent: LinearLayout
    private lateinit var taskTabs: LinearLayout
    private lateinit var taskbarPrevious: TextView
    private lateinit var taskbarNext: TextView
    private lateinit var taskbarShell: LinearLayout
    private lateinit var viewportMap: ViewportMapView
    private lateinit var fullscreenLayer: FrameLayout
    private lateinit var launcher: FrameLayout
    private lateinit var launcherPanel: LinearLayout
    private lateinit var launcherQuery: EditText
    private lateinit var launcherResults: LinearLayout
    private lateinit var launcherScroll: ScrollView
    private lateinit var cursorOverlay: BeastCursorOverlay
    private lateinit var systemStatus: TextView
    private val scrollMomentum by lazy { OverScroller(this) }
    private var momentumVelocity = 0f
    private var lastScrollAt = 0L
    private var momentumTargetsLauncher = false
    private val controlDebouncer = BeastControlDebouncer()
    private val screenshotController by lazy {
        BeastScreenshotController(
            activity = this,
            focusedAppBitmap = {
                cards[WorkspaceState.focusedTaskId]?.captureBitmap()
            },
            onResult = ::onScreenshotResult,
        )
    }
    private val launcherPreferences by lazy { LauncherPreferences(applicationContext) }
    private lateinit var displayManager: DisplayManager
    private var uiFontScale = 1f
    private var uiElementScale = 1f
    private var countedResumed = false
    private var launcherLease: TransientPanelCoordinator.Lease? = null
    private var launcherSubmitSequence = 0L
    private var beastHoverTarget: View? = null
    private var hoverUpdateQueued = false
    private var routedPointerCard: BeastTaskCard? = null
    private var routedPointerButton = 0
    private val hoverUpdateFrame = Runnable {
        hoverUpdateQueued = false
        updateBeastHoverAtCursor()
    }
    private val pendingShowAllTaskIds = linkedSetOf<Long>()
    private val showAllTimeout = Runnable(::finishShowAllBatch)
    private val systemStatusTicker = object : Runnable {
        override fun run() {
            if (::systemStatus.isInitialized) systemStatus.text = systemStatusText()
            if (::root.isInitialized) root.postDelayed(this, SYSTEM_STATUS_INTERVAL_MS)
        }
    }

    private val cards = linkedMapOf<Long, BeastTaskCard>()
    private var apps: List<ResolveInfo> = emptyList()
    private var filteredApps: List<ResolveInfo> = emptyList()
    private var recentPackageRank: Map<String, Int> = emptyMap()
    private val stateListener: () -> Unit = {
        runOnUiThread {
            renderWorkspace(WorkspaceState.consumeRevealFocusedTaskRequest())
            if (::launcher.isInitialized && launcher.visibility == View.VISIBLE) {
                populateLauncher(launcherQuery.text.toString())
            }
        }
    }
    private val appearanceListener: () -> Unit = { runOnUiThread(::applyAppearance) }
    private val phoneAppConfigurationListener: (InputSettings.PhoneAppConfiguration) -> Unit = {
        configuration -> runOnUiThread {
            // This resizes only hosted virtual displays. Beast chrome retains its own
            // per-mode controls while app density follows relative window height.
            cards.values.forEach { it.phoneAppConfiguration = configuration }
        }
    }
    private val launcherSearchListener: () -> Unit = {
        runOnUiThread {
            if (!::launcher.isInitialized) return@runOnUiThread
            if (!LauncherSearchSession.active) {
                if (launcher.visibility == View.VISIBLE) hideLauncher()
                return@runOnUiThread
            }
            if (launcher.visibility != View.VISIBLE) return@runOnUiThread
            val query = LauncherSearchSession.query
            if (launcherQuery.text.toString() != query) {
                launcherQuery.setText(query)
                launcherQuery.setSelection(launcherQuery.text.length)
            } else {
                populateLauncher(query)
            }
            if (launcherSubmitSequence != LauncherSearchSession.submitSequence) {
                launcherSubmitSequence = LauncherSearchSession.submitSequence
                filteredApps.firstOrNull()?.let(::launchFromLauncher)
            }
        }
    }
    private val privilegedListener: () -> Unit = {
        runOnUiThread {
            if (PrivilegedService.state == PrivilegedService.State.READY) {
                if (PrivilegedService.imeRoutingState !=
                    PrivilegedService.ImeRoutingState.UNAVAILABLE
                ) {
                    cards.values.forEach(BeastTaskCard::retryImeRouting)
                }
                syncAndroidTasks()
            }
            renderWorkspace()
        }
    }
    private val cursorListener: () -> Unit = {
        keepCursorOnTop()
        cursorOverlay.postInvalidateOnAnimation()
        if (::root.isInitialized && !hoverUpdateQueued) {
            hoverUpdateQueued = true
            root.postOnAnimation(hoverUpdateFrame)
        }
    }
    private val cursorScrollListener: (Float) -> Unit = { wheel ->
        runOnUiThread { scrollWorkspace(wheel) }
    }
    private val momentumKick = Runnable(::startMomentum)
    private val momentumFrame = object : Runnable {
        override fun run() {
            if (!scrollMomentum.computeScrollOffset()) return
            if (momentumTargetsLauncher) {
                launcherScroll.scrollTo(0, scrollMomentum.currY)
            } else {
                workspaceScroll.scrollTo(scrollMomentum.currX, 0)
            }
            root.postOnAnimation(this)
        }
    }
    private val viewportListener: (Float) -> Unit = { position ->
        runOnUiThread {
            val maxScroll = (taskStrip.width - workspaceScroll.width).coerceAtLeast(0)
            val target = (maxScroll * position).toInt()
            if (kotlin.math.abs(workspaceScroll.scrollX - target) > 2) {
                workspaceScroll.scrollTo(target, 0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Beast is controlled by PhoneUI or a hardware keyboard. It must never
        // become an IME target on the glasses display.
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setStatusBarColor(Color.TRANSPARENT)
        window.setNavigationBarColor(Color.TRANSPARENT)
        InputSettings.load(applicationContext)
        val initialUltrawide = display?.mode?.physicalWidth?.let { it >= 3000 } ?: false
        uiElementScale = InputSettings.uiScale(initialUltrawide)
        uiFontScale = InputSettings.fontScale(initialUltrawide)
        displayManager = getSystemService(DisplayManager::class.java)
        apps = loadApps()
        buildUi()
        BeastInputRouter.attach(this)
        activeInstances.incrementAndGet()
        // REDMAGIC OS does not create PhoneWindow's DecorView until setContentView().
        // Accessing window.insetsController earlier crashes inside PhoneWindow.
        window.decorView.post(::enterImmersiveMode)
        WorkspaceState.addListener(stateListener)
        WorkspaceState.addViewportListener(viewportListener)
        WorkspaceCursor.addListener(cursorListener)
        WorkspaceCursor.addScrollListener(cursorScrollListener)
        InputSettings.addAppearanceListener(appearanceListener)
        InputSettings.addPhoneAppConfigurationListener(phoneAppConfigurationListener)
        LauncherSearchSession.addListener(launcherSearchListener)
        PrivilegedService.addListener(privilegedListener)
        applyAppearance()
        renderWorkspace()
        root.post(systemStatusTicker)
        if (WorkspaceState.restoreFocusAfterDisplayModeSwitch(display?.mode?.physicalWidth) ||
            WorkspaceState.hasPendingDisplayModeFocusRestore()
        ) {
            restoreWorkspaceFocus()
        } else {
            // Startup is the populated taskbar/workspace. The launcher is an explicit
            // surface opened by +apps, never the default.
            restoreWorkspaceFocus()
        }
        if (PrivilegedService.state == PrivilegedService.State.READY) syncAndroidTasks()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            ACTION_SHOW_LAUNCHER -> showLauncher()
            ACTION_ENSURE_WORKSPACE -> restoreWorkspaceFocus()
        }
    }

    override fun onDestroy() {
        if (::root.isInitialized) root.removeCallbacks(systemStatusTicker)
        if (::root.isInitialized) root.removeCallbacks(hoverUpdateFrame)
        beastHoverTarget?.let { updateBeastHover(it, false) }
        beastHoverTarget = null
        routedPointerCard?.cancelRoutedPointer()
        routedPointerCard = null
        routedPointerButton = 0
        BeastInputRouter.detach(this)
        if (::root.isInitialized) root.removeCallbacks(showAllTimeout)
        pendingShowAllTaskIds.clear()
        screenshotController.close()
        launcherLease?.release()
        launcherLease = null
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(this)
        WorkspaceState.removeListener(stateListener)
        WorkspaceState.removeViewportListener(viewportListener)
        WorkspaceCursor.removeListener(cursorListener)
        WorkspaceCursor.removeScrollListener(cursorScrollListener)
        InputSettings.removeAppearanceListener(appearanceListener)
        InputSettings.removePhoneAppConfigurationListener(phoneAppConfigurationListener)
        LauncherSearchSession.removeListener(launcherSearchListener)
        PrivilegedService.removeListener(privilegedListener)
        if (WorkspaceState.hasPendingDisplayModeFocusRestore() || !isFinishing) {
            cards.values.forEach(BeastTaskCard::retainForReattach)
        } else {
            cards.values.forEach(BeastTaskCard::release)
        }
        activeInstances.decrementAndGet()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        visibleDisplayId = display?.displayId ?: -1
        if (!countedResumed) {
            countedResumed = true
            resumedInstances.incrementAndGet()
        }
        displayManager.registerDisplayListener(this, null)
        root.post(::keepOnExternalDisplay)
        AndroidTaskSynchronizer.requestImmediate()
    }

    override fun onPause() {
        visibleDisplayId = -1
        if (countedResumed) {
            countedResumed = false
            resumedInstances.decrementAndGet()
        }
        displayManager.unregisterDisplayListener(this)
        super.onPause()
    }

    override fun onDisplayAdded(displayId: Int) = keepOnExternalDisplay()
    override fun onDisplayRemoved(displayId: Int) = keepOnExternalDisplay()
    override fun onDisplayChanged(displayId: Int) {
        keepOnExternalDisplay()
        if (displayId == display?.displayId) root.post {
            applyAppearance()
            if (WorkspaceState.restoreFocusAfterDisplayModeSwitch(display?.mode?.physicalWidth)) {
                restoreWorkspaceFocus()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemKeyboard()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ESCAPE && launcher.visibility == View.VISIBLE) {
            hideLauncher()
            return true
        }
        if (launcher.visibility != View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            cards[WorkspaceState.focusedTaskId]?.let { if (it.sendKey(event.keyCode)) return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)
        ) {
            val wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (wheel != 0f) {
                // Give the view under the rendered cursor first refusal. A task
                // TextureView forwards the wheel into its VirtualDisplay; empty
                // workspace and launcher areas fall back to shell navigation.
                if (launcher.visibility != View.VISIBLE && super.dispatchGenericMotionEvent(event)) {
                    return true
                }
                scrollWorkspace(wheel)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Android never receives hover events because Beast's cursor is app-rendered. */
    private fun updateBeastHoverAtCursor() {
        if (!::root.isInitialized || root.width <= 0 || root.height <= 0) return
        val state = WorkspaceCursor.position()
        val target = if (state.visible) {
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            val screenX = rootLocation[0] + state.xFraction * root.width
            val screenY = rootLocation[1] + state.yFraction * root.height
            findBeastHoverTarget(root, screenX.toInt(), screenY.toInt())
        } else {
            null
        }
        if (target === beastHoverTarget) return
        beastHoverTarget?.let { updateBeastHover(it, false) }
        beastHoverTarget = target
        target?.let { updateBeastHover(it, true) }
    }

    private fun findBeastHoverTarget(view: View, screenX: Int, screenY: Int): View? {
        if (view.visibility != View.VISIBLE || view.alpha <= 0f || !view.isShown) return null
        val bounds = Rect()
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(screenX, screenY)) return null
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                findBeastHoverTarget(view.getChildAt(index), screenX, screenY)?.let { return it }
            }
        }
        return view.takeIf { it.isEnabled && it.isBeastHoverTarget() }
    }

    override fun routeAppScroll(displayId: Int, x: Int, y: Int, amount: Float): Boolean {
        if (display?.displayId != displayId) return false
        return cards.values.toList().asReversed().any { it.routeScrollAt(x, y, amount) }
    }

    override fun routeAppPinch(displayId: Int, x: Int, y: Int, scale: Float): Boolean {
        if (display?.displayId != displayId) return false
        return cards.values.toList().asReversed().any { it.routePinchAt(x, y, scale) }
    }

    override fun routeAppClick(displayId: Int, x: Int, y: Int, button: Int): Boolean {
        if (display?.displayId != displayId) return false
        return cards.values.toList().asReversed().any { it.routeClickAt(x, y, button) }
    }

    override fun routeAppPointer(
        displayId: Int,
        x: Int,
        y: Int,
        action: Int,
        button: Int,
        buttonState: Int,
    ): Boolean {
        if (display?.displayId != displayId) return false
        if (action == MotionEvent.ACTION_DOWN) {
            routedPointerCard?.cancelRoutedPointer()
            routedPointerCard = null
            routedPointerButton = 0
            val target = cards.values.toList().asReversed().firstOrNull {
                it.routePointerAt(x, y, action, button, buttonState)
            } ?: return false
            routedPointerCard = target
            routedPointerButton = button
            return true
        }

        val target = routedPointerCard ?: return false
        // Once DOWN is captured, consume the whole stream even if the card disappears;
        // falling back to BeastActivity would create a mismatched click-through gesture.
        target.routePointerAt(x, y, action, routedPointerButton, buttonState)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            routedPointerCard = null
            routedPointerButton = 0
        }
        return true
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        aspectLayer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(aspectLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }
        toolbar = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(14, 16, 17))
            setPadding(uiDp(12), 0, uiDp(12), 0)
        }
        shell.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, uiDp(34),
        ))
        workspaceScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        taskStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        workspaceScroll.addView(taskStrip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shell.addView(workspaceScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        taskbar = FrameLayout(this).apply {
            setPadding(uiDp(12), 0, uiDp(8), 0)
            setBackgroundColor(Color.rgb(14, 16, 17))
        }
        taskbarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Keep the tab row wrap-content so its configured alignment remains meaningful
            // when it does not overflow.
            isFillViewport = false
        }
        taskbarContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        taskTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        taskbarContent.addView(taskTabs, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        taskbarScroll.addView(taskbarContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        fun taskbarStepButton(symbol: String, description: String, direction: Int) =
            label(symbol, 20f, ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                contentDescription = description
                visibility = View.GONE
                setBeastClick { scrollTaskbarBy(direction) }
            }
        taskbarPrevious = taskbarStepButton("‹", "Show earlier task tabs", -1)
        taskbarNext = taskbarStepButton("›", "Show later task tabs", 1)
        val taskbarNavigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(taskbarPrevious, LinearLayout.LayoutParams(
                uiDp(TASKBAR_STEP_CONTROL_WIDTH_DP),
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(taskbarScroll, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ))
            addView(taskbarNext, LinearLayout.LayoutParams(
                uiDp(TASKBAR_STEP_CONTROL_WIDTH_DP),
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        taskbar.addView(taskbarNavigation, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        taskbarShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(14, 16, 17))
        }
        taskbarShell.addView(taskbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        viewportMap = ViewportMapView(this)
        taskbarShell.addView(viewportMap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, uiDp(2)))
        shell.addView(taskbarShell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, uiDp(TASKBAR_HEIGHT_DP)))
        aspectLayer.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fullscreenLayer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        aspectLayer.addView(fullscreenLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        launcher = buildLauncher()
        aspectLayer.addView(launcher, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cursorOverlay = BeastCursorOverlay(this)
        aspectLayer.addView(cursorOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            WorkspaceCursor.publishViewport(display?.displayId ?: -1, root.width, root.height)
            updateHorizontalAspectCorrection()
            cards.values.forEach { it.renderedVerticalScale = renderedVerticalScale() }
            keepCursorOnTop()
        }

        workspaceScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTaskSizes() }
        workspaceScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            viewportMap.scrollOffsetPx = scrollX
            syncViewportMap()
            val maxScroll = (taskStrip.width - workspaceScroll.width).coerceAtLeast(1)
            WorkspaceState.setViewportPosition(scrollX.toFloat() / maxScroll)
        }
        taskbarScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateTaskbarOverflowControls()
        }
        taskbarScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTaskbarOverflowControls()
        }
    }

    private fun enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun applyAppearance() {
        val mode = display?.mode
        val ultrawide = mode != null && mode.physicalWidth >= 3000
        // Hardware mode changes can bypass PhoneUI. Keep the persisted setting and
        // its switch in sync with the physical Beast timing whenever we observe it.
        if (InputSettings.ultrawideEnabled != ultrawide) {
            InputSettings.setUltrawideEnabled(applicationContext, ultrawide)
        }
        val nextElementScale = InputSettings.uiScale(ultrawide)
        val elementScaleChanged = kotlin.math.abs(uiElementScale - nextElementScale) >= .01f
        uiElementScale = nextElementScale
        val nextFontScale = InputSettings.fontScale(ultrawide)
        val fontScaleChanged = kotlin.math.abs(uiFontScale - nextFontScale) >= .01f
        uiFontScale = nextFontScale
        if ((elementScaleChanged || fontScaleChanged) && ::root.isInitialized) {
            rebuildUiForElementScale()
            return
        }
        if (::launcherQuery.isInitialized) launcherQuery.textSize = 18f * uiFontScale
        cards.values.forEach { it.fontScale = uiFontScale }
        updateHorizontalAspectCorrection()
        renderWorkspace()
    }

    /**
     * REDMAGIC exposes Beast Ultrawide as a tall logical canvas (for example 3840×2381)
     * composed onto a 3840×1200 panel. Without correction it compresses vertically and
     * therefore stretches all workspace content horizontally. Widen the rendered layer by
     * the inverse factor before scaling it around its centre. The resulting transformed
     * bounds fill the monitor instead of producing a half-width, centred workspace.
     */
    private fun updateHorizontalAspectCorrection() {
        if (!::aspectLayer.isInitialized || !::root.isInitialized || root.width <= 0 || root.height <= 0) return
        val correction = horizontalAspectCorrection()
        val targetLayerWidth = (root.width / correction).roundToInt().coerceAtLeast(root.width)
        val params = aspectLayer.layoutParams as FrameLayout.LayoutParams
        val geometryChanged = params.width != targetLayerWidth ||
            kotlin.math.abs(aspectLayer.scaleX - correction) >= .001f
        if (params.width != targetLayerWidth || params.gravity != Gravity.CENTER) {
            params.width = targetLayerWidth
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.CENTER
            aspectLayer.layoutParams = params
        }
        aspectLayer.pivotX = targetLayerWidth / 2f
        aspectLayer.pivotY = root.height / 2f
        aspectLayer.scaleX = correction
        // The widened layer exactly cancels scaleX, so the visible cursor/input grid
        // once again spans the full physical display.
        val effectiveInputScale = correction * targetLayerWidth / root.width.toFloat()
        WorkspaceCursor.publishHorizontalTransform(display?.displayId ?: -1, effectiveInputScale)
        if (geometryChanged && ::workspaceScroll.isInitialized) {
            workspaceScroll.post(::updateTaskSizes)
        }
    }

    private fun horizontalAspectCorrection(): Float {
        if (!::root.isInitialized || root.width <= 0 || root.height <= 0) return 1f
        val mode = display?.mode ?: return 1f
        if (mode.physicalWidth < 3000 || mode.physicalWidth <= 0 || mode.physicalHeight <= 0) return 1f
        val physicalScaleX = mode.physicalWidth.toFloat() / root.width
        val physicalScaleY = mode.physicalHeight.toFloat() / root.height
        return (physicalScaleY / physicalScaleX).coerceIn(.40f, 1f)
    }

    private fun phoneAppConfiguration(): InputSettings.PhoneAppConfiguration =
        InputSettings.PhoneAppConfiguration(
            InputSettings.phoneAppDensityDpi,
            InputSettings.phoneFontScale,
            InputSettings.phoneAppWindowHeightPx,
        )

    /** Converts the logical Beast canvas height into physical panel pixels. */
    private fun renderedVerticalScale(): Float {
        val mode = display?.mode ?: return 1f
        if (!::root.isInitialized || root.height <= 0 || mode.physicalHeight <= 0) return 1f
        return (mode.physicalHeight.toFloat() / root.height).coerceIn(.1f, 1f)
    }

    private fun rebuildUiForElementScale() {
        val launcherWasVisible = ::launcher.isInitialized && launcher.visibility == View.VISIBLE
        buildUi()
        renderWorkspace()
        if (launcherWasVisible) showLauncher() else restoreWorkspaceFocus()
        root.post {
            WorkspaceCursor.publishViewport(display?.displayId ?: -1, root.width, root.height)
        }
    }

    private fun renderWorkspace(revealFocusedTask: Boolean = false) {
        val tasks = WorkspaceState.tasks
        // An explicit launcher request becomes visible immediately, then receives its
        // Android task ID during reconciliation. Baseline imports remain PHONE/minimized.
        val shownTasks = tasks.filter {
            it.placement == TaskPlacement.BEAST_VISIBLE ||
                it.placement == TaskPlacement.PENDING_BEAST
        }
        val liveIds = tasks.mapTo(hashSetOf()) { it.id }
        cards.keys.filterNot(liveIds::contains).forEach { id ->
            cards.remove(id)?.let { card ->
                (card.parent as? ViewGroup)?.removeView(card)
                card.release()
            }
        }
        tasks.forEach { task ->
            val card = cards.getOrPut(task.id) { createCard(task) }
            card.uiScale = uiElementScale
            card.fontScale = uiFontScale
            card.phoneAppConfiguration = phoneAppConfiguration()
            card.renderedVerticalScale = this@BeastActivity.renderedVerticalScale()
            card.bind(task, WorkspaceState.focusedTaskId == task.id)
        }

        for (index in taskStrip.childCount - 1 downTo 0) {
            val card = taskStrip.getChildAt(index) as? BeastTaskCard ?: continue
            if (shownTasks.none { cards[it.id] === card }) taskStrip.removeViewAt(index)
        }

        val fullscreen = shownTasks.firstOrNull {
            it.id == WorkspaceState.focusedTaskId && it.mode == PresentationMode.FULLSCREEN
        }
        if (fullscreen != null) {
            workspaceScroll.visibility = View.GONE
            taskbarShell.visibility = View.GONE
            fullscreenLayer.visibility = View.VISIBLE
            val card = cards.getValue(fullscreen.id)
            card.setFullscreen(true)
            if (card.parent !== fullscreenLayer) {
                (card.parent as? ViewGroup)?.removeView(card)
                fullscreenLayer.removeAllViews()
                fullscreenLayer.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        } else {
            fullscreenLayer.removeAllViews()
            fullscreenLayer.visibility = View.GONE
            workspaceScroll.visibility = View.VISIBLE
            taskbarShell.visibility = View.VISIBLE
            shownTasks.forEachIndexed { index, task ->
                val card = cards.getValue(task.id)
                card.setFullscreen(false)
                if (card.parent !== taskStrip) {
                    (card.parent as? ViewGroup)?.removeView(card)
                    taskStrip.addView(card, index)
                } else if (taskStrip.indexOfChild(card) != index) {
                    taskStrip.removeView(card)
                    taskStrip.addView(card, index)
                }
            }
            updateTaskSizes()
            if (revealFocusedTask) revealFocusedTask()
            taskStrip.post(::syncViewportMap)
        }
        renderTaskbar(tasks)
    }

    private fun createCard(task: BeastTask) = BeastTaskCard(this, controlDebouncer).apply {
        uiScale = uiElementScale
        fontScale = uiFontScale
        phoneAppConfiguration = phoneAppConfiguration()
        renderedVerticalScale = this@BeastActivity.renderedVerticalScale()
        bind(task, false)
        onFocus = { focusTask(task) }
        onBack = { performBack() }
        onMove = { delta -> WorkspaceState.move(task.id, delta) }
        onMode = { WorkspaceState.setMode(task.id, task.mode.nextFramed()) }
        onFullscreen = {
            WorkspaceState.setMode(
                task.id,
                if (task.mode == PresentationMode.FULLSCREEN) PresentationMode.TABLET else PresentationMode.FULLSCREEN,
            )
        }
        onMinimize = { WorkspaceState.minimize(task.id) }
        onCaptureApp = {
            requestScreenshot(BeastScreenshotController.ScreenshotTarget.FOCUSED_APP)
        }
        onDisplayReady = { displayId ->
            WorkspaceState.markPendingDisplayReady(task.id, displayId)
        }
        onTaskHosted = {
            WorkspaceState.markPendingHosted(task.id)
            onShowAllTaskHosted(task.id)
        }
        onRestoreFailed = { result ->
            Log.w(
                TAG,
                "restore_diagnostic result=${result.name} workspaceId=${task.id} " +
                    "androidTaskId=${task.androidTaskId} package=${task.packageName}",
            )
        }
        onClose = {
            closeTask(task)
        }
    }

    private fun updateTaskSizes() {
        val cardHeight = workspaceScroll.height - taskStrip.paddingTop - taskStrip.paddingBottom
        val appContentHeight = cardHeight - uiDp(APP_DECORATOR_HEIGHT_DP)
        if (appContentHeight <= 0) return
        // Derive width from the actual app surface height remaining after the unified
        // UI/text scale has laid out the toolbar, taskbar, strip padding, and decorator.
        val minimumDecoratorWidth = uiDp(APP_DECORATOR_MIN_WIDTH_DP)
        val phoneWidth = maxOf(
            (appContentHeight * 9f / 19.5f).roundToInt(),
            minimumDecoratorWidth,
        )
        val tabletWidth = maxOf(
            (appContentHeight * 1.45f).roundToInt(),
            minimumDecoratorWidth,
        )
        WorkspaceState.tasks.filter {
            it.placement == TaskPlacement.BEAST_VISIBLE ||
                it.placement == TaskPlacement.PENDING_BEAST
        }.forEach { task ->
            val card = cards[task.id] ?: return@forEach
            val width = if (task.mode == PresentationMode.PHONE) phoneWidth else tabletWidth
            card.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(12)
            }
        }
    }

    private fun revealFocusedTask() {
        val card = cards[WorkspaceState.focusedTaskId] ?: return
        workspaceScroll.post {
            if (card.parent !== taskStrip || card.width <= 0) return@post
            val viewportLeft = workspaceScroll.scrollX
            val viewportRight = viewportLeft + workspaceScroll.width
            val margin = dp(12)
            val target = when {
                card.left < viewportLeft + margin -> card.left - margin
                card.right > viewportRight - margin -> card.right - workspaceScroll.width + margin
                else -> return@post
            }
            val maxScroll = (taskStrip.width - workspaceScroll.width).coerceAtLeast(0)
            workspaceScroll.smoothScrollTo(target.coerceIn(0, maxScroll), 0)
        }
    }

    private fun renderTaskbar(tasks: List<BeastTask>) {
        (taskbarContent.layoutParams as FrameLayout.LayoutParams).gravity = alignmentGravity(
            InputSettings.taskbarAlignment,
        )
        taskTabs.removeAllViews()
        tasks.forEachIndexed { index, task ->
            val focused = WorkspaceState.focusedTaskId == task.id
            val numberColor = if (focused) Color.rgb(232, 247, 248) else Color.WHITE
            val labelColor = if (focused) Color.rgb(164, 223, 229) else Color.WHITE
            val focusRule = View(this).apply {
                setBackgroundColor(if (focused) ACCENT else Color.TRANSPARENT)
            }
            val number = label("%02d".format(index + 1), 10f, numberColor).apply {
                gravity = Gravity.CENTER
            }
            val title = label(task.shortLabel.uppercase(Locale.getDefault()), TASKBAR_TEXT_SIZE, labelColor, bold = focused).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setEms(TASKBAR_LABEL_CHARACTERS)
                ellipsize = TextUtils.TruncateAt.END
            }
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(uiDp(3), 0, uiDp(3), 0)
                isClickable = true
                addView(focusRule, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, uiDp(2)))
                addView(number, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, uiDp(14)).apply {
                    topMargin = uiDp(2)
                })
                addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(18)))
                alpha = if (task.minimized || !task.supportsMultiWindow) .52f else 1f
                setBeastClick { focusTask(task) }
                installBeastHover(listOf(number to { numberColor }, title to { labelColor }))
                taskTabs.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(36)).apply {
                    marginEnd = uiDp(2)
                })
            }
        }
        taskbarScroll.post(::updateTaskbarOverflowControls)
        renderToolbar()
    }

    /** Shows edge controls only when tabs continue beyond the visible taskbar viewport. */
    private fun updateTaskbarOverflowControls() {
        if (!::taskbarScroll.isInitialized || taskbarScroll.width <= 0) return
        val maxScroll = (taskbarContent.width - taskbarScroll.width).coerceAtLeast(0)
        val canShowEarlier = maxScroll > 0 && taskbarScroll.scrollX > 0
        val canShowLater = maxScroll > 0 && taskbarScroll.scrollX < maxScroll
        val previousVisibility = if (canShowEarlier) View.VISIBLE else View.GONE
        val nextVisibility = if (canShowLater) View.VISIBLE else View.GONE
        if (taskbarPrevious.visibility != previousVisibility) {
            taskbarPrevious.visibility = previousVisibility
        }
        if (taskbarNext.visibility != nextVisibility) {
            taskbarNext.visibility = nextVisibility
        }
    }

    private fun scrollTaskbarBy(direction: Int) {
        if (direction == 0 || taskbarScroll.width <= 0) return
        val maxScroll = (taskbarContent.width - taskbarScroll.width).coerceAtLeast(0)
        val step = (taskbarScroll.width * TASKBAR_SCROLL_STEP_FRACTION).roundToInt().coerceAtLeast(1)
        taskbarScroll.smoothScrollTo(
            (taskbarScroll.scrollX + direction * step).coerceIn(0, maxScroll),
            0,
        )
    }

    /** Top bar contains workspace navigation and settings; the app strip stays below. */
    private fun renderToolbar() {
        toolbar.removeAllViews()
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(14, 16, 17))
        }
        controls.addView(label("+APPS", TOOLBAR_TEXT_SIZE, ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(uiDp(8), 0, uiDp(8), 0)
            contentDescription = "Open apps"
            setBeastClick(::showLauncher)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(28)))
        controls.addView(label("SHOW ALL", TOOLBAR_TEXT_SIZE, ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(uiDp(8), 0, uiDp(8), 0)
            setBeastClick(::showAllTasks)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(28)))
        controls.addView(label("MIN ALL", TOOLBAR_TEXT_SIZE, ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(uiDp(8), 0, uiDp(8), 0)
            setBeastClick(WorkspaceState::minimizeAll)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(28)))
        controls.addView(label("CLOSE ALL", TOOLBAR_TEXT_SIZE, Color.rgb(224, 160, 151)).apply {
            gravity = Gravity.CENTER
            setPadding(uiDp(8), 0, uiDp(8), 0)
            setBeastClick(::closeAllTasks)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(28)))
        controls.addView(label("CAPTURE", TOOLBAR_TEXT_SIZE, ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(uiDp(8), 0, uiDp(8), 0)
            contentDescription = "Capture full external display"
            setBeastClick(::captureFullDisplay)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(28)))
        controls.addView(label("→DISPLAY", TOOLBAR_TEXT_SIZE, ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(uiDp(10), 0, uiDp(10), 0)
            setBeastClick(::moveToExternalDisplay)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(28)))
        controls.addView(label("SETTINGS", TOOLBAR_TEXT_SIZE, ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(uiDp(10), 0, uiDp(10), 0)
            setBeastClick(::showInputSettings)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(28)))
        toolbar.addView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            alignmentGravity(InputSettings.toolbarAlignment),
        ).apply {
            marginStart = uiDp(76)
            marginEnd = uiDp(220)
        })
        toolbar.addView(label("LATERAL_", TOOLBAR_TEXT_SIZE, Color.rgb(178, 188, 187), bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            contentDescription = "LATERAL_"
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        systemStatus = label(systemStatusText(), TOOLBAR_TEXT_SIZE, Color.rgb(178, 188, 187)).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            contentDescription = "Time, date, battery, and network status"
            maxLines = 1
        }
        toolbar.addView(systemStatus, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END,
        ).apply { marginEnd = uiDp(8) })
    }

    private fun systemStatusText(): String {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra("level", -1) ?: -1
        val scale = battery?.getIntExtra("scale", 100)?.coerceAtLeast(1) ?: 100
        val percent = if (level >= 0) ((level * 100f) / scale).roundToInt().coerceIn(0, 100) else null
        val network = runCatching {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            connectivity
                ?.getNetworkCapabilities(connectivity.activeNetwork)
                ?.let { capabilities ->
                    when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELL"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH"
                        else -> "OFF"
                    }
                } ?: "OFF"
        }.getOrDefault("OFF")
        val clock = SimpleDateFormat("HH:mm  MMM d", Locale.getDefault()).format(Date())
        return "$clock  ${percent?.let { "$it%" } ?: "--%"}  $network"
    }

    private fun alignmentGravity(alignment: BarAlignment) = when (alignment) {
        BarAlignment.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
        BarAlignment.CENTER -> Gravity.CENTER
        BarAlignment.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
    }

    private fun syncViewportMap() {
        viewportMap.viewportWidthPx = workspaceScroll.width
        viewportMap.contentWidthPx = taskStrip.width.coerceAtLeast(workspaceScroll.width)
        viewportMap.scrollOffsetPx = workspaceScroll.scrollX
    }

    /** Fallback for the rare case Android initially places the workspace on the phone. */
    private fun moveToExternalDisplay() {
        val target = findExternalDisplay(this) ?: return
        if (display?.displayId == target.displayId) return
        BeastWorkspaceController.ensure(this, target)
    }

    /** Keep this Activity off the phone if a display-mode transition re-parents it. */
    private fun keepOnExternalDisplay() {
        val target = findExternalDisplay(this)
        val currentId = display?.displayId
        when {
            target != null && currentId != target.displayId -> moveToExternalDisplay()
            target == null && currentId == Display.DEFAULT_DISPLAY -> finish()
        }
    }

    private fun showInputSettings() {
        InputSettingsPanel.show(
            context = this,
            // Keep the workspace cursor live while the dialog is open. The dialog's
            // own window is above BeastUI, so it receives clicks without suppressing
            // the only visible pointer on the glasses.
            onModalVisibilityChanged = { cursorOverlay.visibility = View.VISIBLE },
        )
    }

    /** Keep the rendered pointer above every dynamically rebuilt BeastUI layer. */
    private fun keepCursorOnTop() {
        if (::cursorOverlay.isInitialized && cursorOverlay.parent === aspectLayer) {
            cursorOverlay.bringToFront()
        }
    }

    private fun scrollWorkspace(wheel: Float) {
        val targetsLauncher = launcher.visibility == View.VISIBLE
        val direction = if (InputSettings.invertScrollbarScroll) -1f else 1f
        val distance = if (targetsLauncher) {
            direction * wheel * dp(96)
        } else {
            // The desktop's visible viewport is a horizontal task strip; use a
            // vertical wheel/PhoneUI edge-scroll gesture to pan it.
            direction * wheel * workspaceScroll.width * .32f
        }
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - lastScrollAt).coerceIn(1L, 120L)
        val sampleVelocity = distance * 1000f / elapsed
        momentumVelocity = if (now - lastScrollAt > 160L) {
            sampleVelocity
        } else {
            momentumVelocity * .55f + sampleVelocity * .45f
        }
        lastScrollAt = now
        momentumTargetsLauncher = targetsLauncher
        scrollMomentum.forceFinished(true)
        root.removeCallbacks(momentumKick)

        if (targetsLauncher) {
            launcherScroll.scrollBy(0, distance.toInt())
        } else {
            workspaceScroll.scrollBy(distance.toInt(), 0)
        }
        if (InputSettings.momentumEnabled) {
            root.postDelayed(momentumKick, MOMENTUM_DELAY_MS)
        }
    }

    private fun startMomentum() {
        if (!InputSettings.momentumEnabled) return
        if (kotlin.math.abs(momentumVelocity) < MIN_FLING_VELOCITY) return
        if (momentumTargetsLauncher) {
            val maxY = ((launcherScroll.getChildAt(0)?.height ?: 0) - launcherScroll.height).coerceAtLeast(0)
            scrollMomentum.fling(0, launcherScroll.scrollY, 0, momentumVelocity.toInt(), 0, 0, 0, maxY)
        } else {
            val maxX = (taskStrip.width - workspaceScroll.width).coerceAtLeast(0)
            scrollMomentum.fling(workspaceScroll.scrollX, 0, momentumVelocity.toInt(), 0, 0, maxX, 0, 0)
        }
        root.postOnAnimation(momentumFrame)
    }

    private fun buildLauncher(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.argb(112, 7, 8, 9))
            isClickable = true
            setBeastClick { hideLauncher() }
        }
        launcherPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(28), uiDp(22), uiDp(28), uiDp(18))
            background = borderBackground(Color.rgb(49, 62, 62))
            isClickable = true
        }
        launcherQuery = EditText(this).apply {
            hint = "> _"
            isSingleLine = true
            typeface = Typeface.MONOSPACE
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(105, 119, 119))
            setBackgroundColor(Color.TRANSPARENT)
            showSoftInputOnFocus = false
            addTextChangedListener(SimpleTextWatcher { value ->
                populateLauncher(value)
                LauncherSearchSession.updateQuery(value)
            })
            setOnEditorActionListener { _, _, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (enter || event == null) {
                    filteredApps.firstOrNull()?.let(::launchFromLauncher)
                    true
                } else false
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = label("× close", 11f, ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(uiDp(10), 0, uiDp(2), 0)
            setBeastClick(::hideLauncher)
        }
        launcherResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        launcherScroll = ScrollView(this).apply { addView(launcherResults) }
        header.addView(launcherQuery, LinearLayout.LayoutParams(0, uiDp(52), 1f))
        header.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(52)))
        launcherPanel.addView(header)
        launcherPanel.addView(buildLauncherSortBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, uiDp(38)))
        launcherPanel.addView(launcherScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        launcherPanel.addView(label("esc:close", 10f, Color.rgb(105, 119, 119)).apply { gravity = Gravity.END })
        overlay.addView(launcherPanel, FrameLayout.LayoutParams(
            uiDp(420),
            uiDp(520),
            Gravity.CENTER,
        ))
        return overlay
    }

    private fun buildLauncherSortBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        LauncherSort.entries.forEach { option ->
            addView(label(option.title, 11f, if (launcherPreferences.sort == option) ACCENT else Color.rgb(105, 119, 119)).apply {
                gravity = Gravity.CENTER
                setPadding(uiDp(12), 0, uiDp(12), 0)
                setBeastClick {
                    launcherPreferences.sort = option
                    // Rebuild the launcher so the persisted active indicator and ordering agree.
                    val parent = launcher.parent as? ViewGroup
                    val index = parent?.indexOfChild(launcher) ?: -1
                    parent?.removeView(launcher)
                    launcher = buildLauncher()
                    if (parent != null && index >= 0) parent.addView(
                        launcher, index,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                    showLauncher()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun showLauncher() {
        if (!LauncherSearchSession.active) {
            launcherSubmitSequence = LauncherSearchSession.submitSequence
            LauncherSearchSession.begin()
            // REDMAGIC otherwise inherits this external display for MainActivity's
            // singleTask delivery, which relocates PhoneUI onto Beast and hides us.
            MainActivity.restorePhoneUiAfterExternalTaskBatch()
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_BEAST_LAUNCHER_SEARCH
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }, ActivityOptions.makeBasic().apply {
                    launchDisplayId = Display.DEFAULT_DISPLAY
                }.toBundle())
            }
        }
        lateinit var lease: TransientPanelCoordinator.Lease
        lease = TransientPanelCoordinator.claim(
            display?.displayId ?: Display.DEFAULT_DISPLAY,
            TransientPanelCoordinator.Kind.APPS,
        ) {
            if (::launcher.isInitialized) {
                launcher.visibility = View.GONE
                LauncherSearchSession.close()
                if (::root.isInitialized) root.requestFocus()
                hideSystemKeyboard()
            }
            if (launcherLease === lease) launcherLease = null
        }
        launcherLease = lease
        launcher.visibility = View.VISIBLE
        launcherQuery.setText(LauncherSearchSession.query)
        populateLauncher(LauncherSearchSession.query)
        launcherPanel.post(::fitLauncherPopup)
        launcherQuery.requestFocus()
        launcherQuery.post(::hideSystemKeyboard)
        // Refresh Android's Overview ordering each time the launcher opens. Once the
        // workspace is seeded this updates sorting only; it never rearranges its cards.
        if (PrivilegedService.state == PrivilegedService.State.READY) syncAndroidTasks()
    }

    private fun hideLauncher() {
        launcher.visibility = View.GONE
        LauncherSearchSession.close()
        launcherLease?.release()
        launcherLease = null
        root.requestFocus()
        hideSystemKeyboard()
    }

    private fun restoreWorkspaceFocus() {
        launcher.visibility = View.GONE
        LauncherSearchSession.close()
        root.requestFocus()
        renderWorkspace()
        root.post {
            cards[WorkspaceState.focusedTaskId]?.restoreFocus()
            hideSystemKeyboard()
        }
    }

    private fun hideSystemKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun populateLauncher(filter: String) {
        launcherResults.removeAllViews()
        filteredApps = orderedApps().asSequence()
            .filter { it.loadLabel(packageManager).toString().contains(filter, ignoreCase = true) }
            .toList()
        filteredApps.forEach { info -> launcherResults.addView(appLauncherRow(info)) }
        if (::launcherPanel.isInitialized) launcherPanel.post(::fitLauncherPopup)
    }

    /** Fit to the currently visible launcher labels, with safe display-relative caps. */
    private fun fitLauncherPopup() {
        if (!::launcherPanel.isInitialized || root.width == 0 || root.height == 0) return
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f * resources.displayMetrics.scaledDensity * uiFontScale
        }
        val widestLabel = filteredApps.maxOfOrNull {
            paint.measureText(it.loadLabel(packageManager).toString().lowercase())
        } ?: 0f
        val desiredWidth = (widestLabel + uiDp(150)).toInt()
        val maxWidth = minOf(root.width - uiDp(48), uiDp(760)).coerceAtLeast(uiDp(260))
        val width = desiredWidth.coerceIn(uiDp(300).coerceAtMost(maxWidth), maxWidth)
        val desiredHeight = uiDp(120) + launcherResults.measuredHeight
        val maxHeight = minOf((root.height * .78f).toInt(), uiDp(760))
        val height = desiredHeight.coerceIn(uiDp(180), maxHeight)
        launcherPanel.layoutParams = (launcherPanel.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = width
            this.height = height
            gravity = Gravity.CENTER
        }
    }

    private fun appLauncherRow(info: ResolveInfo): View {
        val packageName = info.activityInfo.packageName
        val openTasks = WorkspaceState.tasks
            .filter { it.packageName == packageName }
            .sortedBy(BeastTask::overviewRank)
        val distinctTitles = openTasks.map { it.instanceTitle.trim() }
        val useTitles = openTasks.size > 1 && distinctTitles.all(String::isNotBlank) &&
            distinctTitles.distinctBy(String::lowercase).size == openTasks.size
        val rowHeight = maxOf(uiDp(44), uiDp(30) * openTasks.size)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(info.loadLabel(packageManager).toString().lowercase(), 15f, Color.rgb(202, 212, 210)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(uiDp(8), 0, uiDp(8), 0)
                setBeastClick { launchFromLauncher(info) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            if (openTasks.isNotEmpty()) {
                addView(LinearLayout(this@BeastActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    openTasks.forEachIndexed { index, task ->
                        val designation = when {
                            openTasks.size == 1 -> ""
                            useTitles -> " · ${task.instanceTitle.trim().take(32)}"
                            else -> " #${index + 1}"
                        }
                        addView(label("● open$designation", 11f, ACCENT).apply {
                            gravity = Gravity.CENTER_VERTICAL or Gravity.END
                            setPadding(uiDp(12), 0, uiDp(8), 0)
                            setBeastClick {
                            launcherPreferences.recordUse(packageName)
                            WorkspaceState.focus(task.id, reveal = true)
                            hideLauncher()
                        }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(30)))
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, rowHeight))
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight) }
    }

    private fun orderedApps(): List<ResolveInfo> {
        fun label(info: ResolveInfo) = info.loadLabel(packageManager).toString().lowercase()
        return when (launcherPreferences.sort) {
            LauncherSort.ALPHABETICAL -> apps.sortedBy(::label)
            LauncherSort.RECENTLY_USED -> apps.sortedWith(
                compareBy<ResolveInfo> { recentPackageRank[it.activityInfo.packageName] ?: Int.MAX_VALUE }
                    .thenByDescending { launcherPreferences.lastUsed(it.activityInfo.packageName) }
                    .thenBy(::label),
            )
            LauncherSort.MOST_USED -> apps.sortedWith(
                compareByDescending<ResolveInfo> { launcherPreferences.useCount(it.activityInfo.packageName) }
                    .thenByDescending { launcherPreferences.lastUsed(it.activityInfo.packageName) }
                    .thenBy(::label),
            )
        }
    }

    private fun launchFromLauncher(info: ResolveInfo) {
        launcherPreferences.recordUse(info.activityInfo.packageName)
        WorkspaceState.launchNew(
            info.activityInfo.packageName,
            info.activityInfo.name,
            info.loadLabel(packageManager).toString(),
        )
        hideLauncher()
    }

    private fun focusTask(task: BeastTask) {
        // Input delivered inside the already-focused hosted task (pinch, scroll, Chrome
        // menu controls, and contextual actions) must not re-run a cross-display task
        // focus transaction. That transaction can briefly front the task on Android and
        // steal PhoneUI focus even though the user never changed apps.
        if (WorkspaceState.focusedTaskId == task.id && task.placement == TaskPlacement.BEAST_VISIBLE) {
            return
        }
        launcherPreferences.recordUse(task.packageName)
        WorkspaceState.focus(task.id, reveal = true)
    }

    /** Closing means terminate the Android task; minimizing is the non-destructive action. */
    private fun closeTask(task: BeastTask) {
        AndroidTaskSynchronizer.closeTask(task) { removed ->
            if (!removed) {
                android.widget.Toast.makeText(
                    this, "Could not remove ${task.label} from Android Overview",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun closeAllTasks() {
        WorkspaceState.closeAllCandidates().forEach { task ->
            AndroidTaskSynchronizer.closeTask(task) { }
        }
    }

    private fun showAllTasks() {
        root.removeCallbacks(showAllTimeout)
        pendingShowAllTaskIds.clear()
        WorkspaceState.tasks.asSequence()
            .filter { it.placement == TaskPlacement.PHONE && it.supportsMultiWindow }
            .mapTo(pendingShowAllTaskIds, BeastTask::id)
        WorkspaceState.showAll()
        if (pendingShowAllTaskIds.isNotEmpty()) {
            root.postDelayed(showAllTimeout, SHOW_ALL_BATCH_TIMEOUT_MS)
        }
    }

    private fun onShowAllTaskHosted(taskId: Long) {
        if (!pendingShowAllTaskIds.remove(taskId) || pendingShowAllTaskIds.isNotEmpty()) return
        root.removeCallbacks(showAllTimeout)
        finishShowAllBatch()
    }

    private fun finishShowAllBatch() {
        pendingShowAllTaskIds.clear()
        MainActivity.restorePhoneUiAfterExternalTaskBatch()
    }

    private fun captureFullDisplay() {
        requestScreenshot(BeastScreenshotController.ScreenshotTarget.FULL_DISPLAY)
    }

    private fun requestScreenshot(target: BeastScreenshotController.ScreenshotTarget) {
        if (!screenshotController.request(target)) {
            android.widget.Toast.makeText(this, "Capture already in progress", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun onScreenshotResult(result: BeastScreenshotController.Result) {
        when (result) {
            is BeastScreenshotController.Result.Saved -> {
                val message = if (result.galleryVisible) "Screenshot saved to Gallery" else "Screenshot saved"
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            }
            is BeastScreenshotController.Result.Failed -> {
                android.widget.Toast.makeText(this, result.message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncAndroidTasks() {
        val recent = PrivilegedService.recentTasks()
        recentPackageRank = recent.map { it.packageName }.distinct().withIndex()
            .associate { it.value to it.index }
        AndroidTaskSynchronizer.requestImmediate()
        if (launcher.visibility == View.VISIBLE) populateLauncher(launcherQuery.text.toString())
    }

    private fun loadApps(): List<ResolveInfo> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size * uiFontScale
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(color)
    }

    private fun borderBackground(stroke: Int) = GradientDrawable().apply {
        setColor(Color.rgb(10, 12, 13))
        setStroke(dp(1), stroke)
    }

    /** Task surfaces retain native dimensions; only LATERAL_ chrome uses uiDp(). */
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun uiDp(value: Int) = (dp(value) * uiElementScale).toInt()

    private fun View.setBeastClick(action: () -> Unit) {
        setOnClickListener { controlDebouncer.submit(action) }
        (this as? TextView)?.installBeastHover()
    }

}

/** Non-interactive, workspace-owned cursor that remains visible on the glasses. */
private class BeastCursorOverlay(context: android.content.Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 24, 24)
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1.5f
    }
    private val pointer = Path()

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val state = WorkspaceCursor.position()
        if (!state.visible || width == 0 || height == 0) return
        val x = state.xFraction * width
        val y = state.yFraction * height
        val size = resources.displayMetrics.density * 18f
        pointer.reset()
        pointer.moveTo(x, y)
        pointer.lineTo(x, y + size)
        pointer.lineTo(x + size * .32f, y + size * .72f)
        pointer.lineTo(x + size * .58f, y + size * 1.08f)
        pointer.lineTo(x + size * .82f, y + size * .9f)
        pointer.lineTo(x + size * .52f, y + size * .54f)
        pointer.lineTo(x + size, y + size * .52f)
        pointer.close()
        canvas.drawPath(pointer, fill)
        canvas.drawPath(pointer, stroke)
    }
}

private class BeastTaskCard(
    context: android.content.Context,
    private val controlDebouncer: BeastControlDebouncer,
) : FrameLayout(context) {
    var onFocus: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onMove: ((Int) -> Unit)? = null
    var onMode: (() -> Unit)? = null
    var onFullscreen: (() -> Unit)? = null
    var onMinimize: (() -> Unit)? = null
    var onCaptureApp: (() -> Unit)? = null
    var onDisplayReady: ((Int) -> Unit)? = null
    var onTaskHosted: (() -> Unit)? = null
    var onRestoreFailed: ((PrivilegedService.FocusRestoreResult) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val column = LinearLayout(context)
    private val focusRule = View(context)
    private val chrome = FrameLayout(context)
    private val appLabel = TextView(context)
    private val modeButton = TextView(context)
    private val leftControls = LinearLayout(context)
    private val noteNavigation = LinearLayout(context)
    private val rightControls = LinearLayout(context)
    private lateinit var backButton: TextView
    private lateinit var captureAppButton: TextView
    private val surface = TaskSurfaceView(context)
    private val fullscreenExit = TextView(context)
    private val controls = mutableListOf<TextView>()
    private val controlBaseWidths = mutableMapOf<TextView, Int>()
    private var boundTask: BeastTask? = null
    private var focused = false
    private var backQueryGeneration = 0
    var uiScale = 1f
        set(value) {
            field = value
            applyUiScale()
        }
    var fontScale = 1f
        set(value) {
            field = value
            applyFontScale()
        }
    var appDensityDpi = 240
        set(value) {
            val next = value.coerceIn(MIN_HOSTED_APP_DPI, MAX_HOSTED_APP_DPI)
            if (field == next) return
            field = next
            surface.targetDensityDpi = next
        }
    var phoneAppConfiguration = InputSettings.PhoneAppConfiguration(240, 1f, 1)
        set(value) {
            field = value
            updateAppDensityForRenderedHeight()
        }
    var renderedVerticalScale = 1f
        set(value) {
            field = value.coerceIn(.1f, 1f)
            updateAppDensityForRenderedHeight()
        }

    init {
        setBackgroundColor(Color.rgb(12, 14, 15))
        isClickable = true
        setOnClickListener { controlDebouncer.submit { onFocus?.invoke() } }
        surface.onInteraction = {
            onFocus?.invoke()
            refreshBackAvailability()
        }
        surface.onDisplayReady = {
            onDisplayReady?.invoke(it)
            updateCaptureAvailability()
            refreshBackAvailability()
        }
        surface.onTaskHosted = { onTaskHosted?.invoke() }
        surface.onRestoreFailed = { result -> onRestoreFailed?.invoke(result) }
        surface.onAvailabilityChanged = { updateCaptureAvailability() }
        surface.onKeyboardRoutingUnavailable = {
            boundTask?.let { task -> bind(task, false) }
        }
        surface.targetDensityDpi = appDensityDpi
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateAppDensityForRenderedHeight()
        }
        column.orientation = LinearLayout.VERTICAL
        focusRule.setBackgroundColor(Color.TRANSPARENT)
        chrome.setPadding(dp(8), 0, dp(4), 0)
        chrome.setBackgroundColor(Color.rgb(16, 19, 20))
        appLabel.typeface = Typeface.MONOSPACE
        appLabel.textSize = 11f * fontScale
        appLabel.setTextColor(Color.rgb(178, 188, 187))
        appLabel.maxLines = 1
        appLabel.ellipsize = TextUtils.TruncateAt.END
        appLabel.minWidth = 0
        appLabel.minEms = 0
        appLabel.includeFontPadding = false
        backButton = control("<-") { onBack?.invoke() }.apply {
            contentDescription = "Back"
            isEnabled = false
            alpha = .35f
        }
        controlBaseWidths[backButton] = 34
        leftControls.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backButton, LinearLayout.LayoutParams(
                dp(34),
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }

        noteNavigation.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        noteNavigation.addView(control("<") { onMove?.invoke(-1) })
        appLabel.gravity = Gravity.CENTER
        noteNavigation.addView(appLabel, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f,
        ))
        noteNavigation.addView(control(">") { onMove?.invoke(1) })
        chrome.addView(noteNavigation, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        ).apply {
            // Keep the title slot centered in the decorator even though the left
            // and right control groups have different widths.
            marginStart = dp(RIGHT_CONTROLS_WIDTH_DP)
            marginEnd = dp(RIGHT_CONTROLS_WIDTH_DP)
        })

        rightControls.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeButton.gravity = Gravity.CENTER
        modeButton.typeface = Typeface.MONOSPACE
        modeButton.textSize = 12f * fontScale
        modeButton.setTextColor(InputSettings.accentColor)
        modeButton.setOnClickListener { controlDebouncer.submit { onMode?.invoke() } }
        modeButton.installBeastHover { InputSettings.accentColor }
        captureAppButton = control("cap") {
            onCaptureApp?.invoke()
        }.apply {
            contentDescription = "Capture focused app screenshot"
        }
        controlBaseWidths[captureAppButton] = 34
        leftControls.addView(captureAppButton, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT))
        chrome.addView(leftControls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START or Gravity.CENTER_VERTICAL,
        ))
        rightControls.addView(control("_") { onMinimize?.invoke() })
        rightControls.addView(modeButton, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT))
        val fullscreenButton = control("[]") { onFullscreen?.invoke() }
        controlBaseWidths[fullscreenButton] = 36
        rightControls.addView(fullscreenButton, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT))
        rightControls.addView(control("x") { onClose?.invoke() })
        chrome.addView(rightControls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.CENTER_VERTICAL,
        ))
        chrome.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateNavigationVisibility()
        }
        column.addView(focusRule, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        column.addView(chrome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        column.addView(surface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenExit.text = "[T]"
        fullscreenExit.gravity = Gravity.CENTER
        fullscreenExit.typeface = Typeface.MONOSPACE
        fullscreenExit.textSize = 12f * fontScale
        fullscreenExit.setTextColor(InputSettings.accentColor)
        fullscreenExit.setBackgroundColor(Color.argb(190, 15, 18, 19))
        fullscreenExit.visibility = View.GONE
        fullscreenExit.setOnClickListener { controlDebouncer.submit { onFullscreen?.invoke() } }
        fullscreenExit.installBeastHover { InputSettings.accentColor }
        addView(fullscreenExit, LayoutParams(dp(48), dp(36), Gravity.TOP or Gravity.END))
        applyUiScale()
        applyFontScale()
    }

    fun bind(task: BeastTask, focused: Boolean) {
        boundTask = task
        this.focused = focused
        surface.task = task
        appLabel.text = buildString {
            append(task.label.lowercase())
            if (!task.supportsMultiWindow && task.placement == TaskPlacement.PHONE) append(" · phone only")
            if (PrivilegedService.imeRoutingState ==
                PrivilegedService.ImeRoutingState.UNAVAILABLE
            ) {
                append(" · keyboard routing unavailable")
                PrivilegedService.imeRoutingError.takeIf(String::isNotBlank)?.let {
                    append(" · ").append(it)
                }
            }
            task.closeError?.let { append(" · close failed") }
        }
        val routingAvailable = PrivilegedService.imeRoutingState !=
            PrivilegedService.ImeRoutingState.UNAVAILABLE
        surface.isEnabled = routingAvailable
        surface.alpha = if (routingAvailable) 1f else .22f
        modeButton.text = task.mode.marker
        focusRule.setBackgroundColor(if (focused) InputSettings.accentColor else Color.TRANSPARENT)
        updateCaptureAvailability()
        refreshBackAvailability()
    }

    fun setFullscreen(fullscreen: Boolean) {
        chrome.visibility = if (fullscreen) View.GONE else View.VISIBLE
        focusRule.visibility = if (fullscreen) View.GONE else View.VISIBLE
        fullscreenExit.visibility = if (fullscreen) View.VISIBLE else View.GONE
    }

    private fun updateAppDensityForRenderedHeight() {
        if (surface.height <= 0) return
        val configuration = phoneAppConfiguration
        val renderedHeight = surface.height * renderedVerticalScale
        appDensityDpi = (configuration.densityDpi * renderedHeight / configuration.windowHeightPx)
            .roundToInt()
    }

    fun release() = surface.release()
    fun retainForReattach() = surface.retainForReattach()
    fun sendKey(keyCode: Int) = surface.sendKey(keyCode)
    fun captureBitmap() = surface.captureBitmap()
    fun restoreFocus() = surface.requestFocus()
    fun retryImeRouting() = surface.retryImeRouting()
    fun routeScrollAt(globalX: Int, globalY: Int, amount: Float) =
        surface.routeScrollAt(globalX, globalY, amount)
    fun routePinchAt(globalX: Int, globalY: Int, scale: Float) =
        surface.routePinchAt(globalX, globalY, scale)
    fun routeClickAt(globalX: Int, globalY: Int, button: Int) =
        surface.routeClickAt(globalX, globalY, button)
    fun routePointerAt(
        globalX: Int,
        globalY: Int,
        action: Int,
        button: Int,
        buttonState: Int,
    ) = surface.routePointerAt(globalX, globalY, action, button, buttonState)
    fun cancelRoutedPointer() = surface.cancelRoutedPointer()

    fun performBack() {
        if (!backButton.isEnabled) return
        if (sendKey(KeyEvent.KEYCODE_BACK)) {
            backButton.isEnabled = false
            postDelayed({
                surface.catchBackExit { refreshBackAvailability() }
            }, BACK_CATCH_DELAY_MS)
        }
    }

    private fun refreshBackAvailability() {
        val query = ++backQueryGeneration
        surface.queryBackAvailability { available ->
            if (query != backQueryGeneration) return@queryBackAvailability
            backButton.isEnabled = available
            backButton.alpha = if (available) 1f else .35f
            backButton.contentDescription = if (available) "Back" else "Back unavailable"
        }
    }

    private fun updateCaptureAvailability() {
        if (!::captureAppButton.isInitialized) return
        val enabled = focused && surface.canCapture()
        captureAppButton.isEnabled = enabled
        captureAppButton.alpha = if (enabled) 1f else .35f
    }

    private fun control(text: String, action: () -> Unit) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        textSize = 12f * fontScale
        setTextColor(Color.rgb(139, 153, 152))
        setOnClickListener { controlDebouncer.submit(action) }
        installBeastHover()
        layoutParams = LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT)
    }.also {
        controls += it
        controlBaseWidths[it] = 30
    }

    private fun applyUiScale() {
        if (!::backButton.isInitialized || !::captureAppButton.isInitialized) return
        chrome.setPadding(decoratorDp(8), 0, decoratorDp(4), 0)
        focusRule.layoutParams?.let {
            it.height = decoratorDp(2)
            focusRule.layoutParams = it
        }
        chrome.layoutParams?.let {
            it.height = decoratorDp(30)
            chrome.layoutParams = it
        }
        controls.forEach { control ->
            control.layoutParams?.let {
                it.width = decoratorDp(controlBaseWidths[control] ?: 30)
                control.layoutParams = it
            }
        }
        modeButton.layoutParams?.let {
            it.width = decoratorDp(30)
            modeButton.layoutParams = it
        }
        (noteNavigation.layoutParams as? FrameLayout.LayoutParams)?.let {
            val inset = decoratorDp(RIGHT_CONTROLS_WIDTH_DP)
            it.marginStart = inset
            it.marginEnd = inset
            noteNavigation.layoutParams = it
        }
        updateNavigationVisibility(force = true)
        fullscreenExit.layoutParams?.let {
            it.width = decoratorDp(48)
            it.height = decoratorDp(36)
            fullscreenExit.layoutParams = it
        }
        requestLayout()
    }

    /** Preserve the centre arrows on narrow cards by collapsing only the title. */
    private fun updateNavigationVisibility(force: Boolean = false) {
        if (chrome.width <= 0) return
        val titleSlotWidth = chrome.width - chrome.paddingLeft - chrome.paddingRight -
            decoratorDp(RIGHT_CONTROLS_WIDTH_DP * 2)
        val collapseTitle = titleSlotWidth < decoratorDp(MIN_TITLE_SLOT_DP)
        val currentlyCollapsed = appLabel.visibility == View.GONE
        if (collapseTitle == currentlyCollapsed && !force) return

        appLabel.visibility = if (collapseTitle) View.GONE else View.VISIBLE
        val params = noteNavigation.layoutParams as? FrameLayout.LayoutParams ?: return
        if (collapseTitle) {
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
            val contentWidth = chrome.width - chrome.paddingLeft - chrome.paddingRight
            val leftInset = decoratorDp(LEFT_CONTROLS_WIDTH_DP)
            val rightInset = decoratorDp(RIGHT_CONTROLS_WIDTH_DP)
            val navigationWidth = decoratorDp(CENTER_NAVIGATION_WIDTH_DP)
            val freeCenterWidth = (contentWidth - leftInset - rightInset).coerceAtLeast(0)
            params.marginStart = leftInset + ((freeCenterWidth - navigationWidth).coerceAtLeast(0) / 2)
            params.marginEnd = 0
            params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            noteNavigation.gravity = Gravity.CENTER
        } else {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.marginStart = decoratorDp(RIGHT_CONTROLS_WIDTH_DP)
            params.marginEnd = decoratorDp(RIGHT_CONTROLS_WIDTH_DP)
            params.gravity = Gravity.CENTER
            noteNavigation.gravity = Gravity.CENTER_VERTICAL
        }
        noteNavigation.layoutParams = params
    }

    private fun applyFontScale() {
        val textScale = decoratorTextScale()
        appLabel.textSize = 11f * textScale
        modeButton.textSize = 12f * textScale
        fullscreenExit.textSize = 12f * textScale
        controls.forEach { it.textSize = 12f * textScale }
    }

    private companion object {
        const val BACK_CATCH_DELAY_MS = 500L
        const val LEFT_CONTROLS_WIDTH_DP = 68
        const val RIGHT_CONTROLS_WIDTH_DP = 126
        const val CENTER_NAVIGATION_WIDTH_DP = 60
        const val MIN_TITLE_SLOT_DP = 72
        const val DECORATOR_GEOMETRY_SCALE_MAX = 1.15f
        const val DECORATOR_TEXT_SCALE_MAX = 1.50f
        const val MIN_HOSTED_APP_DPI = 72
        const val MAX_HOSTED_APP_DPI = 640
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun decoratorDp(value: Int): Int =
        (dp(value) * uiScale.coerceIn(.85f, DECORATOR_GEOMETRY_SCALE_MAX)).roundToInt()

    private fun decoratorTextScale(): Float = fontScale.coerceIn(.85f, DECORATOR_TEXT_SCALE_MAX)

}

/** One gate for all Beast shell actions; hosted app input bypasses it entirely. */
private class BeastControlDebouncer {
    private var blockedUntil = SystemClock.uptimeMillis() + DEBOUNCE_MS

    fun submit(action: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now < blockedUntil) return
        blockedUntil = now + DEBOUNCE_MS
        action()
    }

    private companion object {
        const val DEBOUNCE_MS = 320L
    }
}
