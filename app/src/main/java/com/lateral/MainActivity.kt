package com.lateral

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lateral.privileged.PairingNotifier
import com.lateral.privileged.PrivilegedService
import com.lateral.beast.BeastActivity
import com.lateral.beast.BeastDisplayModeController
import com.lateral.beast.BeastWorkspaceController
import com.lateral.beast.LauncherSearchSession
import com.lateral.beast.HostedTextInputSession
import com.lateral.beast.WorkspaceState
import com.lateral.beast.AndroidTaskSynchronizer
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import dev.patrickgold.florisboard.embedded.EmbeddedFlorisKeyboardView
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), DisplayManager.DisplayListener {
    companion object {
        val ACTION_BEAST_LAUNCHER_SEARCH = BuildConfig.APPLICATION_ID + ".action.BEAST_LAUNCHER_SEARCH"
        private const val ACTION_PHONE_FOCUS_REPAIR = BuildConfig.APPLICATION_ID + ".action.PHONE_FOCUS_REPAIR"
        private const val WORKSPACE_GUARD_INTERVAL_MS = 350L
        private const val WORKSPACE_RELAUNCH_GUARD_MS = 1200L
        private const val PHONE_GESTURE_GUARD_DP = 24
        private const val PHONE_DISPLAY_SETTLE_MS = 220L
        private const val PHONE_DISPLAY_REPAIR_DELAY_MS = 250L
        private const val MAX_PHONE_DISPLAY_REPAIR_ATTEMPTS = 12
        private const val WIRELESS_DEBUGGING_SETTINGS_ACTION =
            "android.settings.WIRELESS_DEBUGGING_SETTINGS"
        private const val SETTINGS_SHOW_FRAGMENT_EXTRA = ":settings:show_fragment"
        private const val WIRELESS_DEBUGGING_FRAGMENT =
            "com.android.settings.development.WirelessDebuggingFragment"
        private val ACCENT get() = InputSettings.accentColor
        @Volatile private var livePhoneTaskId = -1
        @Volatile private var phoneUiContext: Context? = null

        /** Stable Android identity used by helper-side focus-preserving task transactions. */
        fun currentPhoneTaskId(): Int = livePhoneTaskId

        /** REDMAGIC task moves are globally focus-changing; restore PhoneUI once per batch. */
        fun restorePhoneUiAfterExternalTaskBatch() {
            val taskId = livePhoneTaskId
            if (taskId >= 0 && PrivilegedService.state == PrivilegedService.State.READY) {
                PrivilegedService.restorePhoneTaskIfStillHome(taskId)
            }
        }

        /** Re-focus the existing PhoneUI task after a Beast-originated app launch race. */
        fun bringPhoneUiToFrontAfterBeastAction() {
            val context = phoneUiContext ?: return
            runCatching {
                val intent = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_PHONE_FOCUS_REPAIR
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val options = ActivityOptions.makeBasic().apply {
                    launchDisplayId = Display.DEFAULT_DISPLAY
                }.toBundle()
                context.startActivity(intent, options)
            }.onFailure { Log.w("Lateral/Focus", "PhoneUI foreground request failed", it) }
        }
    }

    private lateinit var inputController: InputController
    private lateinit var displayManager: DisplayManager
    private lateinit var beastSearchField: PhoneProxyEditText
    private lateinit var phoneProxyContainer: LinearLayout
    private lateinit var phoneProxyClose: TextView
    private lateinit var hostedKeyboardController: HostedKeyboardSessionController
    private lateinit var embeddedKeyboardView: EmbeddedFlorisKeyboardView
    private lateinit var embeddedKeyboardContainer: LinearLayout
    private lateinit var embeddedKeyboardStatus: TextView
    private lateinit var connectionText: TextView
    private lateinit var currentAppText: TextView
    private lateinit var inputDebugText: TextView
    private lateinit var trackpad: TrackpadView
    private lateinit var workspaceNavigator: WorkspaceNavigatorView
    private lateinit var workspaceViewportSlider: WorkspaceViewportSliderView
    private lateinit var phoneTaskbarContainer: LinearLayout
    private val accentCommandViews = mutableListOf<TextView>()
    private lateinit var beastDisplayModeController: BeastDisplayModeController
    private var externalDisplay: Display? = null
    private var launchedBeastDisplayId: Int? = null
    private var clearedExternalDisplayId: Int? = null
    private var restoredBeastTimingDisplayId: Int? = null
    private var beastTimingRestoreInFlight = false
    private var lastWorkspaceLaunchAt = 0L
    private var phoneDisplayRepairAttempts = 0
    private var activityResumed = false
    private var phoneFocusRepairGeneration = 0L
    private var pendingPhoneFocusRepair: Runnable? = null
    private var nextPhoneFocusLeaseToken = 1L
    private val phoneFocusLeaseTokens = linkedSetOf<Long>()
    private var beastSearchFocusLease: PhoneWindowFocusLease? = null
    private var adbBridgeDialog: android.app.AlertDialog? = null
    private var lastPromptedBridgeState: PrivilegedService.State? = null
    private val beastSearchListener: () -> Unit = {
        runOnUiThread {
            if (::beastSearchField.isInitialized) syncBeastSearchField()
        }
    }
    private val hostedTextInputListener: () -> Unit = {
        runOnUiThread {
            if (!::hostedKeyboardController.isInitialized) return@runOnUiThread
            HostedTextInputSession.target?.let(hostedKeyboardController::begin)
                ?: hostedKeyboardController.end("editor no longer active")
        }
    }
    private val workspaceGuard = object : Runnable {
        override fun run() {
            if (!activityResumed || isFinishing || isDestroyed) return
            updateExternalDisplay()
            if (externalDisplay != null && !BeastActivity.isWorkspaceVisible() &&
                SystemClock.uptimeMillis() - lastWorkspaceLaunchAt >= WORKSPACE_RELAUNCH_GUARD_MS
            ) {
                ensureExternalWorkspace(preservePhoneUi = false)
            }
            window.decorView.postDelayed(this, WORKSPACE_GUARD_INTERVAL_MS)
        }
    }
    private val phoneDisplayRepair = Runnable { keepPhoneUiOnDefaultDisplay() }
    private val beastSearchFocus = Runnable { focusBeastSearchField() }
    private val sensitivityListener: (Float) -> Unit = { value -> inputController.sensitivity = value }
    private val appearanceListener: () -> Unit = {
        runOnUiThread {
            accentCommandViews.forEach { command ->
                command.setTextColor(InputSettings.accentColor)
                command.background = outlinedBackground(InputSettings.accentOutlineColor())
            }
            if (::embeddedKeyboardView.isInitialized) {
                embeddedKeyboardView.keyboardTheme = lateralKeyboardTheme()
            }
            if (::workspaceNavigator.isInitialized) workspaceNavigator.invalidate()
            if (::workspaceViewportSlider.isInitialized) workspaceViewportSlider.invalidate()
            if (::phoneTaskbarContainer.isInitialized) {
                phoneTaskbarContainer.visibility = if (InputSettings.showPhoneTaskbar) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        }
    }
    private val privilegedStateListener: () -> Unit = {
        runOnUiThread {
            if (::inputDebugText.isInitialized) {
                inputDebugText.text = privilegedStatusText()
            }
            if (PrivilegedService.state == PrivilegedService.State.READY) {
                BeastWorkspaceController.cleanupHistoricalTasks(this)
            } else if (::hostedKeyboardController.isInitialized) {
                hostedKeyboardController.onHelperUnavailable()
            }
            syncPairingNotification()
            maybeShowAdbBridgeDialog()
        }
    }
    private val workspaceStateListener: () -> Unit = {
        runOnUiThread {
            HostedTextInputSession.target?.let { target ->
                val task = WorkspaceState.tasks.firstOrNull { it.id == target.taskId }
                if (task == null || task.minimized) HostedTextInputSession.close(target.displayId)
            }
            if (::workspaceNavigator.isInitialized) {
                workspaceNavigator.submitWorkspace(
                    WorkspaceState.tasks,
                    WorkspaceState.focusedTaskId,
                )
            }
            if (::workspaceViewportSlider.isInitialized) {
                workspaceViewportSlider.setViewportPosition(WorkspaceState.viewportPosition)
            }
        }
    }
    private val viewportStateListener: (Float) -> Unit = { position ->
        runOnUiThread {
            if (::workspaceNavigator.isInitialized) {
                workspaceNavigator.submitWorkspace(
                    WorkspaceState.tasks, WorkspaceState.focusedTaskId,
                )
            }
            if (::workspaceViewportSlider.isInitialized) {
                workspaceViewportSlider.setViewportPosition(position)
            }
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginPairingSetup()
            } else {
                Toast.makeText(
                    this,
                    "Notification permission is required for shade-based code entry.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phoneUiContext = applicationContext
        livePhoneTaskId = taskId
        BeastWorkspaceController.cleanupLegacyTasks(this)
        // LATERAL is the active phone-side controller while Beast is running. Keep the
        // phone display awake for the duration of this visible activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        InputSettings.load(this)
        syncPhoneHostedAppConfiguration()
        beastDisplayModeController = BeastDisplayModeController(applicationContext)

        inputController = InputController { status ->
            runOnUiThread {
                if (::inputDebugText.isInitialized) {
                    inputDebugText.text = status
                }
            }
        }
        inputController.sensitivity = InputSettings.cursorSensitivity
        InputSettings.addSensitivityListener(sensitivityListener)
        InputSettings.addAppearanceListener(appearanceListener)

        PrivilegedService.mouseDeltaHandler = inputController::handleMouseDelta
        PrivilegedService.mouseButtonHandler = inputController::handleMouseButton
        PrivilegedService.addListener(privilegedStateListener)
        WorkspaceState.addListener(workspaceStateListener)
        WorkspaceState.addViewportListener(viewportStateListener)
        PrivilegedService.setHotkeyMonitoringEnabled(true)
        PrivilegedService.ensureRunning()

        displayManager = getSystemService(DisplayManager::class.java)

        buildUi()
        hostedKeyboardController = HostedKeyboardSessionController(
            context = this,
            onStateChanged = ::onHostedKeyboardStateChanged,
        )
        applyPhoneWindowFocusMode()
        LauncherSearchSession.addListener(beastSearchListener)
        HostedTextInputSession.addListener(hostedTextInputListener)
        hostedKeyboardController.recoverStaleSession {
            HostedTextInputSession.target?.let(hostedKeyboardController::begin)
        }
        syncBeastSearchField()
        updateExternalDisplay()
        // A Beast that was already plugged in before the PhoneUI launched will not
        // generate onDisplayAdded(), so explicitly enter its selector here as well.
        if (externalDisplay != null && intent?.action != ACTION_BEAST_LAUNCHER_SEARCH) {
            ensureExternalWorkspace(
                workspaceAction = BeastActivity.ACTION_ENSURE_WORKSPACE,
            )
        }
        if (PrivilegedService.state == PrivilegedService.State.READY) {
            BeastWorkspaceController.cleanupHistoricalTasks(this)
        }
        window.decorView.post(workspaceGuard)
        window.decorView.post(::syncPhoneHostedAppConfiguration)
        if (intent?.action == ACTION_BEAST_LAUNCHER_SEARCH) {
            window.decorView.post(::showBeastLauncherSearchOnPhone)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        window.decorView.post {
            if (intent.action == ACTION_PHONE_FOCUS_REPAIR) {
                // A foreground repair must not re-ensure Beast and start another
                // cross-display transition; it only brings this existing task front.
            } else if (intent.action == ACTION_BEAST_LAUNCHER_SEARCH) {
                // Beast already owns the open launcher. Re-ensuring it here creates a
                // second cross-display focus transition and can hide either UI.
                showBeastLauncherSearchOnPhone()
            } else {
                updateExternalDisplay()
                externalDisplay?.let { display ->
                    BeastWorkspaceController.ensure(this, display)
                }
            }
        }
    }

    override fun onDestroy() {
        if (::trackpad.isInitialized) trackpad.cancelActiveGesture()
        if (::inputController.isInitialized) inputController.cancelActiveButtons()
        if (::hostedKeyboardController.isInitialized) hostedKeyboardController.destroy()
        resetPhoneWindowFocusLeases()
        LauncherSearchSession.removeListener(beastSearchListener)
        HostedTextInputSession.removeListener(hostedTextInputListener)
        if (::connectionText.isInitialized) window.decorView.removeCallbacks(workspaceGuard)
        if (::connectionText.isInitialized) {
            window.decorView.removeCallbacks(phoneDisplayRepair)
            window.decorView.removeCallbacks(beastSearchFocus)
        }
        cancelPhoneFocusRepair()
        PrivilegedService.removeListener(privilegedStateListener)
        InputSettings.removeSensitivityListener(sensitivityListener)
        InputSettings.removeAppearanceListener(appearanceListener)
        WorkspaceState.removeListener(workspaceStateListener)
        WorkspaceState.removeViewportListener(viewportStateListener)
        PrivilegedService.mouseDeltaHandler = null
        PrivilegedService.mouseButtonHandler = null
        if (phoneUiContext === applicationContext) phoneUiContext = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        window.decorView.removeCallbacks(phoneDisplayRepair)
        livePhoneTaskId = taskId
        applyPhoneWindowFocusMode()
        if (::beastSearchField.isInitialized) syncBeastSearchField()
        syncPhoneHostedAppConfiguration()
        displayManager.registerDisplayListener(this, null)
        updateExternalDisplay()
        window.decorView.post(::syncPhoneHostedAppConfiguration)
        window.decorView.post(phoneDisplayRepair)
        window.decorView.post(workspaceGuard)
        window.decorView.post(::maybeShowAdbBridgeDialog)
        AndroidTaskSynchronizer.requestImmediate()
    }

    override fun onPause() {
        activityResumed = false
        if (::connectionText.isInitialized) {
            window.decorView.removeCallbacks(workspaceGuard)
            window.decorView.removeCallbacks(phoneDisplayRepair)
            window.decorView.removeCallbacks(beastSearchFocus)
        }
        cancelPhoneFocusRepair()
        if (::trackpad.isInitialized) trackpad.cancelActiveGesture()
        if (::inputController.isInitialized) inputController.cancelActiveButtons()
        resetPhoneWindowFocusLeases()
        if (::hostedKeyboardController.isInitialized) {
            if (HostedTextInputSession.target != null) {
                HostedTextInputSession.close()
            } else {
                hostedKeyboardController.end("PhoneUI paused")
            }
        }
        displayManager.unregisterDisplayListener(this)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncPhoneHostedAppConfiguration()
    }

    private fun syncPhoneHostedAppConfiguration() {
        val configuration = resources.configuration
        InputSettings.updatePhoneAppConfiguration(
            configuration.densityDpi,
            configuration.fontScale,
            window.decorView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels,
        )
    }

    // ---------------------------------------------------------
    // DISPLAY HANDLING
    // ---------------------------------------------------------

    private fun updateExternalDisplay() {

        if (!::connectionText.isInitialized || !::trackpad.isInitialized) return

        // LATERAL's task surfaces and screenshot helpers are virtual displays too.
        // Never pick one of those as the external target just because it appears before
        // the physical display in DisplayManager's enumeration.
        val previousDisplayId = externalDisplay?.displayId
        externalDisplay = BeastActivity.findExternalDisplay(this)
        if (previousDisplayId != externalDisplay?.displayId) {
            // Release the old stream before InputController swaps its target display.
            trackpad.cancelActiveGesture()
            HostedTextInputSession.close()
        }

        val display = externalDisplay

        if (display == null) {
            connectionText.text = "beast:none"
            trackpad.isEnabled = false
            inputController.setDisplay(null)
            launchedBeastDisplayId = null
            clearedExternalDisplayId = null
            restoredBeastTimingDisplayId = null
            beastTimingRestoreInFlight = false
        } else {
            inputController.setDisplay(display)

            val mode = display.mode
            val hardwareUltrawide = mode.physicalWidth >= 3000
            // The glasses' hardware switch can change modes without passing through
            // our settings UI. Treat the observed timing as authoritative so the
            // persisted toggle and the next settings panel always reflect reality.
            if (InputSettings.ultrawideEnabled != hardwareUltrawide) {
                InputSettings.setUltrawideEnabled(applicationContext, hardwareUltrawide)
            }

            // Some firmware carries a forced 1919x1200 viewport into Ultrawide.
            // Clear it once per connection; native pixel geometry then controls sizing.
            if (clearedExternalDisplayId != display.displayId) {
                clearedExternalDisplayId = display.displayId
                PrivilegedService.clearDisplayOverrideSize(display.displayId)
            }

            val shape = if (hardwareUltrawide) "uw" else "${mode.physicalWidth}"
            connectionText.text = if (BuildConfig.VITURE_SDK_ENABLED) {
                "beast:$shape"
            } else {
                "display:$shape"
            }

            trackpad.isEnabled = true
            restorePreferredBeastTiming(display)
        }
    }

    private fun restorePreferredBeastTiming(display: Display) {
        if (!BuildConfig.VITURE_SDK_ENABLED ||
            !beastDisplayModeController.isBeastConnected() ||
            restoredBeastTimingDisplayId == display.displayId || beastTimingRestoreInFlight
        ) return
        val timing = InputSettings.preferredBeastTiming ?: return
        val mode = display.mode
        if (mode.physicalWidth == timing.width && mode.physicalHeight == timing.height &&
            mode.refreshRate.roundToInt() == timing.refreshRate
        ) {
            restoredBeastTimingDisplayId = display.displayId
            return
        }

        beastTimingRestoreInFlight = true
        WorkspaceState.beginDisplayModeSwitch(timing.isUltrawide)
        beastDisplayModeController.setNativeTiming(
            timing.width,
            timing.height,
            timing.refreshRate,
        ) { result ->
            beastTimingRestoreInFlight = false
            result.onSuccess {
                restoredBeastTimingDisplayId = display.displayId
                InputSettings.setPreferredBeastTiming(applicationContext, timing)
            }.onFailure {
                WorkspaceState.cancelDisplayModeSwitch()
            }
        }
    }

    override fun onDisplayAdded(displayId: Int) {
        updateExternalDisplay()

        if (externalDisplay?.displayId == displayId) {
            ensureExternalWorkspace()
        }
    }

    override fun onDisplayRemoved(displayId: Int) {
        updateExternalDisplay()
    }

    override fun onDisplayChanged(displayId: Int) {
        if (externalDisplay?.displayId == displayId) HostedTextInputSession.close()
        updateExternalDisplay()
        // Some USB-C docks keep a Beast display object around while it is OFF, then
        // simply change its state to ON on the next plug-in. Treat that as a connect.
        if (externalDisplay?.displayId == displayId && launchedBeastDisplayId != displayId) {
            ensureExternalWorkspace()
        }
    }

    // ---------------------------------------------------------
    // UI
    // ---------------------------------------------------------

    private fun buildUi() {
        accentCommandViews.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 10, 11))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->

            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            val ime = insets.getInsets(
                WindowInsetsCompat.Type.ime()
            )

            view.setPadding(
                dp(16) + systemBars.left,
                dp(12) + systemBars.top,
                dp(16) + systemBars.right,
                dp(12) + maxOf(systemBars.bottom, ime.bottom) + dp(PHONE_GESTURE_GUARD_DP)
            )

            insets
        }

        connectionText = TextView(this).apply {
            text = "beast:none"
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(150, 162, 166))
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { showControllerSettings() }
        }

        // Holds operational feedback from launcher and display actions without competing
        // with the intentionally quiet controller surface.
        currentAppText = TextView(this).apply { visibility = View.GONE }

        trackpad = TrackpadView(this).apply {
            onMouseEvent = { event ->
                inputController.handleEvent(event)
            }
        }

        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        status.addView(phoneLabel(), LinearLayout.LayoutParams(0, dp(38), 1f))
        status.addView(connectionText, LinearLayout.LayoutParams(0, dp(38), 1f))

        workspaceNavigator = WorkspaceNavigatorView(this).apply {
            onTaskSelected = { taskId -> WorkspaceState.focus(taskId, reveal = true) }
            submitWorkspace(
                WorkspaceState.tasks, WorkspaceState.focusedTaskId,
            )
        }

        workspaceViewportSlider = WorkspaceViewportSliderView(this).apply {
            onViewportChanged = WorkspaceState::setViewportPosition
            setViewportPosition(WorkspaceState.viewportPosition)
        }
        phoneTaskbarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (InputSettings.showPhoneTaskbar) View.VISIBLE else View.GONE
            addView(
                workspaceNavigator,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)),
            )
        }

        inputDebugText = TextView(this).apply {
            text = privilegedStatusText()
            visibility = View.VISIBLE
            setTextColor(Color.rgb(150, 162, 166))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        // The selector lives on PhoneUI, but its selections are materialized only in
        // the externally routed Beast workspace (see showPhoneLauncher()).
        addCommand(controls, "apps") { showPhoneLauncher() }
        addCommand(controls, "disp") { showExternalDisplaySettings() }
        addCommand(controls, "set") { showInputSettings() }
        if (BuildConfig.FLAVOR == "dev") {
            addCommand(controls, "agent") { showAgentPanel() }
        }

        beastSearchField = PhoneProxyEditText(this).apply {
            hint = "Beast app search"
            isSingleLine = true
            typeface = Typeface.MONOSPACE
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(105, 119, 119))
            visibility = View.GONE
            addTextChangedListener(SimpleTextWatcher { LauncherSearchSession.updateQuery(it) })
            setOnEditorActionListener { _, _, event ->
                val enter = event == null ||
                    (event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (enter) LauncherSearchSession.submit()
                enter
            }
        }
        phoneProxyClose = TextView(this).apply {
            text = "done"
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextColor(ACCENT)
            background = outlinedBackground(InputSettings.accentOutlineColor())
            visibility = View.GONE
            setOnClickListener { LauncherSearchSession.close() }
        }
        phoneProxyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(beastSearchField, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(phoneProxyClose, LinearLayout.LayoutParams(dp(72), dp(48)).apply {
                marginStart = dp(6)
            })
        }
        embeddedKeyboardStatus = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.rgb(150, 162, 166))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val embeddedKeyboardClose = TextView(this).apply {
            text = "close"
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextColor(ACCENT)
            background = outlinedBackground(InputSettings.accentOutlineColor())
            setOnClickListener { HostedTextInputSession.close() }
        }
        val embeddedKeyboardHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(embeddedKeyboardStatus, LinearLayout.LayoutParams(0, dp(38), 1f))
            addView(embeddedKeyboardClose, LinearLayout.LayoutParams(dp(72), dp(36)))
        }
        embeddedKeyboardView = EmbeddedFlorisKeyboardView(this).apply {
            keyboardTheme = lateralKeyboardTheme()
            visibility = View.GONE
        }
        embeddedKeyboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(embeddedKeyboardHeader)
            addView(
                embeddedKeyboardView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(status)
        root.addView(rule())
        // Per-app actions stay within easy reach at the top of PhoneUI.
        root.addView(controls)
        root.addView(rule())
        // The optional task tabs live directly above the touchpad.
        root.addView(phoneTaskbarContainer)

        root.addView(
            trackpad,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // The viewport scrollbar remains available even when task tabs are hidden,
        // anchored at the bottom edge of the touchpad for quick horizontal navigation.
        root.addView(workspaceViewportSlider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(30),
        ))
        root.addView(inputDebugText)
        root.addView(currentAppText)
        root.addView(
            embeddedKeyboardContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        // Keep the phone-owned search field at the bottom of the resized PhoneUI, directly
        // above the IME when it is visible, without covering the touchpad or task navigator.
        root.addView(phoneProxyContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ))

        setContentView(root)
        // The keyboard remains GONE until an exact hosted editor is verified, but keeping
        // its Compose tree prepared avoids a visible cold-composition pause on first use.
        root.post { embeddedKeyboardView.precompose() }
    }

    private fun privilegedStatusText(): String = when {
        PrivilegedService.state != PrivilegedService.State.READY ->
            "Input bridge: ${PrivilegedService.state}"
        else -> "Input bridge: READY · embedded keyboard available"
    }

    private fun onHostedKeyboardStateChanged(
        state: HostedKeyboardSessionController.State,
        detail: String?,
    ) {
        if (!::embeddedKeyboardContainer.isInitialized) return
        val visible = state != HostedKeyboardSessionController.State.IDLE
        embeddedKeyboardContainer.visibility = if (visible) View.VISIBLE else View.GONE
        embeddedKeyboardView.visibility = if (
            state == HostedKeyboardSessionController.State.ACTIVE
        ) View.VISIBLE else View.GONE
        embeddedKeyboardStatus.text = when (state) {
            HostedKeyboardSessionController.State.IDLE -> ""
            HostedKeyboardSessionController.State.ACTIVATING -> detail ?: "Connecting keyboard…"
            HostedKeyboardSessionController.State.ACTIVE ->
                "typing in ${HostedTextInputSession.target?.label.orEmpty()}"
            HostedKeyboardSessionController.State.RESTORING -> "Closing keyboard…"
            HostedKeyboardSessionController.State.UNAVAILABLE ->
                detail ?: "Keyboard unavailable"
        }
    }

    private fun phoneLabel() = TextView(this).apply {
        text = "LATERAL_"
        textSize = 17f
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = .08f
        setTextColor(Color.rgb(235, 240, 239))
    }

    private fun rule() = View(this).apply {
        setBackgroundColor(Color.rgb(55, 66, 69))
    }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)) }

    private fun addCommand(parent: LinearLayout, label: String, action: () -> Unit) {
        val command = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(ACCENT)
            background = outlinedBackground(InputSettings.accentOutlineColor())
            isClickable = true
            setOnClickListener { action() }
        }
        accentCommandViews += command
        parent.addView(command, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        })
    }

    private fun outlinedBackground(stroke: Int) = GradientDrawable().apply {
        setColor(Color.rgb(15, 18, 19))
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showControllerSettings() {
        HostedTextInputSession.close()
        val focusLease = acquirePhoneWindowFocus()
        val entries = arrayOf(
            "Open Android settings on Beast",
            "Pair privileged input helper",
        )
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("LATERAL_ / config")
                .setItems(entries) { _, which ->
                    when (which) {
                        0 -> launchSettings()
                        1 -> if (android.os.Build.VERSION.SDK_INT >= 33 && !PairingNotifier.canPost(this)) {
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else beginPairingSetup()
                    }
                }
                .create()
                .also { dialog ->
                    dialog.setOnDismissListener { focusLease.release() }
                    dialog.show()
                }
        } catch (error: RuntimeException) {
            focusLease.release()
            Toast.makeText(this, error.message ?: "Could not open config", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInputSettings() {
        HostedTextInputSession.close()
        val focusLease = acquirePhoneWindowFocus()
        InputSettingsPanel.show(
            context = this,
            onModalVisibilityChanged = { visible -> if (!visible) focusLease.release() },
        )
    }

    private fun showAgentPanel() {
        HostedTextInputSession.close()
        val focusLease = acquirePhoneWindowFocus()
        AgentPanel.show(
            activity = this,
            controller = (application as LateralApp).agentController,
            onModalVisibilityChanged = { visible -> if (!visible) focusLease.release() },
        )
    }

    private fun showExternalDisplaySettings() {
        HostedTextInputSession.close()
        val focusLease = acquirePhoneWindowFocus()
        ExternalDisplayPanel.show(
            context = this,
            displayProvider = { externalDisplay },
            requestUltrawide = { enabled, complete ->
                WorkspaceState.beginDisplayModeSwitch(enabled)
                val refreshRate = InputSettings.preferredBeastTiming?.refreshRate
                    ?: externalDisplay?.mode?.refreshRate?.roundToInt()?.takeIf { it > 0 }
                    ?: 60
                val height = if (enabled) 1200 else {
                    externalDisplay?.mode?.physicalHeight?.takeIf { it == 1080 || it == 1200 }
                        ?: 1200
                }
                val timing = InputSettings.BeastTiming(
                    if (enabled) 3840 else 1920,
                    height,
                    refreshRate,
                )
                beastDisplayModeController.setNativeTiming(
                    timing.width,
                    timing.height,
                    timing.refreshRate,
                ) { result ->
                    result.onSuccess {
                        InputSettings.setPreferredBeastTiming(applicationContext, timing)
                        // Display callbacks, not a guessed delay, complete the handoff once
                        // the newly enumerated timing reports the requested geometry.
                        updateExternalDisplay()
                    }.onFailure {
                        WorkspaceState.cancelDisplayModeSwitch()
                    }
                    complete(result.map { enabled })
                }
            },
            requestDisplayMode = { display, mode, complete ->
                WorkspaceState.beginDisplayModeSwitch(mode.physicalWidth >= 3000)
                PrivilegedService.setUserPreferredDisplayMode(
                    display.displayId,
                    mode.physicalWidth,
                    mode.physicalHeight,
                    mode.refreshRate,
                ) { applied ->
                    if (applied) {
                        if (beastDisplayModeController.isBeastConnected()) {
                            InputSettings.setPreferredBeastTiming(
                                applicationContext,
                                InputSettings.BeastTiming(
                                    mode.physicalWidth,
                                    mode.physicalHeight,
                                    mode.refreshRate.roundToInt(),
                                ),
                            )
                        }
                        updateExternalDisplay()
                        complete(Result.success(Unit))
                    } else {
                        WorkspaceState.cancelDisplayModeSwitch()
                        complete(Result.failure(IllegalStateException("Display mode was not accepted")))
                    }
                }
            },
            isBeastDisplay = {
                BuildConfig.VITURE_SDK_ENABLED && beastDisplayModeController.isBeastConnected()
            },
            requestBeastNativeTiming = { width, height, refreshRate, complete ->
                WorkspaceState.beginDisplayModeSwitch(width >= 3000)
                beastDisplayModeController.setNativeTiming(width, height, refreshRate) { result ->
                    result.onSuccess {
                        InputSettings.setPreferredBeastTiming(
                            applicationContext,
                            InputSettings.BeastTiming(width, height, refreshRate),
                        )
                        updateExternalDisplay()
                    }.onFailure {
                        WorkspaceState.cancelDisplayModeSwitch()
                    }
                    complete(result)
                }
            },
            vitureSdkEnabled = BuildConfig.VITURE_SDK_ENABLED,
            onModalVisibilityChanged = { visible -> if (!visible) focusLease.release() },
        )
    }

    private fun beginPairingSetup() {
        if (!PairingNotifier.showPairingPrompt(this)) return
        Toast.makeText(
            this,
            "Open Wireless debugging, choose Pair device with pairing code, then reply from the notification shade.",
            Toast.LENGTH_LONG,
        ).show()
        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }

    /** Prompt once for each stable unavailable state while the PhoneUI is visible. */
    private fun maybeShowAdbBridgeDialog() {
        if (!activityResumed || isFinishing || isDestroyed || !::inputDebugText.isInitialized) return

        val state = PrivilegedService.state
        if (state == PrivilegedService.State.READY) {
            lastPromptedBridgeState = null
            adbBridgeDialog?.dismiss()
            return
        }
        if (state !in setOf(
                PrivilegedService.State.NEEDS_DEVELOPER_OPTIONS,
                PrivilegedService.State.NEEDS_WIRELESS_DEBUGGING,
                PrivilegedService.State.NEEDS_PAIRING,
            ) || lastPromptedBridgeState == state || adbBridgeDialog?.isShowing == true
        ) return

        lastPromptedBridgeState = state
        val focusLease = acquirePhoneWindowFocus()
        val pairingRequired = state == PrivilegedService.State.NEEDS_PAIRING
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.adb_bridge_dialog_title))
            .setMessage(
                when (state) {
                    PrivilegedService.State.NEEDS_DEVELOPER_OPTIONS ->
                        getString(R.string.adb_bridge_developer_options_message)
                    PrivilegedService.State.NEEDS_PAIRING ->
                        getString(R.string.adb_bridge_pairing_message)
                    else ->
                        getString(R.string.adb_bridge_wireless_message)
                },
            )
            .setNegativeButton(R.string.adb_bridge_dialog_later, null)
            .setPositiveButton(
                if (pairingRequired) R.string.adb_bridge_start_pairing
                else R.string.adb_bridge_open_wireless_debugging,
            ) { _, _ ->
                if (pairingRequired) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && !PairingNotifier.canPost(this)) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        beginPairingSetup()
                    }
                } else {
                    openWirelessDebuggingSettings()
                }
            }
            .create()
            .also { created ->
                adbBridgeDialog = created
                created.setOnDismissListener {
                    if (adbBridgeDialog === created) adbBridgeDialog = null
                    focusLease.release()
                }
            }
        try {
            dialog.show()
        } catch (error: RuntimeException) {
            adbBridgeDialog = null
            focusLease.release()
            Toast.makeText(this, error.message ?: "Could not open Wireless debugging", Toast.LENGTH_SHORT).show()
        }
    }

    /** Android has no stable public direct action; use it when present, then the tested fallback. */
    private fun openWirelessDebuggingSettings() {
        val direct = Intent(WIRELESS_DEBUGGING_SETTINGS_ACTION)
        if (direct.resolveActivity(packageManager) != null) {
            startActivity(direct)
            return
        }

        val developerOptions = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(SETTINGS_SHOW_FRAGMENT_EXTRA, WIRELESS_DEBUGGING_FRAGMENT)
        }
        try {
            startActivity(developerOptions)
        } catch (error: RuntimeException) {
            Toast.makeText(
                this,
                error.message ?: "Could not open Developer options",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun syncPairingNotification() {
        if (PrivilegedService.state == PrivilegedService.State.NEEDS_PAIRING) {
            PairingNotifier.showPairingPrompt(this)
        } else {
            PairingNotifier.cancel(this)
        }
    }

    private fun launchSettings() {

        val display = externalDisplay ?: return

        val intent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = display.displayId
        }

        try {
            startActivity(intent, options.toBundle())
            currentAppText.text =
                "Settings → Display ${display.displayId}"
        } catch (e: Exception) {
            Toast.makeText(
                this,
                e.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showPhoneLauncher() {
        if (externalDisplay == null) {
            Toast.makeText(this, "Connect an external display to open apps", Toast.LENGTH_SHORT).show()
            return
        }
        val focusLease = acquirePhoneWindowFocus()
        PhoneAppLauncherPanel.show(
            activity = this,
            onLaunch = { info ->
                WorkspaceState.launchNew(
                    info.activityInfo.packageName,
                    info.activityInfo.name,
                    info.loadLabel(packageManager).toString(),
                )
                ensureExternalWorkspace()
            },
            onOpen = { task ->
                WorkspaceState.focus(task.id, reveal = true)
                ensureExternalWorkspace()
            },
            onModalVisibilityChanged = { visible -> if (!visible) focusLease.release() },
        )
    }

    /** Phone-owned IME companion for the Beast launcher; results remain on BeastUI. */
    private fun showBeastLauncherSearchOnPhone() {
        // Give the shell move one traversal before focusing PhoneUI's in-layout field.
        keepPhoneUiOnDefaultDisplay()
        window.decorView.removeCallbacks(beastSearchFocus)
        window.decorView.postDelayed(beastSearchFocus, PHONE_DISPLAY_SETTLE_MS)
    }

    /** MainActivity is phone-only even when a cross-display singleTask launch races OEM policy. */
    private fun keepPhoneUiOnDefaultDisplay() {
        if (!activityResumed || isFinishing || isDestroyed) return
        if (display?.displayId == Display.DEFAULT_DISPLAY) {
            phoneDisplayRepairAttempts = 0
            return
        }
        if (PrivilegedService.state == PrivilegedService.State.READY) {
            PrivilegedService.restorePhoneTaskIfStillHome(taskId)
        }
        if (phoneDisplayRepairAttempts++ < MAX_PHONE_DISPLAY_REPAIR_ATTEMPTS) {
            window.decorView.postDelayed(phoneDisplayRepair, PHONE_DISPLAY_REPAIR_DELAY_MS)
        }
    }

    private fun focusBeastSearchField() {
        if (!activityResumed || isFinishing || isDestroyed || !LauncherSearchSession.active) return
        syncBeastSearchField()
        beastSearchField.requestFocus()
        beastSearchField.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(beastSearchField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Keeps Beast's query in PhoneUI without placing a modal window over the touchpad. */
    private fun syncBeastSearchField() {
        if (!LauncherSearchSession.active) {
            phoneProxyContainer.visibility = View.GONE
            beastSearchField.visibility = View.GONE
            phoneProxyClose.visibility = View.GONE
            if (beastSearchField.hasFocus()) {
                beastSearchField.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(beastSearchField.windowToken, 0)
            }
            beastSearchFocusLease?.release()
            beastSearchFocusLease = null
            return
        }
        if (beastSearchFocusLease == null) {
            beastSearchFocusLease = acquirePhoneWindowFocus()
        }
        phoneProxyContainer.visibility = View.VISIBLE
        beastSearchField.visibility = View.VISIBLE
        phoneProxyClose.visibility = View.GONE
        val query = LauncherSearchSession.query
        if (beastSearchField.text.toString() != query) {
            beastSearchField.setText(query)
            beastSearchField.setSelection(query.length)
        }
    }

    private fun ensureExternalWorkspace(
        preservePhoneUi: Boolean = true,
        workspaceAction: String = BeastActivity.ACTION_ENSURE_WORKSPACE,
    ) {
        val display = externalDisplay ?: run {
            Toast.makeText(this, "Connect an external display to open apps", Toast.LENGTH_SHORT).show()
            return
        }

        if (BeastWorkspaceController.ensure(this, display, workspaceAction)) {
            launchedBeastDisplayId = display.displayId
            lastWorkspaceLaunchAt = SystemClock.uptimeMillis()
            // Launching or signalling an Activity on REDMAGIC's external display can
            // transiently promote that task globally and expose Home on display 0.
            // A user-originated PhoneUI action must leave the same PhoneUI task in
            // front; watchdog recovery deliberately does not steal the phone back.
            if (preservePhoneUi) schedulePhoneFocusRepair(180L)
        } else {
            Toast.makeText(this, "Could not open Beast workspace", Toast.LENGTH_SHORT).show()
        }
    }

    private fun schedulePhoneFocusRepair(delayMs: Long) {
        cancelPhoneFocusRepair()
        val generation = phoneFocusRepairGeneration
        val delays = longArrayOf(delayMs, 260L, 500L, 900L, 1_400L)
        var attempt = 0
        val repair = object : Runnable {
            override fun run() {
                if (generation != phoneFocusRepairGeneration || !activityResumed ||
                    isFinishing || isDestroyed || PrivilegedService.state != PrivilegedService.State.READY
                ) {
                    pendingPhoneFocusRepair = null
                    return
                }
                val restored = PrivilegedService.restorePhoneTaskIfStillHome(taskId)
                attempt++
                if (!restored && attempt < delays.size) {
                    window.decorView.postDelayed(this, delays[attempt])
                } else {
                    pendingPhoneFocusRepair = null
                }
            }
        }
        pendingPhoneFocusRepair = repair
        window.decorView.postDelayed(repair, delays[0])
    }

    private fun cancelPhoneFocusRepair() {
        phoneFocusRepairGeneration++
        pendingPhoneFocusRepair?.let(window.decorView::removeCallbacks)
        pendingPhoneFocusRepair = null
    }

    /**
     * PhoneUI is normally a touchable controller that cannot take window focus away
     * from a hosted app. Explicit phone-side text and modal UI temporarily lease focus.
     */
    private fun acquirePhoneWindowFocus(): PhoneWindowFocusLease {
        val token = nextPhoneFocusLeaseToken++
        phoneFocusLeaseTokens += token
        applyPhoneWindowFocusMode()
        return PhoneWindowFocusLease(token)
    }

    private fun applyPhoneWindowFocusMode() {
        if (phoneFocusLeaseTokens.isEmpty()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    private fun resetPhoneWindowFocusLeases() {
        phoneFocusLeaseTokens.clear()
        beastSearchFocusLease = null
        applyPhoneWindowFocusMode()
    }

    private inner class PhoneWindowFocusLease(private val token: Long) {
        private var released = false

        fun release() {
            if (released) return
            released = true
            if (phoneFocusLeaseTokens.remove(token)) applyPhoneWindowFocusMode()
        }
    }

}
