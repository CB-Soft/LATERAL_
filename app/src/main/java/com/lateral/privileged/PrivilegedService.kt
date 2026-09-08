package com.lateral.privileged

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrates UxSpace's own shell-uid helper: pairs with the device's wireless-debugging
 * dialog, discovers and connects to the ADB connect service, starts a [PrivilegedServer]
 * over `app_process`, and exposes the privileged-call surface UxSpace uses to launch apps,
 * inject input, and create trusted virtual displays.
 *
 * Built in stage 4 of [docs/PRIVILEGE.md]; not yet wired by UxSpaceApp — the app still goes
 * through [ShizukuManager]. Stage 5 swaps the wiring and removes the Shizuku path.
 *
 * Lifecycle:
 *
 *  - First run, after [activate] pairs with a 6-digit code, the ADB identity (key + cert)
 *    is persisted; the device trusts it permanently.
 *  - Every launch, [ensureRunning] discovers the connect port via mDNS, connects, and
 *    starts the server. The returned [AdbStream] is held open so the server stays alive;
 *    closing it (or the app dying) tears the server down.
 *  - The server sends its Binder back through [BinderReceiverProvider]; that arrives at
 *    [onPrivilegedBinder] and moves the state machine to [State.READY].
 */
object PrivilegedService {

    enum class ImeRoutingState { CHECKING, AVAILABLE, UNAVAILABLE }

    enum class FocusRestoreResult(val code: Int, val targetRestored: Boolean) {
        SUCCESS(0, true),
        TARGET_RESTORE_FAILED(1, false),
        PHONE_FOCUS_FAILED(2, true),
        VERIFICATION_FAILED(3, true),
        GUARD_SETUP_FAILED(4, true),
        HELPER_UNAVAILABLE(-1, false),
        UNKNOWN(-2, false);

        companion object {
            fun fromCode(code: Int): FocusRestoreResult = entries.firstOrNull { it.code == code }
                ?: UNKNOWN
        }
    }

    sealed interface HostedDisplayResult {
        data class Ready(val displayId: Int) : HostedDisplayResult
        data object HelperUnavailable : HostedDisplayResult
        data object KeyboardRoutingUnavailable : HostedDisplayResult
        data object DisplayCreationFailed : HostedDisplayResult
    }

    data class RecentTask(
        val taskId: Int,
        val lastActiveTime: Long,
        val isRunning: Boolean,
        val packageName: String,
        val activityName: String,
        val title: String,
    )

    data class TaskSnapshot(
        val taskId: Int,
        val overviewRank: Int,
        val lastActiveTime: Long,
        val isRunning: Boolean,
        val hasBeenVisible: Boolean,
        val displayId: Int,
        val isVisible: Boolean,
        val isFocused: Boolean,
        val activityType: Int,
        val excluded: Boolean,
        val supportsMultiWindow: Boolean,
        val packageName: String,
        val activityName: String,
        val title: String,
        val isAvailable: Boolean,
    )

    data class FocusedEditorInfo(val inputType: Int, val imeOptions: Int)

    enum class SessionImeResult(val code: Int) {
        SUCCESS(0), QUERY_FAILED(1), ENABLE_FAILED(2), SELECT_FAILED(3),
        VERIFY_FAILED(4), OWNER_DEAD(5), NOT_SELECTED(6), NO_PREVIOUS(7),
        RESTORE_FAILED(8), HELPER_UNAVAILABLE(-1), UNKNOWN(-2);

        companion object {
            fun fromCode(code: Int): SessionImeResult =
                entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    data class SessionImeActivation(
        val result: SessionImeResult,
        val previousImeId: String,
        val selectedImeId: String,
    )

    data class ImeClientSnapshot(
        val selectedImeId: String,
        val displayId: Int,
        val taskId: Int,
        val packageName: String,
        val inputType: Int,
        val imeOptions: Int,
    )

    private const val TAG = "Lateral/Privileged"

    /** Steps the user (or UxSpace) must clear before privileged calls work. */
    enum class State {
        /** Android < 11 — the wireless-debugging path does not exist. */
        UNSUPPORTED,

        /**
         * Developer options is locked. Wireless Debugging can't be turned on until the user
         * unlocks Developer options (Settings → About → tap Build number 7×), so the wizard
         * walks them through that first.
         */
        NEEDS_DEVELOPER_OPTIONS,

        /** Paired before, but the connect service is not on mDNS (wireless debugging off). */
        NEEDS_WIRELESS_DEBUGGING,

        /**
         * Never paired (or the device forgot our key — connect failed with
         * AdbPairingRequiredException, which clears the paired marker). The wizard asks for
         * a pairing code.
         */
        NEEDS_PAIRING,

        /** mDNS / TCP work in progress. */
        DISCOVERING,
        CONNECTING,
        STARTING,

        /** Helper bound — privileged calls go through. */
        READY,
    }

    @Volatile
    var state: State = State.NEEDS_PAIRING
        private set

    @Volatile
    var imeRoutingState: ImeRoutingState = ImeRoutingState.CHECKING
        private set

    @Volatile
    var imeRoutingError: String = ""
        private set

    @Volatile
    private var service: IPrivilegedService? = null

    /** Held to keep the helper alive — closing the stream brings the server down with it. */
    @Volatile
    private var adbStream: AdbStream? = null

    private var appContext: Context? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lateral-privileged") }
    /** Pinch pacing must never occupy the lane used by clicks, keys, and task actions. */
    private val pinchWorker = Executors.newSingleThreadExecutor { Thread(it, "lateral-pinch") }
    private val pendingPinch = AtomicReference<PinchRequest?>(null)
    private val pinchDrainScheduled = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "lateral-privileged-sched")
    }

    private data class PinchRequest(
        val displayId: Int,
        val centerX: Int,
        val centerY: Int,
        val fromSpan: Int,
        val toSpan: Int,
        val durationMs: Int,
    )

    /** Call once, from the Application. Computes the initial state from persisted identity. */
    fun init(context: Context) {
        appContext = context.applicationContext
        state = computeInitialState(context)
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun computeInitialState(context: Context): State = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> State.UNSUPPORTED
        !isDevOptionsEnabled(context) -> State.NEEDS_DEVELOPER_OPTIONS
        hasPaired(context) -> State.NEEDS_WIRELESS_DEBUGGING
        else -> State.NEEDS_PAIRING
    }

    /** Developer options unlocked? Read-only access to Settings.Global; no permission needed. */
    private fun isDevOptionsEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) != 0
    }.getOrDefault(false)

    /**
     * Whether UxSpace has previously paired successfully. A separate marker file from the key
     * — the key is generated and persisted on first use of [AdbConnectionManager], whether or
     * not the device ever accepted it, so the key file's existence proves nothing.
     */
    private fun hasPaired(context: Context): Boolean =
        File(context.filesDir, PAIRED_MARKER).exists()

    private fun markPaired(context: Context, paired: Boolean) {
        val marker = File(context.filesDir, PAIRED_MARKER)
        if (paired) {
            marker.parentFile?.mkdirs()
            runCatching { marker.createNewFile() }
        } else {
            runCatching { marker.delete() }
        }
    }

    private fun setState(next: State) {
        if (next == state) return
        Log.i(TAG, "state: $state -> $next")
        state = next
        notifyListeners()
    }

    private fun setImeRoutingState(next: ImeRoutingState) {
        if (next == imeRoutingState) return
        Log.i(TAG, "IME routing: $imeRoutingState -> $next")
        imeRoutingState = next
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }

    /**
     * Try to make the helper live — discover the connect port via mDNS, connect, start the
     * server. Idempotent; safe to call from anywhere. The state machine reflects progress.
     */
    fun ensureRunning() {
        worker.execute {
            val ctx = appContext ?: return@execute
            if (state == State.UNSUPPORTED) return@execute
            if (state == State.READY && service != null) return@execute
            // Bring-up already in flight: a previous ensureRunning() task is spawning
            // a server and its binder hasn't published yet. Without this guard, every
            // call between the spawn and the READY transition fires another server,
            // and each one runs its own /dev/input/event* HotkeyMonitor — observed in
            // logcat as three identical "hotkey monitor started" lines and 3× FD use
            // on every input device. The dropped binders are leaked; the displaced
            // server processes never exit.
            if (state == State.DISCOVERING ||
                state == State.CONNECTING ||
                state == State.STARTING
            ) {
                Log.d(TAG, "ensureRunning ignored — bring-up already in flight (state=$state)")
                return@execute
            }
            // Re-evaluate prerequisites on every attempt — the user may have just unlocked
            // Developer options or toggled Wireless Debugging.
            if (!isDevOptionsEnabled(ctx)) {
                setState(State.NEEDS_DEVELOPER_OPTIONS)
                return@execute
            }
            if (!hasPaired(ctx)) {
                setState(State.NEEDS_PAIRING)
                return@execute
            }

            setState(State.DISCOVERING)
            val endpoints = AdbDiscovery.discoverConnect(ctx, DISCOVERY_TIMEOUT_MS)
            if (endpoints.isEmpty()) {
                Log.w(TAG, "connect service not advertised — wireless debugging off?")
                setState(State.NEEDS_WIRELESS_DEBUGGING)
                return@execute
            }

            val adb = AdbConnectionManager.getInstance(ctx)
            if (!adb.isConnected) {
                setState(State.CONNECTING)
                // Android can advertise a stale `_adb-tls-connect._tcp` next to the active
                // one; try each until one accepts the connect, so a refused stale endpoint
                // doesn't masquerade as "wireless debugging off".
                var connected = false
                var lastError: Exception? = null
                for (endpoint in endpoints) {
                    try {
                        if (adb.connect(endpoint.host, endpoint.port)) {
                            connected = true
                            break
                        }
                        Log.w(TAG, "ADB connect to ${endpoint.host}:${endpoint.port} returned false")
                    } catch (e: AdbPairingRequiredException) {
                        // The device no longer trusts our key — wipe the marker and walk
                        // the user back through pairing. No point trying other endpoints.
                        Log.w(TAG, "device requires (re-)pairing — invalidating the paired marker")
                        markPaired(ctx, false)
                        setState(State.NEEDS_PAIRING)
                        return@execute
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(
                            TAG,
                            "ADB connect to ${endpoint.host}:${endpoint.port} failed: ${e.message}",
                        )
                    }
                }
                if (!connected) {
                    Log.e(
                        TAG,
                        "all ${endpoints.size} connect endpoint(s) refused — wireless debugging off?",
                        lastError,
                    )
                    setState(State.NEEDS_WIRELESS_DEBUGGING)
                    return@execute
                }
            }

            setState(State.STARTING)
            val stream = ServerBootstrap.start(ctx, adb)
            if (stream == null) {
                Log.w(TAG, "could not start PrivilegedServer over ADB")
                setState(State.NEEDS_WIRELESS_DEBUGGING)
                return@execute
            }
            adbStream = stream
            // BinderReceiverProvider.onPrivilegedBinder() drives us to READY when the server
            // publishes its Binder back to the app.
        }
    }

    /**
     * Pair using the 6-digit [pairingCode] from Wireless Debugging's "Pair device with a
     * pairing code" dialog. The pairing port is discovered via mDNS — this only works
     * while the dialog is still in the foreground, which is why UxSpace reads the code
     * from a notification's `RemoteInput` (the shade overlays the dialog instead of
     * backgrounding it). [done] runs on the worker thread.
     */
    fun activate(pairingCode: String, done: (Boolean) -> Unit) {
        worker.execute {
            val ctx = appContext ?: run { done(false); return@execute }
            try {
                val endpoint = AdbDiscovery.discoverPairing(ctx, DISCOVERY_TIMEOUT_MS)
                if (endpoint == null) {
                    Log.w(TAG, "pair: mDNS did not find the service — is the dialog still up?")
                    markPaired(ctx, false)
                    done(false); return@execute
                }
                val adb = AdbConnectionManager.getInstance(ctx)
                val paired = adb.pair(endpoint.host, endpoint.port, pairingCode)
                Log.i(TAG, "ADB pair ${endpoint.host}:${endpoint.port} ok=$paired")
                markPaired(ctx, paired)
                if (!paired) { done(false); return@execute }
                done(true)
                ensureRunning()
            } catch (e: Exception) {
                Log.e(TAG, "activate failed", e)
                markPaired(ctx, false)
                done(false)
            }
        }
    }

    /**
     * Called by [BinderReceiverProvider] when [PrivilegedServer] hands its Binder back. The
     * provider gates this on the calling uid (shell or self), so this is reachable only
     * from the server we started.
     */
    fun onPrivilegedBinder(binder: IBinder) {
        if (!binder.pingBinder()) {
            Log.w(TAG, "onPrivilegedBinder: ping failed")
            return
        }
        // Displace any previously-bound server. Without this, the spawn-race window
        // can hand us a second (or third) binder for a separate PrivilegedServer
        // process — each one running its own /dev/input/event* HotkeyMonitor and
        // its own native input injector. The orphans never exit on their own.
        val previous = service
        if (previous != null) {
            Log.w(TAG, "displacing previously-bound privileged service — calling exit()")
            runCatching { previous.setHotkeyListener(null) }
            runCatching { previous.exit() }
        }
        service = IPrivilegedService.Stub.asInterface(binder)
        // Clear a preferred external route written by older LATERAL_ builds. The phone's
        // system audio policy owns the active route (including the user's "This Phone"
        // selection); LATERAL_ must never overwrite it with USB/wired routing.
        runCatching { service?.setBeastMediaRoutingEnabled(false) }
            .onFailure { Log.e(TAG, "could not clear legacy Beast media routing", it) }
        setImeRoutingState(ImeRoutingState.CHECKING)
        Log.i(TAG, "privileged binder bound — READY")
        // Only install the hotkey listener if a keyboard is actually attached. With
        // no keyboard the helper opens no /dev/input/event* nodes at all — minimum
        // footprint, no contention against the glasses' USB IMU endpoint on OEMs
        // (Samsung) where BT input attach disturbs USB hosts.
        if (hotkeyMonitoringRequested) installHotkeyListener()
        setState(State.READY)
    }

    /**
     * App-level dispatcher for the Ctrl+Alt+X global hotkeys the shell-uid server captures
     * off `/dev/input/event*`. Set once by the app at startup (alongside the other
     * `WorkspaceController.*` lambdas in `UxSpaceApp.onCreate`). Invoked on the main thread.
     * Codes are [PrivilegedHotkeys].HK_*.
     */
    @Volatile
    var hotkeyHandler: ((code: Int) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hotkeyListenerStub = object : IPrivilegedHotkeyListener.Stub() {
        override fun onHotkey(code: Int) {
            // Binder thread → main thread; controller methods aren't always main-only,
            // but the actions (zoom presets, view mode, recenter) ultimately touch
            // the renderer and UI, so jumping once here is safest.
            mainHandler.post { hotkeyHandler?.invoke(code) }
        }
        override fun onMouseDelta(dx: Int, dy: Int, wheel: Int) {
            // Raw evdev mouse deltas — never quantised to the phone-screen edge.
            mainHandler.post { mouseDeltaHandler?.invoke(dx, dy, wheel) }
        }
        override fun onMouseButton(code: Int, pressed: Boolean) {
            // With the helper holding EVIOCGRAB on the mouse, button events never
            // reach `dispatchGenericMotionEvent` either — so we have to forward
            // them here and the app dispatches workspace clicks ourselves.
            mainHandler.post { mouseButtonHandler?.invoke(code, pressed) }
        }
    }

    /**
     * App-level handler for raw evdev mouse motion (REL_X, REL_Y, REL_WHEEL batched
     * per SYN_REPORT). Set once by the app at startup. dx/dy are kernel ticks;
     * the handler is responsible for any scaling to workspace NDC units.
     */
    @Volatile
    var mouseDeltaHandler: ((dx: Int, dy: Int, wheel: Int) -> Unit)? = null

    /**
     * App-level handler for mouse button transitions (BTN_LEFT/RIGHT/MIDDLE
     * press + release). [code] is the kernel BTN_* number, [pressed] true on
     * press / false on release.
     */
    @Volatile
    var mouseButtonHandler: ((code: Int, pressed: Boolean) -> Unit)? = null

    private fun installHotkeyListener() {
        val helper = service ?: return
        runCatching {
            helper.setHotkeyListener(hotkeyListenerStub)
        }.onFailure { Log.w(TAG, "setHotkeyListener failed", it) }
    }

    /**
     * Whether the app has currently asked the helper to monitor hotkeys. Controlled by
     * [setHotkeyMonitoringEnabled] from the keyboard-presence listener in MainActivity;
     * stays true once any keyboard appears so the next privileged-binder bind installs
     * the listener immediately.
     */
    @Volatile
    private var hotkeyMonitoringRequested: Boolean = false

    /**
     * Toggle the shell-uid HotkeyMonitor based on whether a physical keyboard is
     * attached. When `enabled = false` the listener Binder is nulled out, the helper
     * tears down its FileObserver + reader threads + every `/dev/input/event*` FD.
     * When `enabled = true` it re-opens them and resumes Ctrl+Alt+X dispatch.
     */
    fun setHotkeyMonitoringEnabled(enabled: Boolean) {
        if (hotkeyMonitoringRequested == enabled) return
        hotkeyMonitoringRequested = enabled
        Log.i(TAG, "hotkey monitoring requested -> $enabled")
        val helper = service ?: return
        runCatching {
            helper.setHotkeyListener(if (enabled) hotkeyListenerStub else null)
        }.onFailure { Log.w(TAG, "setHotkeyListener toggle failed", it) }
    }

    /**
     * Force-detach and re-attach the VITURE glasses' USB device via the helper, so the
     * kernel re-enumerates it. Triggered by the app's DOF-stall watchdog after the SDK
     * stops producing poses for an extended period — observed empirically that a stale
     * Carina USB endpoint sometimes only recovers from a bus reset, which the BT-pair
     * USB churn occasionally does on its own. Returns immediately; the actual rescan
     * runs on the helper's worker thread and the resulting USB_DEVICE_ATTACHED intent
     * re-triggers [com.uxspace.spatial.WorkspacePresentation.retryHeadTracking].
     */
    fun rescanGlassesUsb() {
        val helper = service ?: run {
            Log.w(TAG, "rescanGlassesUsb ignored — helper not READY (state=$state)")
            return
        }
        worker.execute {
            runCatching { helper.rescanGlassesUsb() }
                .onFailure { Log.w(TAG, "rescanGlassesUsb failed", it) }
        }
    }

    // region Privileged-call surface (mirrors ShizukuManager so UxSpaceApp swaps cleanly)

    fun launchApp(displayId: Int, packageName: String, activityName: String) {
        Log.i(
            "Lateral/Launch",
            "7) PrivilegedService.launchApp pkg=$packageName display=$displayId state=$state",
        )
        val helper = service
        if (helper == null) {
            Log.w(TAG, "launchApp ignored — not READY (state=$state)")
            return
        }
        worker.execute {
            runCatching {
                Log.i(
                    "Lateral/Launch",
                    "8) helper.launchOnDisplay AIDL pkg=$packageName display=$displayId",
                )
                val ok = helper.launchOnDisplay(displayId, packageName, activityName)
                Log.i(
                    "Lateral/Launch",
                    "9) helper returned ok=$ok pkg=$packageName display=$displayId",
                )
            }.onFailure { Log.e(TAG, "launchApp failed", it) }
        }
    }

    fun createHostedVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        destroyContentOnRemoval: Boolean = true,
    ): HostedDisplayResult {
        val helper = service
        if (helper == null) {
            Log.w(TAG, "createHostedVirtualDisplay ignored — not READY (state=$state)")
            return HostedDisplayResult.HelperUnavailable
        }
        val result = runCatching {
            helper.createVirtualDisplayWithPolicy(
                name, width, height, densityDpi, surface, destroyContentOnRemoval,
            )
        }.onFailure { Log.e(TAG, "createHostedVirtualDisplay failed", it) }.getOrNull()
            ?: return HostedDisplayResult.DisplayCreationFailed
        return when {
            result >= 0 -> {
                imeRoutingError = ""
                setImeRoutingState(ImeRoutingState.AVAILABLE)
                HostedDisplayResult.Ready(result)
            }
            result == IME_ROUTING_UNAVAILABLE -> {
                imeRoutingError = runCatching { helper.imeRoutingError }.getOrDefault("")
                if (imeRoutingError.isNotBlank()) {
                    Log.e(TAG, "Keyboard routing unavailable: $imeRoutingError")
                }
                setImeRoutingState(ImeRoutingState.UNAVAILABLE)
                HostedDisplayResult.KeyboardRoutingUnavailable
            }
            else -> HostedDisplayResult.DisplayCreationFailed
        }
    }

    /** Reassert and verify native IME routing before a retained display is used. */
    fun ensureHostedDisplayImeRouting(displayId: Int): Boolean {
        val helper = service ?: return false
        val available = runCatching {
            helper.applyDefaultDisplayImePolicy(displayId) &&
                helper.getDisplayImePolicy(displayId) == DISPLAY_IME_POLICY_FALLBACK_DISPLAY
        }.onFailure { Log.e(TAG, "IME routing verification failed for display $displayId", it) }
            .getOrDefault(false)
        imeRoutingError = if (available) "" else runCatching {
            helper.imeRoutingError
        }.getOrDefault("")
        setImeRoutingState(
            if (available) ImeRoutingState.AVAILABLE else ImeRoutingState.UNAVAILABLE,
        )
        return available
    }

    fun recentTasks(): List<RecentTask> {
        val helper = service ?: return emptyList()
        return runCatching {
            helper.recentTasks.orEmpty().mapNotNull { encoded ->
                val fields = encoded.split('\t')
                if (fields.size !in 5..6) return@mapNotNull null
                RecentTask(
                    taskId = fields[0].toInt(),
                    lastActiveTime = fields[1].toLong(),
                    isRunning = fields[2].toBooleanStrictOrNull() ?: false,
                    packageName = fields[3],
                    activityName = fields[4],
                    title = fields.getOrElse(5) { "" },
                )
            }
        }.onFailure { Log.e(TAG, "recentTasks failed", it) }.getOrDefault(emptyList())
    }

    private fun taskSnapshotResponse(): Pair<Boolean, List<TaskSnapshot>> {
        val helper = service ?: return false to emptyList()
        return runCatching {
            val encodedSnapshots = helper.taskSnapshots.orEmpty()
            if (encodedSnapshots.any { it.startsWith("!error\t") }) return@runCatching false to emptyList()
            true to encodedSnapshots.mapNotNull { encoded ->
                val fields = encoded.split('\t')
                if (fields.size < 15 || fields[0] != "v1") return@mapNotNull null
                TaskSnapshot(
                    taskId = fields[1].toInt(),
                    overviewRank = fields[2].toInt(),
                    lastActiveTime = fields[3].toLong(),
                    isRunning = fields[4].toBooleanStrictOrNull() ?: false,
                    hasBeenVisible = fields[5].toBooleanStrictOrNull() ?: false,
                    displayId = fields[6].toInt(),
                    isVisible = fields[7].toBooleanStrictOrNull() ?: false,
                    isFocused = fields[8].toBooleanStrictOrNull() ?: false,
                    activityType = fields[9].toInt(),
                    excluded = fields[10].toBooleanStrictOrNull() ?: false,
                    supportsMultiWindow = fields[11].toBooleanStrictOrNull() ?: true,
                    packageName = fields[12],
                    activityName = fields[13],
                    title = fields[14],
                    // Appended compatibly to v1 after REDMAGIC exposed unavailable
                    // persistent records alongside legitimate cached Overview tasks.
                    isAvailable = fields.getOrNull(15)?.toBooleanStrictOrNull() ?: true,
                )
            }
        }.onFailure { Log.e(TAG, "taskSnapshots failed", it) }
            .getOrDefault(false to emptyList())
    }

    fun taskSnapshots(): List<TaskSnapshot> = taskSnapshotResponse().second

    fun queryTaskSnapshots(callback: (List<TaskSnapshot>?) -> Unit) {
        worker.execute {
            val (success, snapshots) = taskSnapshotResponse()
            mainHandler.post { callback(snapshots.takeIf { success }) }
        }
    }

    fun rewriteRecentTaskOrder(
        taskIdsNewestFirst: IntArray,
        displayIds: IntArray,
        callback: (Boolean) -> Unit,
    ) {
        worker.execute {
            val result = runCatching {
                service?.rewriteRecentTaskOrder(taskIdsNewestFirst, displayIds) == true
            }.getOrDefault(false)
            mainHandler.post { callback(result) }
        }
    }

    fun startRecentTaskOnDisplay(taskId: Int, displayId: Int): Boolean {
        val helper = service ?: return false
        return runCatching { helper.startRecentTaskOnDisplay(taskId, displayId) }
            .onFailure { Log.e(TAG, "startRecentTaskOnDisplay failed", it) }
            .getOrDefault(false)
    }

    /**
     * Repairs the phone display only when a task transition exposed Home. A different
     * top task is treated as an intentional user task switch and is never overridden.
     */
    fun restorePhoneTaskIfStillHome(taskId: Int): Boolean {
        val helper = service ?: return false
        return runCatching { helper.restorePhoneTaskIfStillHome(taskId) }
            .onFailure { Log.e(TAG, "restorePhoneTaskIfStillHome failed", it) }
            .getOrDefault(false)
    }

    fun restorePhoneTaskIfStillHomeAsync(taskId: Int) {
        worker.execute { restorePhoneTaskIfStillHome(taskId) }
    }

    fun focusPhoneTaskAsync(taskId: Int, callback: (Boolean) -> Unit) {
        worker.execute {
            val focused = startRecentTaskOnDisplay(taskId, android.view.Display.DEFAULT_DISPLAY)
            mainHandler.post { callback(focused) }
        }
    }

    /** Exact-task move + PhoneUI recovery, executed as one helper-side transaction. */
    fun restoreTaskPreservingPhoneFocus(
        taskId: Int,
        beastDisplayId: Int,
        phoneTaskId: Int,
    ): FocusRestoreResult {
        val helper = service ?: return FocusRestoreResult.HELPER_UNAVAILABLE
        return runCatching {
            FocusRestoreResult.fromCode(
                helper.restoreTaskPreservingPhoneFocus(taskId, beastDisplayId, phoneTaskId),
            )
        }.onFailure { Log.e(TAG, "focus-preserving restore failed for task $taskId", it) }
            .getOrDefault(FocusRestoreResult.UNKNOWN)
    }

    fun restoreTaskPreservingPhoneFocusAsync(
        taskId: Int,
        beastDisplayId: Int,
        phoneTaskId: Int,
        callback: (FocusRestoreResult) -> Unit,
    ) {
        worker.execute {
            val result = restoreTaskPreservingPhoneFocus(taskId, beastDisplayId, phoneTaskId)
            mainHandler.post { callback(result) }
        }
    }

    fun removeTask(taskId: Int): Boolean {
        val helper = service ?: return false
        return runCatching { helper.removeTask(taskId) }
            .onFailure { Log.e(TAG, "removeTask failed for $taskId", it) }
            .getOrDefault(false)
    }

    fun removeTaskAsync(taskId: Int, callback: (Boolean) -> Unit) {
        worker.execute {
            val removed = removeTask(taskId)
            mainHandler.post { callback(removed) }
        }
    }

    fun releaseVirtualDisplay(displayId: Int) =
        onWorker { service?.releaseVirtualDisplay(displayId) }

    fun resizeVirtualDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int) =
        onWorker { service?.resizeVirtualDisplay(displayId, width, height, densityDpi) }

    fun clearDisplayOverrideSize(displayId: Int) =
        onWorker { service?.clearDisplayOverrideSize(displayId) }

    fun setUserPreferredDisplayMode(
        displayId: Int,
        width: Int,
        height: Int,
        refreshRate: Float,
        callback: (Boolean) -> Unit,
    ) {
        worker.execute {
            val applied = runCatching {
                service?.setUserPreferredDisplayMode(displayId, width, height, refreshRate) == true
            }.onFailure { Log.e(TAG, "setUserPreferredDisplayMode failed", it) }.getOrDefault(false)
            mainHandler.post { callback(applied) }
        }
    }

    /** Clears a legacy LATERAL_-owned external media preference, if present. */
    fun setBeastMediaRoutingEnabled(enabled: Boolean) =
        onWorker { service?.setBeastMediaRoutingEnabled(enabled) }

    fun setVirtualDisplaySurface(displayId: Int, surface: Surface) =
        onWorker { service?.setVirtualDisplaySurface(displayId, surface) }

    /**
     * Inject an ACTION_SCROLL motion event into the app on [displayId] at content
     * pixel (x, y). Identical path to `UiScreen.dispatchScroll` but routed through the
     * shell-uid helper so the event reaches an out-of-process VirtualDisplay; one
     * AIDL call per frame, no swipe synthesis.
     */
    fun scrollOnDisplay(displayId: Int, x: Int, y: Int, vScroll: Float) =
        onWorker { service?.injectScroll(displayId, x, y, vScroll) }

    /** Low-latency path used after the cursor has already been resolved to a hosted display. */
    fun scrollOnDisplayImmediate(displayId: Int, x: Int, y: Int, vScroll: Float) {
        val helper = service ?: return
        runCatching { helper.injectScroll(displayId, x, y, vScroll) }
            .onFailure { Log.e(TAG, "direct scroll failed for display $displayId", it) }
    }

    /**
     * Injects a coherent two-pointer scale gesture without blocking ordinary input.
     * If several zoom requests arrive while one is running, keep only the latest so
     * repeated pinches cannot create a seconds-long backlog.
     */
    fun pinchOnDisplay(
        displayId: Int,
        centerX: Int,
        centerY: Int,
        fromSpan: Int,
        toSpan: Int,
        durationMs: Int,
    ) {
        pendingPinch.set(PinchRequest(
            displayId, centerX, centerY, fromSpan, toSpan, durationMs,
        ))
        schedulePinchDrain()
    }

    private fun schedulePinchDrain() {
        if (!pinchDrainScheduled.compareAndSet(false, true)) return
        pinchWorker.execute {
            try {
                while (true) {
                    val request = pendingPinch.getAndSet(null) ?: break
                    runCatching {
                        service?.pinchOnDisplay(
                            request.displayId,
                            request.centerX,
                            request.centerY,
                            request.fromSpan,
                            request.toSpan,
                            request.durationMs,
                        )
                    }.onFailure { Log.e(TAG, "pinch failed for display ${request.displayId}", it) }
                }
            } finally {
                pinchDrainScheduled.set(false)
                if (pendingPinch.get() != null) schedulePinchDrain()
            }
        }
    }

    /** Inject an Android SOURCE_MOUSE frame onto the selected physical display. */
    fun injectMouse(displayId: Int, x: Int, y: Int, action: Int, buttonState: Int = 0) =
        onWorker { service?.injectMouse(displayId, x, y, action, buttonState) }

    fun clickMouse(displayId: Int, x: Int, y: Int, button: Int) =
        onWorker { service?.clickMouse(displayId, x, y, button) }

    fun clickTouch(displayId: Int, x: Int, y: Int) =
        onWorker { service?.clickTouch(displayId, x, y) }

    fun moveNativePointer(displayId: Int, dx: Int, dy: Int, wheel: Int = 0) =
        onWorker { service?.moveNativePointer(displayId, dx, dy, wheel) }

    fun nativePointerButton(displayId: Int, code: Int, pressed: Boolean) =
        onWorker { service?.nativePointerButton(displayId, code, pressed) }

    /**
     * Streamed touch injection: one frame at a time, action ∈ {DOWN=0, MOVE=1, UP=2,
     * CANCEL=3}. Lets the trackpad surface a touch-move-lift gesture into an app's
     * VirtualDisplay so taps and swipes work the same way they would on a real screen.
     */
    fun injectTouch(displayId: Int, x: Int, y: Int, action: Int) {
        val name = when (action) {
            0 -> "DOWN"; 1 -> "MOVE"; 2 -> "UP"; 3 -> "CANCEL"; else -> "?($action)"
        }
        Log.d(TAG, "injectTouch $name @ ($x,$y) display=$displayId")
        onWorker { service?.injectTouch(displayId, x, y, action) }
    }

    fun key(displayId: Int, keyCode: Int) = onWorker { service?.key(displayId, keyCode) }

    fun text(displayId: Int, value: String) = onWorker { service?.text(displayId, value) }

    fun queryFocusedEditor(displayId: Int, callback: (FocusedEditorInfo?) -> Unit) {
        worker.execute {
            val raw = runCatching { service?.getFocusedEditorInfo(displayId) }.getOrNull()
            val result = if (raw != null && raw.size >= 3 && raw[0] == 1) {
                FocusedEditorInfo(raw[1], raw[2])
            } else null
            mainHandler.post { callback(result) }
        }
    }

    fun beginSessionInputMethod(
        imeId: String,
        ownerToken: IBinder,
        callback: (SessionImeActivation) -> Unit,
    ) {
        worker.execute {
            val raw = runCatching { service?.beginSessionInputMethod(imeId, ownerToken) }
                .onFailure { Log.e(TAG, "session IME activation failed", it) }
                .getOrNull()
            val activation = if (raw != null && raw.size >= 3) {
                SessionImeActivation(
                    SessionImeResult.fromCode(raw[0].toIntOrNull() ?: -2),
                    raw[1], raw[2],
                )
            } else {
                SessionImeActivation(SessionImeResult.HELPER_UNAVAILABLE, "", "")
            }
            mainHandler.post { callback(activation) }
        }
    }

    fun restoreSessionInputMethod(
        sessionImeId: String,
        previousImeId: String,
        callback: ((SessionImeResult) -> Unit)? = null,
    ) {
        worker.execute {
            val result = runCatching {
                service?.restoreSessionInputMethod(sessionImeId, previousImeId)
                    ?.let(SessionImeResult::fromCode)
            }.onFailure { Log.e(TAG, "session IME restore failed", it) }
                .getOrNull() ?: SessionImeResult.HELPER_UNAVAILABLE
            callback?.let { mainHandler.post { it(result) } }
        }
    }

    fun getImeClientSnapshot(callback: (ImeClientSnapshot?) -> Unit) {
        worker.execute {
            val raw = runCatching { service?.imeClientSnapshot }.getOrNull()
            val fields = raw?.split('|')
            val snapshot = if (fields != null && fields.size >= 7 && fields[0] == "v1") {
                ImeClientSnapshot(
                    selectedImeId = fields[1],
                    displayId = fields[2].toIntOrNull() ?: -1,
                    taskId = fields[3].toIntOrNull() ?: -1,
                    packageName = fields[4],
                    inputType = fields[5].toIntOrNull() ?: 0,
                    imeOptions = fields[6].toIntOrNull() ?: 0,
                )
            } else null
            mainHandler.post { callback(snapshot) }
        }
    }

    fun forceStop(packageName: String) = onWorker { service?.forceStop(packageName) }

    /** Whether the given VirtualDisplay currently has any activity stacked on it. */
    fun displayHasActivity(displayId: Int): Boolean =
        runCatching { service?.displayHasActivity(displayId) == true }.getOrDefault(false)

    /** Queries the activity stack off the UI thread; false is the safe fallback. */
    fun queryDisplayHasBackStack(displayId: Int, callback: (Boolean) -> Unit) {
        worker.execute {
            val result = runCatching { service?.displayHasBackStack(displayId) == true }
                .getOrDefault(false)
            mainHandler.post { callback(result) }
        }
    }

    /** Queries package presence off the UI thread; true is the safe fallback when unknown. */
    fun queryDisplayHasPackage(displayId: Int, packageName: String, callback: (Boolean) -> Unit) {
        worker.execute {
            val result = runCatching { service?.displayHasPackage(displayId, packageName) ?: true }
                .getOrDefault(true)
            mainHandler.post { callback(result) }
        }
    }

    /** Reattaches a tracked Android task to its Beast virtual display. */
    fun restoreRecentTaskOnDisplay(taskId: Int, displayId: Int, callback: (Boolean) -> Unit) {
        worker.execute {
            val result = runCatching {
                service?.restoreRecentTaskOnDisplay(taskId, displayId) == true
            }.getOrDefault(false)
            mainHandler.post { callback(result) }
        }
    }

    /**
     * Injects KEYCODE_MEDIA_PAUSE at the given display so any media playback on
     * the activity (YouTube audio, etc.) stops when the window is minimised.
     */
    fun sendMediaPause(displayId: Int) {
        onWorker { service?.key(displayId, KeyEvent.KEYCODE_MEDIA_PAUSE) }
    }

    fun sendBack(displayId: Int, onEmptied: () -> Unit) {
        onWorker { service?.key(displayId, KeyEvent.KEYCODE_BACK) }
        scheduler.schedule(
            {
                runCatching {
                    if (service?.displayHasActivity(displayId) == false) onEmptied()
                }.onFailure { Log.e(TAG, "back-empty check failed", it) }
            },
            BACK_SETTLE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    // endregion

    private fun onWorker(block: () -> Unit) {
        worker.execute { runCatching { block() } }
    }

    /** How long to wait for an mDNS hit before giving up. */
    private const val DISCOVERY_TIMEOUT_MS = 5_000L

    /** How long to wait after a Back press before checking whether it closed the app. */
    private const val BACK_SETTLE_MS = 800L

    /** Slightly beyond the helper's immutable two-second hard cap. */

    private const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
    private const val IME_ROUTING_UNAVAILABLE = -2

    /** Path of the marker file created on a successful [activate] (relative to filesDir). */
    // Versioned because builds copied from UXspace used an UXspace-labelled ADB key.
    // Re-pairing with a fresh LATERAL_ identity is required once after migration.
    private const val PAIRED_MARKER = "adb/lateral_paired_v2.flag"
}
