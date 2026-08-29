package com.lateral.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * UxSpace's shell-uid privileged helper — runs `am` / `input`, creates the workspace's
 * trusted virtual displays, and hands itself back to the app over a Binder. It exists in
 * two activation modes, both passing through this same class:
 *
 *  - **Shizuku** (transitional). Shizuku binds it as a user service; the AIDL Stub is
 *    delivered through Shizuku's `bindUserService` callback.
 *  - **UxSpace's own bootstrap** (`docs/PRIVILEGE.md`). `app_process` invokes [main], the
 *    server is constructed in the shell-uid process, and its Binder is handed to the app
 *    through [BinderReceiverProvider].
 *
 * From either entry point the privileged work is identical — only the start-up path
 * differs. `am`/`input` are shell-outs because shell uid may not call `ActivityTaskManager`
 * directly; `createVirtualDisplay` uses the `DisplayManager` from a `com.android.shell`
 * package context so the call passes `DisplayManagerService`'s package/uid check.
 */
class PrivilegedServer() : IPrivilegedService.Stub() {

    /** A Context — Shizuku-provided in the user-service path, system-context in [main]. */
    private var context: Context? = null

    /** Trusted virtual displays created for the workspace, keyed by display id. */
    private val virtualDisplays = HashMap<Int, VirtualDisplay>()
    @Volatile private var lastImeRoutingError = ""
    private val imePolicyLock = Any()
    private val sessionImeLock = Any()
    private var sessionImeId: String? = null
    private var sessionPreviousImeId: String? = null
    private var sessionOwner: IBinder? = null
    private val sessionOwnerDeath = IBinder.DeathRecipient {
        synchronized(sessionImeLock) {
            restoreSessionImeInternal("owner binder died")
        }
    }
    private var forcedDesktopModeOriginal: Int? = null

    private val audioRoutingLock = Any()
    private var audioManager: AudioManager? = null

    /** Shell-owned, display-0-only input shield used during cross-display task moves. */
    private val phoneGuardHandler by lazy { Handler(Looper.getMainLooper()) }
    private var phoneGuardWindowManager: WindowManager? = null
    private var phoneGuardView: View? = null
    private var phoneGuardAcquiredAt = 0L
    private var phoneGuardDeadline = 0L
    private var phoneGuardHolders = 0
    private val phoneGuardExpiry = Runnable {
        clearPhoneTouchGuardInternal("absolute timeout")
    }
    private var phoneFocusLeaseGeneration = 0

    /** Shizuku instantiates the user service with this constructor when a Context is available. */
    @Suppress("unused")
    constructor(context: Context) : this() {
        this.context = context
        recoverPersistedSessionIme()
    }

    /** Used by [main] to set the system context once the ActivityThread is up. */
    internal fun setContext(context: Context) {
        this.context = context
        recoverPersistedSessionIme()
    }

    override fun destroy() {
        synchronized(sessionImeLock) { restoreSessionImeInternal("helper shutdown") }
        clearPhoneTouchGuard()
        phoneGuardHandler.removeCallbacksAndMessages(null)
        setBeastMediaRoutingEnabled(false)
        nativeMouse?.close()
        releaseAllDisplays()
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
    ): Int = createVirtualDisplayInternal(name, width, height, densityDpi, surface, true)

    override fun createVirtualDisplayWithPolicy(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        destroyContentOnRemoval: Boolean,
    ): Int = createVirtualDisplayInternal(
        name, width, height, densityDpi, surface, destroyContentOnRemoval,
    )

    private fun createVirtualDisplayInternal(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        destroyContentOnRemoval: Boolean,
    ): Int {
        val displayManager = displayManager()
        if (displayManager == null) {
            Log.e(TAG, "createVirtualDisplay: no DisplayManager (context unavailable)")
            return -1
        }
        return try {
            val display = displayManager.createVirtualDisplay(
                name, width, height, densityDpi, surface,
                BASE_TRUSTED_DISPLAY_FLAGS or
                    (if (destroyContentOnRemoval) VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL else 0),
            )
            if (display == null) {
                Log.e(TAG, "createVirtualDisplay returned null")
                return -1
            }
            val id = display.display.displayId
            // Do not expose or launch onto the display until Android confirms that its
            // real input connection will render the IME on the phone. Continuing with
            // WindowManager's local policy would let a keyboard appear inside Beast.
            if (!applyDefaultDisplayImePolicy(id)) {
                runCatching { display.release() }
                restoreForcedDesktopModeIfIdle()
                Log.e(TAG, "trusted virtual display rejected: phone IME routing unavailable id=$id")
                return IME_ROUTING_UNAVAILABLE
            }
            synchronized(virtualDisplays) { virtualDisplays[id] = display }
            Log.i(TAG, "trusted virtual display created id=$id ${width}x$height '$name' ime=fallback")
            id
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            -1
        }
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        val display = synchronized(virtualDisplays) { virtualDisplays.remove(displayId) }
        if (display != null) {
            runCatching { display.release() }
            Log.i(TAG, "trusted virtual display released id=$displayId")
        }
        restoreForcedDesktopModeIfIdle()
    }

    private fun releaseAllDisplays() {
        val displays = synchronized(virtualDisplays) {
            virtualDisplays.values.toList().also { virtualDisplays.clear() }
        }
        displays.forEach { runCatching { it.release() } }
        restoreForcedDesktopModeIfIdle()
    }

    /**
     * A DisplayManager for creating the virtual display.
     *
     * The display must be created under the package that owns this process's uid (shell) —
     * `DisplayManagerService` rejects a mismatch with "packageName must match the calling
     * uid". A Shizuku-provided or app context carries the *app's* package (`com.uxspace`),
     * so the display is created from a `com.android.shell` package context instead.
     */
    private fun displayManager(): DisplayManager? {
        val base = baseContext() ?: return null
        val shellContext = runCatching {
            base.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure { Log.e(TAG, "could not create a $SHELL_PACKAGE context", it) }.getOrNull()
            ?: return null
        return shellContext.getSystemService(DisplayManager::class.java)
    }

    /** The provided context, or this process's system context fetched reflectively. */
    private fun baseContext(): Context? {
        context?.let { return it }
        val ctx = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            activityThread.getMethod("getSystemContext").invoke(current) as Context
        }.onFailure { Log.e(TAG, "could not obtain a system context", it) }.getOrNull()
        context = ctx
        return ctx
    }

    private fun shellContextForDisplay(displayId: Int): Context? {
        val base = baseContext() ?: return null
        val shell = runCatching {
            base.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        val display = shell.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            ?: return null
        return shell.createDisplayContext(display)
    }

    override fun launchOnDisplay(
        displayId: Int,
        packageName: String,
        activityName: String,
    ): Boolean {
        Log.i(
            "Lateral/Launch",
            "11) helper.launchOnDisplay pkg=$packageName/$activityName display=$displayId",
        )
        val ok = runVerbose(
            "am", "start",
            "--display", displayId.toString(),
            // 1 = WINDOWING_MODE_FULLSCREEN — the activity fills its own bare trusted
            // display; UxSpace draws the surrounding window chrome itself. Freeform
            // mode (5) was used before per-app displays existed; it makes Samsung One
            // UI add its own freeform title bar (grey strip with a blue drag handle)
            // above the activity, which now stacks against our chrome and looks broken.
            "--windowingMode", "1",
            "-n", "$packageName/$activityName",
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER",
            // NEW_TASK | MULTIPLE_TASK. Beast-created tasks remain in Android Overview,
            // so releasing their non-destructive display can return them to the phone.
            "-f", FLAG_NEW_TASK_MULTIPLE,
        )
        Log.i("Lateral/Launch", "12) am-start returned ok=$ok display=$displayId")
        // ~600 ms after the launch, dump the activity stack for this display so we can
        // see whether the activity actually landed where we asked it to. The dumpsys
        // call is cheap; this only fires once per launch.
        scheduleDisplayDump(displayId, packageName)
        return ok
    }

    /**
     * Fire a one-shot delayed dump of `dumpsys activity activities` filtered to a
     * specific display id, so we can see post-launch what's actually on that display.
     * Helps diagnose the "icon shown in taskbar but no window" symptom — if the
     * dump shows the activity on a different display (or not at all), we know the
     * launch routing went wrong.
     */
    private fun scheduleDisplayDump(displayId: Int, packageName: String) {
        Thread {
            try {
                Thread.sleep(600)
                val dump = runCapture("dumpsys", "activity", "activities") ?: return@Thread
                val lines = dump.lineSequence().toList()
                var inDisplay = false
                var matched = 0
                val out = StringBuilder()
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.startsWith("Display #")) {
                        val num = line.removePrefix("Display #").takeWhile(Char::isDigit).toIntOrNull()
                        inDisplay = num == displayId
                        if (inDisplay) out.appendLine(line)
                    } else if (inDisplay && (
                            line.contains("ActivityRecord{") ||
                                line.contains("Stack ") ||
                                line.contains("RootTask ") ||
                                line.startsWith("* Task")
                            )
                    ) {
                        out.appendLine("    $line")
                        matched++
                    }
                }
                Log.i(
                    "Lateral/Launch",
                    "13) dumpsys display=$displayId activities=$matched for pkg=$packageName\n" +
                        if (out.isEmpty()) "    (no display section found)" else out.toString().trimEnd(),
                )
            } catch (_: InterruptedException) {
                // Helper shutting down — fine.
            }
        }.start()
    }

    /**
     * Like [run] but always logs stdout and stderr separately on completion — used for
     * the launch path so we can see exactly what `am start` printed. The other shell-out
     * call-sites (input tap, swipe, key, force-stop) stay on [run] which logs only when
     * something printed or the exit code was non-zero, to keep input logs quiet.
     */
    private fun runVerbose(vararg command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            Log.i(TAG, "[$exit] ${command.joinToString(" ")}")
            if (stdout.isNotEmpty()) Log.i(TAG, "  stdout: $stdout")
            if (stderr.isNotEmpty()) Log.i(TAG, "  stderr: $stderr")
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "command failed: ${command.joinToString(" ")}", e)
            false
        }
    }

    override fun tap(displayId: Int, x: Int, y: Int) {
        run("input", "-d", displayId.toString(), "tap", x.toString(), y.toString())
    }

    override fun swipe(
        displayId: Int,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Int,
    ) {
        run(
            "input", "-d", displayId.toString(), "swipe",
            fromX.toString(), fromY.toString(), toX.toString(), toY.toString(),
            durationMs.toString(),
        )
    }

    override fun key(displayId: Int, keyCode: Int) {
        run("input", "-d", displayId.toString(), "keyevent", keyCode.toString())
    }

    override fun text(displayId: Int, value: String) {
        run("input", "-d", displayId.toString(), "text", value)
    }

    override fun getFocusedEditorInfo(displayId: Int): IntArray {
        val dump = runCapture("dumpsys", "input_method")
            ?: return intArrayOf(0, 0, 0)
        val clientDisplay = CURRENT_IME_CLIENT.find(dump)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (clientDisplay != displayId) return intArrayOf(0, 0, 0)
        val served = dump.substringAfter("mServedInputConnection=", "")
            .lineSequence().firstOrNull()?.trim().orEmpty()
        if (served.isBlank() || served.startsWith("null", ignoreCase = true)) {
            return intArrayOf(0, 0, 0)
        }

        val editorBlock = dump.substringAfter("mCurrentEditorInfo:", "")
        val match = EDITOR_INFO.find(editorBlock) ?: return intArrayOf(0, 0, 0)
        val inputType = match.groupValues.getOrNull(1)?.toIntOrNull(16) ?: 0
        if (inputType == 0) return intArrayOf(0, 0, 0)
        val imeOptions = match?.groupValues?.getOrNull(2)?.toIntOrNull(16) ?: 0
        return intArrayOf(1, inputType, imeOptions)
    }

    override fun beginSessionInputMethod(imeId: String, ownerToken: IBinder): Array<String> =
        synchronized(sessionImeLock) {
            val selectedBefore = selectedInputMethod()
            if (selectedBefore.isBlank()) {
                return@synchronized arrayOf(IME_SESSION_QUERY_FAILED.toString(), "", "")
            }

            if (sessionImeId != null && sessionImeId != imeId) {
                restoreSessionImeInternal("replaced by new session")
            }

            unlinkSessionOwner()
            try {
                ownerToken.linkToDeath(sessionOwnerDeath, 0)
                sessionOwner = ownerToken
            } catch (_: RemoteException) {
                return@synchronized arrayOf(
                    IME_SESSION_OWNER_DEAD.toString(), selectedBefore, selectedInputMethod(),
                )
            }

            val previous = when {
                sessionImeId == imeId && !sessionPreviousImeId.isNullOrBlank() ->
                    sessionPreviousImeId.orEmpty()
                selectedBefore != imeId -> selectedBefore
                else -> ""
            }
            sessionImeId = imeId
            sessionPreviousImeId = previous
            if (previous.isNotBlank() && !persistSessionImeState(imeId, previous)) {
                clearSessionImeState()
                return@synchronized arrayOf(
                    IME_SESSION_QUERY_FAILED.toString(), previous, selectedInputMethod(),
                )
            }

            if (selectedBefore != imeId) {
                if (!run("ime", "enable", imeId)) {
                    clearSessionImeState()
                    return@synchronized arrayOf(
                        IME_SESSION_ENABLE_FAILED.toString(), previous, selectedInputMethod(),
                    )
                }
                if (!run("ime", "set", imeId)) {
                    restoreSessionImeInternal("selection command failed")
                    return@synchronized arrayOf(
                        IME_SESSION_SELECT_FAILED.toString(), previous, selectedInputMethod(),
                    )
                }
            }

            val verified = waitForSelectedIme(imeId)
            if (!verified) {
                restoreSessionImeInternal("activation verification failed")
                return@synchronized arrayOf(
                    IME_SESSION_VERIFY_FAILED.toString(), previous, selectedInputMethod(),
                )
            }
            arrayOf(IME_SESSION_SUCCESS.toString(), previous, imeId)
        }

    override fun restoreSessionInputMethod(sessionImeId: String, previousImeId: String): Int =
        synchronized(sessionImeLock) {
            val current = selectedInputMethod()
            if (current != sessionImeId) {
                clearSessionImeState()
                return@synchronized IME_SESSION_NOT_SELECTED
            }
            if (previousImeId.isBlank() || previousImeId == sessionImeId) {
                clearSessionImeState()
                return@synchronized IME_SESSION_NO_PREVIOUS
            }
            this.sessionImeId = sessionImeId
            this.sessionPreviousImeId = previousImeId
            if (restoreSessionImeInternal("explicit app restore")) {
                IME_SESSION_SUCCESS
            } else IME_SESSION_RESTORE_FAILED
        }

    override fun getImeClientSnapshot(): String {
        val dump = runCapture("dumpsys", "input_method").orEmpty()
        val selected = selectedInputMethod()
        val displayId = CURRENT_IME_CLIENT.find(dump)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        val editorBlock = dump.substringAfter("mCurrentEditorInfo:", "")
        val editor = EDITOR_INFO.find(editorBlock)
        val inputType = editor?.groupValues?.getOrNull(1)?.toIntOrNull(16) ?: 0
        val imeOptions = editor?.groupValues?.getOrNull(2)?.toIntOrNull(16) ?: 0
        val packageName = EDITOR_PACKAGE.find(editorBlock)?.groupValues?.getOrNull(1).orEmpty()
        val taskId = if (displayId >= 0) taskOnDisplay(displayId) ?: -1 else -1
        return listOf(
            "v1", selected, displayId.toString(), taskId.toString(), packageName,
            inputType.toString(), imeOptions.toString(),
        ).joinToString("|")
    }

    private fun selectedInputMethod(): String =
        runCapture("settings", "get", "secure", "default_input_method")
            ?.trim().orEmpty().takeUnless { it == "null" }.orEmpty()

    private fun waitForSelectedIme(expected: String): Boolean {
        repeat(IME_SESSION_VERIFY_ATTEMPTS) {
            if (selectedInputMethod() == expected) return true
            try {
                Thread.sleep(IME_SESSION_VERIFY_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun restoreSessionImeInternal(reason: String): Boolean {
        val active = sessionImeId
        val previous = sessionPreviousImeId
        val current = selectedInputMethod()
        val shouldRestore = !active.isNullOrBlank() && !previous.isNullOrBlank() &&
            previous != active && current == active
        val restored = !shouldRestore || (
            run("ime", "enable", previous!!) && run("ime", "set", previous) &&
                waitForSelectedIme(previous)
            )
        Log.i(TAG, "session IME cleanup ($reason): restored=$restored current=$current")
        clearSessionImeState()
        return restored
    }

    private fun persistSessionImeState(active: String, previous: String): Boolean =
        run("settings", "put", "secure", IME_SESSION_ACTIVE_SETTING, active) &&
            run("settings", "put", "secure", IME_SESSION_PREVIOUS_SETTING, previous)

    private fun recoverPersistedSessionIme() = synchronized(sessionImeLock) {
        val active = runCapture(
            "settings", "get", "secure", IME_SESSION_ACTIVE_SETTING,
        )?.trim().orEmpty().takeUnless { it == "null" }.orEmpty()
        val previous = runCapture(
            "settings", "get", "secure", IME_SESSION_PREVIOUS_SETTING,
        )?.trim().orEmpty().takeUnless { it == "null" }.orEmpty()
        if (active.isBlank() || previous.isBlank()) {
            clearPersistedSessionImeState()
            return@synchronized
        }
        sessionImeId = active
        sessionPreviousImeId = previous
        restoreSessionImeInternal("cold helper recovery")
    }

    private fun unlinkSessionOwner() {
        sessionOwner?.let { runCatching { it.unlinkToDeath(sessionOwnerDeath, 0) } }
        sessionOwner = null
    }

    private fun clearSessionImeState() {
        unlinkSessionOwner()
        sessionImeId = null
        sessionPreviousImeId = null
        clearPersistedSessionImeState()
    }

    private fun clearPersistedSessionImeState() {
        run("settings", "delete", "secure", IME_SESSION_ACTIVE_SETTING)
        run("settings", "delete", "secure", IME_SESSION_PREVIOUS_SETTING)
    }

    /**
     * Compatibility cleanup for builds which used to prefer a Beast USB/wired endpoint.
     *
     * Android (and the user) own the active media route. In particular we must not reassert
     * an external endpoint after the user has selected "This Phone" in the system picker.
     */
    override fun setBeastMediaRoutingEnabled(enabled: Boolean): Boolean = synchronized(audioRoutingLock) {
        val manager = baseContext()?.getSystemService(AudioManager::class.java)
        if (manager == null) {
            Log.w(TAG, "media routing unavailable: no AudioManager")
            return@synchronized false
        }
        audioManager = manager
        if (enabled) {
            Log.w(TAG, "ignoring deprecated Beast media-routing request; Android manages audio")
        }
        clearBeastMediaRouting()
    }

    private fun clearBeastMediaRouting(): Boolean {
        val manager = audioManager ?: return true
        return runCatching {
            val strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
            val strategy = strategyClass.getMethod("getAudioProductStrategies").invoke(null)
                .let { it as List<*> }
                .firstOrNull { item ->
                    strategyClass.getMethod("getName").invoke(item) == MEDIA_STRATEGY_NAME
                } ?: return@runCatching true
            AudioManager::class.java.getMethod(
                "removePreferredDeviceForStrategy", strategyClass,
            ).invoke(manager, strategy)
            Log.i(TAG, "media route preference cleared")
            true
        }.onFailure { Log.e(TAG, "could not clear Beast media preference", it) }.getOrDefault(false)
    }

    override fun forceStop(packageName: String) {
        // Closes the app's windows and kills its process — so releasing the virtual display
        // it ran on has no live activity left to relocate onto the phone's screen.
        run("am", "force-stop", packageName)
    }

    /**
     * Two-finger pinch on [displayId], centred at (centerX, centerY), pointer spread
     * going from [fromSpan] to [toSpan] over [durationMs]. Builds a MotionEvent sequence
     * (DOWN, POINTER_DOWN, MOVEs, POINTER_UP, UP) and submits it through `InputManager`
     * directly — `input` only does a single pointer.
     *
     * Reflective access to `InputManager.getInstance()` and `injectInputEvent(...)` —
     * both are hidden but accessible from the shell uid this process runs as
     * (shell has `INJECT_EVENTS`).
     */
    override fun pinchOnDisplay(
        displayId: Int,
        centerX: Int,
        centerY: Int,
        fromSpan: Int,
        toSpan: Int,
        durationMs: Int,
    ) {
        try {
            val injector = obtainInjector() ?: run {
                Log.e(TAG, "pinch: InputManager unavailable")
                return
            }
            val steps = (durationMs / PINCH_STEP_MS).coerceAtLeast(3)
            val downAt = SystemClock.uptimeMillis()
            // The two pointers move horizontally apart from / together to the centre.
            fun pointAtStep(step: Int): Pair<FloatArray, FloatArray> {
                val t = step.toFloat() / steps
                val span = fromSpan + (toSpan - fromSpan) * t
                val half = span / 2f
                return floatArrayOf(centerX - half, centerY.toFloat()) to
                    floatArrayOf(centerX + half, centerY.toFloat())
            }
            val (start0, start1) = pointAtStep(0)
            injectMotionEvent(injector, displayId, downAt, downAt, MotionEvent.ACTION_DOWN,
                start0, null)
            injectMotionEvent(
                injector, displayId, downAt, downAt,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                start0, start1,
            )
            for (step in 1..steps) {
                SystemClock.sleep(PINCH_STEP_MS.toLong())
                val (p0, p1) = pointAtStep(step)
                val t = SystemClock.uptimeMillis()
                injectMotionEvent(injector, displayId, downAt, t, MotionEvent.ACTION_MOVE, p0, p1)
            }
            val (end0, end1) = pointAtStep(steps)
            val finalT = SystemClock.uptimeMillis()
            injectMotionEvent(
                injector, displayId, downAt, finalT,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                end0, end1,
            )
            injectMotionEvent(injector, displayId, downAt, finalT, MotionEvent.ACTION_UP, end0, null)
        } catch (t: Throwable) {
            Log.e(TAG, "pinch failed", t)
        }
    }

    /**
     * Streamed touch injection: one [ACTION_DOWN] / [ACTION_MOVE] / [ACTION_UP] /
     * [ACTION_CANCEL] motion event at a time on the target display. The down timestamp
     * is latched on `DOWN` and reused for the rest of the sequence, so the app sees a
     * coherent gesture (which is required by Android's input dispatching).
     */
    /**
     * Force the VITURE glasses' USB device to re-enumerate. Locates the device by
     * walking `/sys/bus/usb/devices/` for a `idVendor` that reads `35ca`; if found,
     * writes the device's bus-port identifier (the directory name, e.g. `1-1.2`) to
     * the USB driver's `unbind` file, sleeps briefly, then writes it to `bind`. The
     * kernel re-enumerates the device which then fires `USB_DEVICE_ATTACHED` back to
     * the app. Both writes need shell-uid sysfs write permission (which we have).
     *
     * Triggered from the app-side DOF-stall watchdog when the SDK reports the
     * pose stream has been quiet for several seconds — empirically the BT-keyboard
     * pair sometimes wedges the Carina endpoint until something tickles the bus.
     */
    override fun rescanGlassesUsb() {
        try {
            val devicesRoot = File("/sys/bus/usb/devices")
            val devices = devicesRoot.listFiles() ?: run {
                Log.w(TAG, "rescanGlassesUsb: /sys/bus/usb/devices unreadable")
                return
            }
            var matched = 0
            for (dev in devices) {
                val vendor = runCatching { File(dev, "idVendor").readText().trim() }
                    .getOrNull() ?: continue
                if (vendor != VITURE_VID_HEX) continue
                val busPort = dev.name
                matched++
                Log.i(TAG, "rescanGlassesUsb: rescanning $busPort (idVendor=$vendor)")
                if (rescanViaAuthorized(dev) || rescanViaDriverUnbind(busPort)) {
                    Log.i(TAG, "rescanGlassesUsb: $busPort rescan dispatched")
                } else {
                    Log.w(
                        TAG,
                        "rescanGlassesUsb: every rescan path failed for $busPort " +
                            "— shell uid likely lacks sysfs write permission on this OEM",
                    )
                }
            }
            if (matched == 0) Log.w(TAG, "rescanGlassesUsb: no VITURE device under /sys/bus/usb/devices")
        } catch (t: Throwable) {
            Log.e(TAG, "rescanGlassesUsb failed", t)
        }
    }

    /**
     * Toggle `authorized` from 1 → 0 → 1 on the device's sysfs node. On Samsung this
     * file is sometimes group-writable by `usb`, which shell is a member of — so it
     * works where `/sys/bus/usb/drivers/usb/unbind` doesn't. Same end effect: kernel
     * tears down the device, then re-enumerates.
     */
    private fun rescanViaAuthorized(devDir: File): Boolean {
        val authorized = File(devDir, "authorized")
        if (!authorized.exists()) return false
        return try {
            authorized.writeText("0")
            try { Thread.sleep(USB_REBIND_GAP_MS) } catch (_: InterruptedException) {}
            authorized.writeText("1")
            Log.i(TAG, "rescanViaAuthorized: ${devDir.name} toggled")
            true
        } catch (e: Exception) {
            Log.d(TAG, "rescanViaAuthorized failed for ${devDir.name}: ${e.message}")
            false
        }
    }

    /**
     * Classic unbind/bind via the usb driver. Requires write access to
     * `/sys/bus/usb/drivers/usb/{unbind,bind}` which is normally root-only; mentioned
     * for completeness and to keep a fallback path documented.
     */
    private fun rescanViaDriverUnbind(busPort: String): Boolean {
        return try {
            File("/sys/bus/usb/drivers/usb/unbind").writeText(busPort)
            try { Thread.sleep(USB_REBIND_GAP_MS) } catch (_: InterruptedException) {}
            File("/sys/bus/usb/drivers/usb/bind").writeText(busPort)
            Log.i(TAG, "rescanViaDriverUnbind: $busPort rebound")
            true
        } catch (e: Exception) {
            Log.d(TAG, "rescanViaDriverUnbind failed for $busPort: ${e.message}")
            false
        }
    }

    override fun injectTouch(displayId: Int, x: Int, y: Int, action: Int) {
        try {
            val injector = obtainInjector() ?: run {
                Log.e(TAG, "touch: InputManager unavailable")
                return
            }
            val androidAction = when (action) {
                0 -> MotionEvent.ACTION_DOWN
                1 -> MotionEvent.ACTION_MOVE
                2 -> MotionEvent.ACTION_UP
                3 -> MotionEvent.ACTION_CANCEL
                else -> {
                    Log.e(TAG, "touch: unknown action $action")
                    return
                }
            }
            val now = SystemClock.uptimeMillis()
            val downAt = if (androidAction == MotionEvent.ACTION_DOWN) {
                touchDownAt[displayId] = now
                now
            } else {
                touchDownAt[displayId] ?: now
            }
            val pt = floatArrayOf(x.toFloat(), y.toFloat())
            val ok = injectMotionEvent(injector, displayId, downAt, now, androidAction, pt, null)
            if (!ok) {
                Log.w(TAG, "touch inject FAILED action=$androidAction display=$displayId at ($x,$y)")
            }
            if (androidAction == MotionEvent.ACTION_UP ||
                androidAction == MotionEvent.ACTION_CANCEL) {
                touchDownAt.remove(displayId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "touch injection failed", t)
        }
    }

    override fun applyDefaultDisplayImePolicy(displayId: Int): Boolean = synchronized(imePolicyLock) {
        runCatching {
            acquirePhoneImeDesktopModeLease()
            val manager = windowManagerService()
                ?: throw IllegalStateException("IWindowManager unavailable")
            Class.forName("android.view.IWindowManager").getMethod(
                "setDisplayImePolicy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(manager, displayId, DISPLAY_IME_POLICY_FALLBACK_DISPLAY)
            var observed = readDisplayImePolicy(manager, displayId)
            var attemptsRemaining = IME_POLICY_VERIFY_ATTEMPTS - 1
            while (observed != DISPLAY_IME_POLICY_FALLBACK_DISPLAY && attemptsRemaining-- > 0) {
                SystemClock.sleep(IME_POLICY_VERIFY_INTERVAL_MS)
                observed = readDisplayImePolicy(manager, displayId)
            }
            if (observed != DISPLAY_IME_POLICY_FALLBACK_DISPLAY) {
                throw IllegalStateException(
                    "IME policy verification failed for display $displayId: observed=$observed",
                )
            }
            Log.i(TAG, "phone IME routing verified display=$displayId policy=$observed")
            lastImeRoutingError = ""
            true
        }.onFailure {
            lastImeRoutingError = it.imeRoutingMessage()
            Log.e(TAG, "could not route display $displayId IME to phone", it)
        }
            .getOrDefault(false)
    }

    override fun getDisplayImePolicy(displayId: Int): Int = runCatching {
        val manager = windowManagerService()
            ?: throw IllegalStateException("IWindowManager unavailable")
        readDisplayImePolicy(manager, displayId)
    }.onFailure { Log.e(TAG, "could not read display $displayId IME policy", it) }
        .getOrDefault(IME_POLICY_UNKNOWN)

    override fun getImeRoutingError(): String = lastImeRoutingError

    private fun windowManagerService(): Any? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, Context.WINDOW_SERVICE) as? IBinder
            ?: throw IllegalStateException("window service binder unavailable")
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }.onFailure { Log.e(TAG, "could not obtain IWindowManager", it) }.getOrNull()

    private fun readDisplayImePolicy(manager: Any, displayId: Int): Int =
        (Class.forName("android.view.IWindowManager").getMethod(
            "getDisplayImePolicy",
            Int::class.javaPrimitiveType,
        ).invoke(manager, displayId) as Number).toInt()

    private fun Throwable.imeRoutingMessage(): String {
        val root = generateSequence(this) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message.orEmpty()}".take(240)
    }

    /**
     * REDMAGIC ships Developer Options' forced-desktop flag enabled. Android deliberately
     * converts FALLBACK_DISPLAY back to LOCAL while that flag is set, even for an explicit
     * per-display override. Hold the previous value only while hosted displays exist.
     */
    private fun acquirePhoneImeDesktopModeLease() {
        if (forcedDesktopModeOriginal != null) return
        val persistedOriginal = runCapture(
            "settings", "get", "global", FORCE_DESKTOP_MODE_ORIGINAL_SETTING,
        )?.trim()?.takeUnless { it == "null" }?.toIntOrNull()
        val current = runCapture(
            "settings", "get", "global", FORCE_DESKTOP_MODE_SETTING,
        )?.trim()?.toIntOrNull() ?: 0
        val original = persistedOriginal ?: current
        forcedDesktopModeOriginal = original
        if (original != 0) {
            // The shell helper can be replaced independently of the app process. Record
            // the value before changing it so the replacement helper can inherit and
            // eventually close the same lease instead of mistaking leased zero for the
            // user's preference.
            if (persistedOriginal == null && !run(
                    "settings", "put", "global", FORCE_DESKTOP_MODE_ORIGINAL_SETTING,
                    original.toString(),
                )
            ) {
                forcedDesktopModeOriginal = null
                throw IllegalStateException("could not preserve forced desktop mode")
            }
            if (current != 0 && !run(
                    "settings", "put", "global", FORCE_DESKTOP_MODE_SETTING, "0",
                )
            ) {
                forcedDesktopModeOriginal = null
                throw IllegalStateException("could not disable forced desktop mode")
            }
            if (current != 0) SystemClock.sleep(FORCE_DESKTOP_MODE_SETTLE_MS)
            Log.i(TAG, "forced desktop mode suspended for native phone IME routing")
        }
    }

    private fun restoreForcedDesktopModeIfIdle() {
        if (synchronized(virtualDisplays) { virtualDisplays.isNotEmpty() }) return
        synchronized(imePolicyLock) {
            val original = forcedDesktopModeOriginal ?: return
            runCatching {
                if (original != 0 && !run(
                        "settings", "put", "global", FORCE_DESKTOP_MODE_SETTING,
                        original.toString(),
                    )
                ) throw IllegalStateException("settings put failed")
                if (original != 0 && !run(
                        "settings", "delete", "global", FORCE_DESKTOP_MODE_ORIGINAL_SETTING,
                    )
                ) throw IllegalStateException("settings marker cleanup failed")
                forcedDesktopModeOriginal = null
                Log.i(TAG, "forced desktop mode restored to $original")
            }.onFailure { Log.e(TAG, "could not restore forced desktop mode", it) }
        }
    }

    override fun clickTouch(displayId: Int, x: Int, y: Int) {
        injectTouchClick(displayId, x, y)
    }

    private fun injectTouchClick(
        displayId: Int,
        x: Int,
        y: Int,
        waitForFinish: Boolean = false,
    ): Boolean {
        try {
            val injector = obtainInjector() ?: run {
                Log.e(TAG, "tap: InputManager unavailable")
                return false
            }
            val downAt = SystemClock.uptimeMillis()
            val point = floatArrayOf(x.toFloat(), y.toFloat())
            val downInjected = injectMotionEvent(
                injector, displayId, downAt, downAt, MotionEvent.ACTION_DOWN, point, null,
                injectionMode = if (waitForFinish) 2 else 0,
            )
            try {
                Thread.sleep(CLICK_HOLD_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val upInjected = injectMotionEvent(
                injector, displayId, downAt, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, point, null,
                injectionMode = if (waitForFinish) 2 else 0,
            )
            return downInjected && upInjected
        } catch (t: Throwable) {
            Log.e(TAG, "atomic touch tap failed", t)
            return false
        }
    }

    /** Per-display down timestamp for a streamed touch sequence. */
    private val touchDownAt = mutableMapOf<Int, Long>()

    /**
     * Inject a one-shot ACTION_SCROLL motion event on the target display — mouse-wheel
     * equivalent, fast, no synthesized touch swipe. Same shape as `UiScreen.dispatchScroll`,
     * routed through `InputManager.injectInputEvent` so the event lands on an
     * out-of-process VirtualDisplay.
     */
    override fun injectScroll(displayId: Int, x: Int, y: Int, vScroll: Float) {
        try {
            val injector = obtainInjector() ?: run {
                Log.e(TAG, "scroll: InputManager unavailable")
                return
            }
            val now = SystemClock.uptimeMillis()
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                },
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x.toFloat()
                    this.y = y.toFloat()
                    setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                },
            )
            val event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0,
            )
            event.source = InputDevice.SOURCE_MOUSE
            runCatching {
                event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .invoke(event, displayId)
            }
            val injectMethod = injector.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            // 0 = INJECT_INPUT_EVENT_MODE_ASYNC.
            injectMethod.invoke(injector, event, 0)
            event.recycle()
        } catch (t: Throwable) {
            Log.e(TAG, "scroll failed", t)
        }
    }

    override fun getRecentTasks(): Array<String> = runCatching {
        val service = activityTaskManagerService()
        val method = service.javaClass.methods.firstOrNull {
            it.name == "getRecentTasks" && it.parameterTypes.size == 3
        } ?: error("getRecentTasks API unavailable")
        val slice = method.invoke(service, 100, 0, 0)
        val list = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: emptyList<Any>()
        list.mapNotNull(::encodeRecentTask).toTypedArray()
    }.onFailure { Log.e(TAG, "getRecentTasks failed", it) }.getOrDefault(emptyArray())

    override fun getTaskSnapshots(): Array<String> = runCatching {
        val service = activityTaskManagerService()
        val recentMethod = service.javaClass.methods.firstOrNull {
            it.name == "getRecentTasks" && it.parameterTypes.size == 3
        } ?: error("getRecentTasks API unavailable")
        val slice = recentMethod.invoke(service, 100, 0, 0)
        val recents = slice.javaClass.getMethod("getList").invoke(slice) as? List<*>
            ?: emptyList<Any>()
        val running = readRunningTasks(service).associateBy { taskField(it, "taskId", "id").intValue(-1) }
        recents.mapIndexedNotNull { rank, info -> encodeTaskSnapshot(info, running, rank) }
            .toTypedArray()
    }.onFailure { Log.e(TAG, "getTaskSnapshots failed", it) }.getOrElse {
        arrayOf("!error\t${it.javaClass.simpleName}")
    }

    override fun rewriteRecentTaskOrder(taskIds: IntArray, displayIds: IntArray): Boolean {
        if (taskIds.size != displayIds.size) return false
        // Starting oldest first leaves the requested first entry as Android's MRU task.
        for (index in taskIds.indices.reversed()) {
            if (!bringTaskToFront(taskIds[index], displayIds[index])) return false
        }
        return true
    }

    private fun readRunningTasks(service: Any): List<Any> {
        val method = service.javaClass.methods.firstOrNull { it.name == "getTasks" }
            ?: return emptyList()
        val args = method.parameterTypes.mapIndexed { index, type ->
            when (type) {
                Int::class.javaPrimitiveType -> if (index == 0) 100 else -1
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        @Suppress("UNCHECKED_CAST")
        return (method.invoke(service, *args) as? List<Any>).orEmpty()
    }

    private fun taskOnDisplay(displayId: Int): Int? = runCatching {
        val tasks = readRunningTasks(activityTaskManagerService()).filter {
            taskField(it, "displayId").intValue(-1) == displayId
        }
        val task = tasks.firstOrNull {
            taskField(it, "isFocused") as? Boolean == true
        } ?: tasks.firstOrNull {
            taskField(it, "isVisible") as? Boolean == true
        } ?: tasks.firstOrNull()
        taskField(task, "taskId", "id").intValue(-1).takeIf { it >= 0 }
    }.onFailure {
        Log.w(TAG, "could not resolve task on display $displayId", it)
    }.getOrNull()

    private fun encodeTaskSnapshot(info: Any?, running: Map<Int, Any>, rank: Int): String? {
        info ?: return null
        val taskId = taskField(info, "taskId", "id").intValue(-1)
        if (taskId < 0) return null
        val live = running[taskId]
        val intent = taskField(info, "baseIntent") as? Intent
        val component = sequenceOf(live, info).filterNotNull().flatMap { record ->
            sequenceOf("topActivity", "baseActivity", "realActivity")
                .mapNotNull { taskField(record, it) as? ComponentName }
        }.firstOrNull() ?: intent?.component ?: return null
        val description = taskField(info, "taskDescription")
        val title = runCatching {
            description?.javaClass?.getMethod("getLabel")?.invoke(description)?.toString().orEmpty()
        }.getOrDefault("").sanitizeTaskField()
        val activityType = taskField(live ?: info, "activityType").intValue(0).takeIf { it != 0 }
            ?: runCatching {
                val config = taskField(live ?: info, "configuration")
                val window = config?.javaClass?.getField("windowConfiguration")?.get(config)
                window?.javaClass?.getMethod("getActivityType")?.invoke(window).intValue(0)
            }.getOrDefault(0)
        val excluded = (taskField(info, "isExcluded") as? Boolean ?: false) ||
            ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
        // RecentTaskInfo does not expose mHasBeenVisible on every vendor build. A
        // standard Overview record is restorable even when it has no live task.
        // REDMAGIC's never-presented ghost records report ACTIVITY_TYPE_UNDEFINED,
        // so the fallback deliberately accepts only standard application tasks.
        val hasBeenVisible = taskField(info, "mHasBeenVisible", "hasBeenVisible") as? Boolean
            ?: (activityType == 1)
        return listOf(
            "v1", taskId, rank,
            taskField(info, "lastActiveTime").longValue(0L),
            taskField(info, "isRunning") as? Boolean ?: (live != null),
            hasBeenVisible,
            taskField(live ?: info, "displayId").intValue(-1),
            taskField(live ?: info, "isVisible") as? Boolean ?: false,
            taskField(live ?: info, "isFocused") as? Boolean ?: false,
            activityType,
            excluded,
            taskField(live ?: info, "supportsMultiWindow") as? Boolean ?: true,
            component.packageName.sanitizeTaskField(),
            component.className.sanitizeTaskField(),
            title,
            taskField(info, "isAvailable") as? Boolean ?: true,
        ).joinToString("\t")
    }

    private fun taskField(target: Any?, vararg names: String): Any? {
        target ?: return null
        return names.firstNotNullOfOrNull { name ->
            generateSequence(target.javaClass) { it.superclass }.firstNotNullOfOrNull { type ->
                runCatching {
                    type.getDeclaredField(name).apply { isAccessible = true }.get(target)
                }.getOrNull()
            }
        }
    }

    private fun Any?.intValue(fallback: Int) = (this as? Number)?.toInt() ?: fallback
    private fun Any?.longValue(fallback: Long) = (this as? Number)?.toLong() ?: fallback
    private fun String.sanitizeTaskField() = replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim()

    private fun bringTaskToFront(taskId: Int, displayId: Int): Boolean = runCatching {
        val service = activityTaskManagerService()
        val method = service.javaClass.methods.firstOrNull {
            it.name == "startActivityFromRecents" && it.parameterTypes.size == 2
        } ?: error("startActivityFromRecents API unavailable")
        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = displayId
            setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        }.toBundle()
        method.invoke(service, taskId, options)
        true
    }.onFailure { Log.e(TAG, "could not reorder task $taskId", it) }.getOrDefault(false)

    override fun startRecentTaskOnDisplay(taskId: Int, displayId: Int): Boolean {
        // NX789J / REDMAGIC OS represents every standard Overview entry as a root task
        // with the same id and reliably supports this public shell command. Prefer it:
        // startActivityFromRecents reports success on this ROM but silently leaves the
        // task on display 0.
        if (run("am", "display", "move-stack", taskId.toString(), displayId.toString())) {
            return true
        }

        return runCatching {
            val service = activityTaskManagerService()
            val method = service.javaClass.methods.firstOrNull {
                it.name == "startActivityFromRecents" && it.parameterTypes.size == 2
            } ?: error("startActivityFromRecents API unavailable")
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }.toBundle()
            val result = method.invoke(service, taskId, options)
            Log.i(TAG, "recent task $taskId resumed on display $displayId result=$result")
            true
        }.onFailure {
            Log.w(TAG, "recent-task move and generic resume both failed", it)
        }.getOrDefault(false)
    }

    override fun restorePhoneTaskIfStillHome(phoneTaskId: Int): Boolean {
        val topTaskId = topTaskIdOnDisplay(Display.DEFAULT_DISPLAY)
        if (topTaskId == phoneTaskId) {
            Log.d(TAG, "phone focus repair skipped; PhoneUI already owns display 0")
            return true
        }

        val top = observedTasks().firstOrNull { it.taskId == topTaskId }
        if (top?.activityType != ACTIVITY_TYPE_HOME) {
            Log.d(
                TAG,
                "phone focus repair skipped; display 0 top=$topTaskId " +
                    "type=${top?.activityType ?: "unknown"}",
            )
            return false
        }

        val restored = bringTaskToFront(phoneTaskId, Display.DEFAULT_DISPLAY)
        Log.i(TAG, "phone focus repair top=Home phone=$phoneTaskId restored=$restored")
        return restored
    }

    override fun removeTask(taskId: Int): Boolean = runCatching {
        val service = activityTaskManagerService()
        val method = service.javaClass.methods.firstOrNull {
            it.name == "removeTask" && it.parameterTypes.size == 1
        } ?: error("removeTask API unavailable")
        (method.invoke(service, taskId) as? Boolean) ?: true
    }.onFailure {
        Log.w(TAG, "hidden removeTask failed for $taskId; trying shell root-task removal", it)
    }.getOrElse {
        run("am", "stack", "remove", taskId.toString())
    }

    @SuppressLint("BlockedPrivateApi")
    private fun activityTaskManagerService(): Any {
        val type = Class.forName("android.app.ActivityTaskManager")
        val method = type.getDeclaredMethod("getService").apply { isAccessible = true }
        return method.invoke(null) ?: error("ActivityTaskManager service unavailable")
    }

    private fun encodeRecentTask(info: Any?): String? {
        info ?: return null
        fun field(vararg names: String): Any? = names.firstNotNullOfOrNull { name ->
            runCatching {
                info.javaClass.getField(name).apply { isAccessible = true }.get(info)
            }.getOrNull()
        }
        val taskId = (field("taskId", "id") as? Number)?.toInt() ?: return null
        val lastActiveTime = (field("lastActiveTime") as? Number)?.toLong() ?: 0L
        val running = field("isRunning") as? Boolean ?: false
        val intent = field("baseIntent") as? Intent
        val component = sequenceOf("topActivity", "baseActivity", "realActivity")
            .mapNotNull { field(it) as? ComponentName }
            .firstOrNull() ?: intent?.component ?: return null
        val description = field("taskDescription")
        val title = runCatching {
            description?.javaClass?.getMethod("getLabel")?.invoke(description)?.toString().orEmpty()
        }.getOrDefault("")
            .replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim()
        return listOf(
            taskId.toString(), lastActiveTime.toString(), running.toString(),
            component.packageName, component.className, title,
        ).joinToString("\t")
    }

    override fun resizeVirtualDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int) {
        val display = synchronized(virtualDisplays) { virtualDisplays[displayId] } ?: return
        runCatching {
            display.resize(width.coerceAtLeast(1), height.coerceAtLeast(1), densityDpi)
            Log.i(TAG, "trusted virtual display resized id=$displayId ${width}x$height")
        }.onFailure { Log.e(TAG, "resizeVirtualDisplay failed id=$displayId", it) }
    }

    override fun clearDisplayOverrideSize(displayId: Int) {
        runCatching {
            val process = ProcessBuilder(
                "/system/bin/wm", "size", "reset", "-d", displayId.toString(),
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val exit = process.waitFor()
            if (exit != 0) error("wm size exited $exit: $output")
            Log.i(TAG, "display $displayId forced viewport cleared")
        }.onFailure { Log.e(TAG, "clearDisplayOverrideSize failed id=$displayId", it) }
    }

    override fun setUserPreferredDisplayMode(
        displayId: Int,
        width: Int,
        height: Int,
        refreshRate: Float,
    ): Boolean {
        if (displayId < 0 || width <= 0 || height <= 0 || !refreshRate.isFinite() || refreshRate <= 0f) {
            return false
        }
        // This is the public DisplayManager shell command. Applying a preferred mode through
        // shell uid keeps the selection scoped to this physical display rather than changing
        // the phone's global rendering configuration.
        return runVerbose(
            "cmd",
            "display",
            "set-user-preferred-display-mode",
            width.toString(),
            height.toString(),
            refreshRate.toString(),
            displayId.toString(),
        )
    }

    override fun setVirtualDisplaySurface(displayId: Int, surface: Surface) {
        val display = synchronized(virtualDisplays) { virtualDisplays[displayId] } ?: return
        runCatching { display.surface = surface }
            .onFailure { Log.e(TAG, "setVirtualDisplaySurface failed id=$displayId", it) }
    }

    override fun injectMouse(displayId: Int, x: Int, y: Int, action: Int, buttonState: Int) {
        injectMouseFrame(displayId, x, y, action, buttonState, 0)
    }

    override fun clickMouse(displayId: Int, x: Int, y: Int, button: Int) {
        val safeButton = when (button) {
            MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY -> button
            else -> MotionEvent.BUTTON_PRIMARY
        }
        injectMouseFrame(displayId, x, y, MotionEvent.ACTION_DOWN, safeButton, safeButton)
        if (safeButton != MotionEvent.BUTTON_PRIMARY) {
            injectMouseFrame(displayId, x, y, MotionEvent.ACTION_BUTTON_PRESS, safeButton, safeButton)
        }
        try {
            Thread.sleep(CLICK_HOLD_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (safeButton != MotionEvent.BUTTON_PRIMARY) {
            injectMouseFrame(displayId, x, y, MotionEvent.ACTION_BUTTON_RELEASE, 0, safeButton)
        }
        injectMouseFrame(displayId, x, y, MotionEvent.ACTION_UP, 0, safeButton)
    }

    private fun injectMouseFrame(
        displayId: Int,
        x: Int,
        y: Int,
        action: Int,
        buttonState: Int,
        actionButton: Int,
    ) {
        try {
            val injector = obtainInjector() ?: run {
                Log.e(TAG, "mouse: InputManager unavailable")
                return
            }
            val now = SystemClock.uptimeMillis()
            val downAt = when (action) {
                MotionEvent.ACTION_DOWN -> now.also { mouseDownAt[displayId] = it }
                else -> mouseDownAt[displayId] ?: now
            }
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x.toFloat()
                this.y = y.toFloat()
                pressure = if (buttonState == 0) 0f else 1f
                size = 1f
            })
            val event = MotionEvent.obtain(
                downAt, now, action, 1, props, coords,
                0, buttonState, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0,
            )
            event.source = InputDevice.SOURCE_MOUSE
            if (actionButton != 0) {
                runCatching {
                    event.javaClass.getMethod("setActionButton", Int::class.javaPrimitiveType)
                        .invoke(event, actionButton)
                }
            }
            runCatching {
                event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .invoke(event, displayId)
            }
            val injectMethod = injector.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            injectMethod.invoke(injector, event, 0)
            event.recycle()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mouseDownAt.remove(displayId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "mouse injection failed", t)
        }
    }

    private val mouseDownAt = mutableMapOf<Int, Long>()

    @Volatile private var nativeMouse: NativeMouse? = null

    override fun moveNativePointer(displayId: Int, dx: Int, dy: Int, wheel: Int) {
        if (dx == 0 && dy == 0 && wheel == 0) return
        nativeMouse(displayId)?.move(dx, dy, wheel)
    }

    override fun nativePointerButton(displayId: Int, code: Int, pressed: Boolean) {
        nativeMouse(displayId)?.button(code, pressed)
    }

    @Synchronized
    private fun nativeMouse(displayId: Int): NativeMouse? {
        nativeMouse?.takeIf { it.displayId == displayId && it.isAlive }?.let { return it }
        nativeMouse?.close()
        nativeMouse = runCatching {
            associateInputPort(displayId)
            NativeMouse(displayId)
        }.onFailure { Log.e(TAG, "could not create native pointer for display=$displayId", it) }
            .getOrNull()
        return nativeMouse
    }

    /** Route the virtual mouse's phys/port name to the target physical display. */
    private fun associateInputPort(displayId: Int) {
        val display = baseContext()?.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId) ?: error("display $displayId not found")
        val address = display.javaClass.getMethod("getAddress").invoke(display)
            ?: error("display $displayId has no physical address")
        val displayPort = (address.javaClass.getMethod("getPort").invoke(address) as Number).toInt()
        val inputManager = obtainInjector() ?: error("InputManager unavailable")
        inputManager.javaClass.getMethod(
            "addPortAssociation",
            String::class.java,
            Int::class.javaPrimitiveType,
        ).invoke(inputManager, NATIVE_MOUSE_PORT, displayPort)
        Log.i(TAG, "native pointer port '$NATIVE_MOUSE_PORT' -> display=$displayId port=$displayPort")
    }

    /** Persistent Android uinput process. EOF would unregister the device, so stdin stays open. */
    private class NativeMouse(val displayId: Int) {
        private val process = ProcessBuilder("/system/bin/uinput", "-")
            .redirectErrorStream(true)
            .start()
        private val writer = process.outputStream.bufferedWriter()

        val isAlive: Boolean get() = process.isAlive

        init {
            Thread({
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { Log.d(TAG, "uinput: $it") }
                }
            }, "lateral-uinput-log").apply { isDaemon = true }.start()
            send(
                """{"id":1,"command":"register","name":"LATERAL_ Virtual Mouse","vid":0x35ca,"pid":0x1200,"bus":"usb","port":"$NATIVE_MOUSE_PORT","configuration":[{"type":"UI_SET_EVBIT","data":["EV_KEY","EV_REL"]},{"type":"UI_SET_KEYBIT","data":["BTN_LEFT","BTN_RIGHT","BTN_MIDDLE"]},{"type":"UI_SET_RELBIT","data":["REL_X","REL_Y","REL_WHEEL"]}]}""",
            )
            send("""{"id":1,"command":"delay","duration":300}""")
            Log.i(TAG, "native uinput mouse registered for display=$displayId")
        }

        @Synchronized
        fun move(dx: Int, dy: Int, wheel: Int) {
            // Linux REL_* values are signed 32-bit, but normal pointer motion is
            // small.  Bound the values at the uinput boundary as a final guard
            // against a corrupted Binder/raw-evdev sample becoming Int.MAX_VALUE.
            val safeDx = dx.coerceIn(-127, 127)
            val safeDy = dy.coerceIn(-127, 127)
            val safeWheel = wheel.coerceIn(-127, 127)
            val events = mutableListOf<String>()
            if (safeDx != 0) events += listOf("\"EV_REL\"", "\"REL_X\"", safeDx.toString())
            if (safeDy != 0) events += listOf("\"EV_REL\"", "\"REL_Y\"", safeDy.toString())
            if (safeWheel != 0) events += listOf("\"EV_REL\"", "\"REL_WHEEL\"", safeWheel.toString())
            events += listOf("\"EV_SYN\"", "\"SYN_REPORT\"", "0")
            send("""{"id":1,"command":"inject","events":[${events.joinToString(",")}]}""")
        }

        @Synchronized
        fun button(code: Int, pressed: Boolean) {
            val name = when (code) {
                272 -> "BTN_LEFT"
                273 -> "BTN_RIGHT"
                274 -> "BTN_MIDDLE"
                else -> return
            }
            send("""{"id":1,"command":"inject","events":["EV_KEY","$name",${if (pressed) 1 else 0},"EV_SYN","SYN_REPORT",0]}""")
        }

        private fun send(json: String) {
            writer.write(json)
            writer.newLine()
            writer.flush()
        }

        fun close() {
            runCatching { writer.close() }
            process.destroy()
        }
    }

    /** Build and submit one frame of the pinch — one or two pointers. Returns the
     *  injectInputEvent boolean — true means the event was accepted by the dispatcher. */
    private fun injectMotionEvent(
        injector: Any,
        displayId: Int,
        downAt: Long,
        eventAt: Long,
        action: Int,
        p0: FloatArray,
        p1: FloatArray?,
        injectionMode: Int = 0,
    ): Boolean {
        val count = if (p1 == null) 1 else 2
        val props = Array(count) { idx ->
            MotionEvent.PointerProperties().apply {
                id = idx
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { idx ->
            val src = if (idx == 0) p0 else p1!!
            MotionEvent.PointerCoords().apply {
                x = src[0]
                y = src[1]
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downAt, eventAt, action, count, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        // MotionEvent has setDisplayId since API 30 (hidden in some versions). Read it
        // back so we can tell if the reflection silently failed — that would route the
        // event to display 0 (the phone screen) instead of the virtual display.
        val setOk = runCatching {
            event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }.isSuccess
        val actualDisplayId = runCatching {
            event.javaClass.getMethod("getDisplayId").invoke(event) as? Int
        }.getOrNull() ?: -1
        if (!setOk || actualDisplayId != displayId) {
            Log.w(TAG, "displayId mismatch: setOk=$setOk wanted=$displayId got=$actualDisplayId")
        }
        val injectMethod = injector.javaClass.getMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
        // 0 = ASYNC, 2 = WAIT_FOR_FINISH. Returns Boolean — false means the
        // dispatcher rejected the event (permission, no window, wrong display, …).
        val result = injectMethod.invoke(injector, event, injectionMode)
        event.recycle()
        return (result as? Boolean) ?: false
    }

    /** `InputManager.getInstance()` or, on newer Android, an equivalent service-hosted singleton. */
    private fun obtainInjector(): Any? {
        return runCatching {
            val cls = Class.forName("android.hardware.input.InputManager")
            cls.getMethod("getInstance").invoke(null)
        }.onFailure { Log.w(TAG, "InputManager.getInstance() failed: ${it.message}") }.getOrNull()
    }

    /**
     * Whether [displayId] currently has an activity on it. Used after a Back press to tell
     * whether Back closed the app (so UxSpace can close the now-empty window). Errs on the
     * side of `true` if the dump cannot be read or parsed, so a live app is never closed.
     */
    override fun displayHasActivity(displayId: Int): Boolean {
        val dump = runCapture("dumpsys", "activity", "activities") ?: return true
        if (!dump.contains("ActivityRecord{") || !dump.contains("Display #")) return true
        var inDisplay = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Display #")) {
                val num = line.removePrefix("Display #").takeWhile(Char::isDigit).toIntOrNull()
                inDisplay = num == displayId
            } else if (inDisplay && line.contains("ActivityRecord{")) {
                return true
            }
        }
        return false
    }

    /**
     * Back is safe only when the virtual display has a nested activity to pop. Android does
     * not expose another app's fragment/navigation stack, so this intentionally fails closed
     * when the activity dump is unavailable or cannot be parsed.
     */
    override fun displayHasBackStack(displayId: Int): Boolean {
        val dump = runCapture("dumpsys", "activity", "activities") ?: return false
        if (!dump.contains("ActivityRecord{") || !dump.contains("Display #")) return false
        var inDisplay = false
        var activityCount = 0
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Display #")) {
                val num = line.removePrefix("Display #").takeWhile(Char::isDigit).toIntOrNull()
                inDisplay = num == displayId
            } else if (inDisplay && line.contains("ActivityRecord{")) {
                activityCount++
            }
        }
        return activityCount > 1
    }

    /**
     * Checks whether the hosted app still owns an activity on the virtual display. This is
     * deliberately fail-open: a failed diagnostic must not launch a duplicate app instance.
     */
    override fun displayHasPackage(displayId: Int, packageName: String): Boolean {
        val dump = runCapture("dumpsys", "activity", "activities") ?: return true
        if (!dump.contains("ActivityRecord{") || !dump.contains("Display #")) return true
        var inDisplay = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Display #")) {
                val num = line.removePrefix("Display #").takeWhile(Char::isDigit).toIntOrNull()
                inDisplay = num == displayId
            } else if (inDisplay && line.contains("ActivityRecord{") &&
                (line.contains("$packageName/") || line.contains(" $packageName "))
            ) {
                return true
            }
        }
        return false
    }

    override fun restoreRecentTaskOnDisplay(taskId: Int, displayId: Int): Boolean =
        startRecentTaskOnDisplay(taskId, displayId)

    /**
     * Cross-display task moves on REDMAGIC can expose Home on display 0 for a traversal.
     * Keep the phone input-safe while moving the exact task, then restore the existing
     * PhoneUI task before returning. Guard failure is deliberately fail-open: the task
     * transaction still runs and reports RESTORE_GUARD_SETUP_FAILED only after the two
     * placements have succeeded.
     */
    override fun restoreTaskPreservingPhoneFocus(
        taskId: Int,
        beastDisplayId: Int,
        phoneTaskId: Int,
    ): Int {
        // Never take display 0 back from an app the user deliberately selected. The
        // preservation transaction is conditional on PhoneUI owning that display when
        // the operation begins; background recovery may still restore the Beast task.
        val phoneTopTaskId = topTaskIdOnDisplay(Display.DEFAULT_DISPLAY)
        val preservePhoneFocus = phoneTopTaskId == phoneTaskId
        Log.i(
            TAG,
            "focus transaction target=$taskId->$beastDisplayId phone=$phoneTaskId " +
                "phoneTop=$phoneTopTaskId preserve=$preservePhoneFocus",
        )
        val guarded = preservePhoneFocus && acquirePhoneTouchGuard()
        try {
            if (!startRecentTaskOnDisplay(taskId, beastDisplayId)) {
                return RESTORE_TARGET_FAILED
            }

            if (!preservePhoneFocus) {
                return if (verifyTargetPlacement(taskId, beastDisplayId)) {
                    RESTORE_SUCCESS
                } else {
                    RESTORE_VERIFICATION_FAILED
                }
            }

            // startActivityFromRecents is the only operation which reliably promotes an
            // already-correct-display task on this ROM. Fall back to the shell move path.
            val currentPhoneTopTaskId = topTaskIdOnDisplay(Display.DEFAULT_DISPLAY)
            if (currentPhoneTopTaskId == null) {
                Log.w(TAG, "focus transaction skipped phone recovery; display 0 top is unknown")
                return if (verifyTargetPlacement(taskId, beastDisplayId)) {
                    RESTORE_SUCCESS
                } else {
                    RESTORE_VERIFICATION_FAILED
                }
            }
            if (currentPhoneTopTaskId != phoneTaskId) {
                val currentPhoneTop = observedTasks().firstOrNull { it.taskId == currentPhoneTopTaskId }
                if (currentPhoneTop?.activityType != ACTIVITY_TYPE_HOME) {
                    Log.i(
                        TAG,
                        "focus transaction canceled phone recovery; display 0 top=" +
                            "$currentPhoneTopTaskId type=${currentPhoneTop?.activityType ?: "unknown"}",
                    )
                    return if (verifyTargetPlacement(taskId, beastDisplayId)) {
                        RESTORE_SUCCESS
                    } else {
                        RESTORE_VERIFICATION_FAILED
                    }
                }
            }
            val phoneRecovered = bringTaskToFront(phoneTaskId, Display.DEFAULT_DISPLAY) ||
                startRecentTaskOnDisplay(phoneTaskId, Display.DEFAULT_DISPLAY)
            if (!phoneRecovered) return RESTORE_PHONE_FOCUS_FAILED

            if (!verifyRestorePlacements(taskId, beastDisplayId, phoneTaskId)) {
                return RESTORE_VERIFICATION_FAILED
            }

            schedulePhoneFocusLease(phoneTaskId)
            return if (guarded) RESTORE_SUCCESS else RESTORE_GUARD_SETUP_FAILED
        } finally {
            if (preservePhoneFocus) releasePhoneTouchGuard()
        }
    }

    override fun clearPhoneTouchGuard() {
        onPhoneGuardThread(Unit) { clearPhoneTouchGuardInternal("explicit cleanup") }
    }

    /** Acquire without ever moving an existing deadline. Nested transactions share it. */
    private fun acquirePhoneTouchGuard(): Boolean = onPhoneGuardThread(false) {
        val now = SystemClock.uptimeMillis()
        if (phoneGuardView != null && now < phoneGuardDeadline) {
            phoneGuardHolders++
            return@onPhoneGuardThread true
        }
        if (phoneGuardView != null) clearPhoneTouchGuardInternal("stale acquisition")

        val displayContext = shellContextForDisplay(Display.DEFAULT_DISPLAY)
            ?: return@onPhoneGuardThread false
        val windowManager = displayContext.getSystemService(WindowManager::class.java)
            ?: return@onPhoneGuardThread false
        val guard = View(displayContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { _, _ -> true }
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        var installed = false
        var lastFailure: Throwable? = null
        for (windowType in intArrayOf(
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        )) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                title = "LATERAL_ phone transition guard"
                gravity = Gravity.FILL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            }
            val added = runCatching { windowManager.addView(guard, params) }
            if (added.isSuccess) {
                installed = true
                break
            }
            lastFailure = added.exceptionOrNull()
        }
        if (!installed) {
            Log.e(TAG, "phone touch guard unavailable; restore will continue", lastFailure)
            return@onPhoneGuardThread false
        }

        phoneGuardWindowManager = windowManager
        phoneGuardView = guard
        phoneGuardAcquiredAt = now
        phoneGuardDeadline = now + PHONE_GUARD_HARD_TIMEOUT_MS
        phoneGuardHolders = 1
        // This is intentionally posted before the first task operation. Neither nested
        // acquisition nor any slow shell command is allowed to move this deadline.
        phoneGuardHandler.removeCallbacks(phoneGuardExpiry)
        phoneGuardHandler.postAtTime(phoneGuardExpiry, phoneGuardDeadline)
        Log.d(TAG, "phone touch guard installed until $phoneGuardDeadline")
        true
    }

    private fun releasePhoneTouchGuard() {
        onPhoneGuardThread(Unit) {
            if (phoneGuardView == null) return@onPhoneGuardThread
            phoneGuardHolders = (phoneGuardHolders - 1).coerceAtLeast(0)
            if (phoneGuardHolders == 0) clearPhoneTouchGuardInternal("transaction finished")
        }
    }

    private fun clearPhoneTouchGuardInternal(reason: String) {
        phoneGuardHandler.removeCallbacks(phoneGuardExpiry)
        val view = phoneGuardView
        val manager = phoneGuardWindowManager
        phoneGuardView = null
        phoneGuardWindowManager = null
        phoneGuardAcquiredAt = 0L
        phoneGuardDeadline = 0L
        phoneGuardHolders = 0
        if (view != null && manager != null) {
            runCatching { manager.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "could not remove phone touch guard", it) }
            Log.d(TAG, "phone touch guard removed: $reason")
        }
    }

    private fun <T> onPhoneGuardThread(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrDefault(fallback)
        val result = AtomicReference(fallback)
        val latch = CountDownLatch(1)
        if (!phoneGuardHandler.post {
                try {
                    result.set(block())
                } finally {
                    latch.countDown()
                }
            }
        ) return fallback
        return if (latch.await(PHONE_GUARD_MAIN_THREAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
            result.get()
        } else {
            Log.e(TAG, "timed out waiting for phone guard main thread")
            phoneGuardHandler.post { clearPhoneTouchGuardInternal("main-thread wait timeout") }
            fallback
        }
    }

    private data class ObservedTask(
        val taskId: Int,
        val displayId: Int,
        val activityType: Int,
    )

    private fun observedTasks(): List<ObservedTask> = getTaskSnapshots().mapNotNull { encoded ->
        val fields = encoded.split('\t')
        if (fields.size < 13 || fields[0] != "v1") return@mapNotNull null
        ObservedTask(
            taskId = fields[1].toIntOrNull() ?: return@mapNotNull null,
            displayId = fields[6].toIntOrNull() ?: return@mapNotNull null,
            activityType = fields[9].toIntOrNull() ?: 0,
        )
    }

    private fun verifyRestorePlacements(
        taskId: Int,
        beastDisplayId: Int,
        phoneTaskId: Int,
    ): Boolean {
        repeat(RESTORE_VERIFY_ATTEMPTS) { attempt ->
            val observed = observedTasks()
            val targetCorrect = observed.firstOrNull { it.taskId == taskId }?.displayId == beastDisplayId
            val phoneCorrect = observed.firstOrNull { it.taskId == phoneTaskId }?.displayId ==
                Display.DEFAULT_DISPLAY
            if (targetCorrect && phoneCorrect) return true
            if (attempt + 1 < RESTORE_VERIFY_ATTEMPTS) SystemClock.sleep(RESTORE_VERIFY_DELAY_MS)
        }
        Log.w(
            TAG,
            "restore verification failed task=$taskId->$beastDisplayId phone=$phoneTaskId->0",
        )
        return false
    }

    private fun verifyTargetPlacement(taskId: Int, beastDisplayId: Int): Boolean {
        repeat(RESTORE_VERIFY_ATTEMPTS) { attempt ->
            if (observedTasks().firstOrNull { it.taskId == taskId }?.displayId == beastDisplayId) {
                return true
            }
            if (attempt + 1 < RESTORE_VERIFY_ATTEMPTS) SystemClock.sleep(RESTORE_VERIFY_DELAY_MS)
        }
        Log.w(TAG, "restore verification failed task=$taskId->$beastDisplayId")
        return false
    }

    /**
     * The guard is gone at this point. During late lifecycle/biometric completion only
     * repair an exposed Home task; SystemUI, credential activities, and user-selected apps
     * are intentionally left alone.
     */
    private fun schedulePhoneFocusLease(phoneTaskId: Int) {
        val generation = ++phoneFocusLeaseGeneration
        val expiresAt = SystemClock.uptimeMillis() + PHONE_FOCUS_LEASE_MS
        fun check() {
            if (generation != phoneFocusLeaseGeneration || SystemClock.uptimeMillis() >= expiresAt) return
            val topTaskId = topTaskIdOnDisplay(Display.DEFAULT_DISPLAY)
            val top = observedTasks().firstOrNull { it.taskId == topTaskId }
            if (top?.activityType == ACTIVITY_TYPE_HOME) {
                Log.d(TAG, "focus lease repairing transient Home exposure")
                bringTaskToFront(phoneTaskId, Display.DEFAULT_DISPLAY)
            }
            phoneGuardHandler.postDelayed(::check, PHONE_FOCUS_LEASE_POLL_MS)
        }
        phoneGuardHandler.postDelayed(::check, PHONE_FOCUS_LEASE_POLL_MS)
    }

    private fun topTaskIdOnDisplay(displayId: Int): Int? {
        val dump = runCapture("dumpsys", "activity", "activities") ?: return null
        var inDisplay = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Display #")) {
                val id = line.removePrefix("Display #").takeWhile(Char::isDigit).toIntOrNull()
                if (inDisplay && id != displayId) return null
                inDisplay = id == displayId
            } else if (inDisplay && line.startsWith("* Task{")) {
                return TASK_ID_IN_DUMP.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        }
        return null
    }

    /** Run a shell command and return its standard output, or `null` if it could not run. */
    private fun runCapture(vararg command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "command failed: ${command.joinToString(" ")}", e)
            null
        }
    }

    // region Hotkey monitor — mirrors the Windows companion's WH_KEYBOARD_LL global hook.
    //
    // MainActivity runs with FLAG_NOT_FOCUSABLE so it never sees physical key events. The
    // shell uid we run as can read `/dev/input/event*` directly (same path `adb getevent`
    // uses), which gives us every keyboard regardless of focused window or display. We
    // read passively — the OS still delivers the same events to whatever window has
    // focus, so this is a spy, not an interceptor.
    //
    // We track Ctrl + Alt held state and fire a [PrivilegedHotkeys] code to the
    // registered app-side listener on each ACTION_DOWN of a target key. Hot-plug
    // (BT keyboard connects later) is handled via a FileObserver on /dev/input.
    //
    // Modifier choice: Win/Meta was the natural cross-platform combo (matching the
    // Windows app), but Samsung One UI hard-binds Meta to the launcher's app-drawer
    // shortcut. Switching the Android *and* Windows hotkey set to Ctrl+Alt sidesteps
    // both Samsung's launcher and Windows' Meta-key reservations.

    @Volatile private var hotkeyMonitor: HotkeyMonitor? = null

    override fun setHotkeyListener(listener: IPrivilegedHotkeyListener?) {
        synchronized(this) {
            hotkeyMonitor?.stop()
            hotkeyMonitor = null
            if (listener != null) {
                try {
                    hotkeyMonitor = HotkeyMonitor(listener).also { it.start() }
                    Log.i(TAG, "hotkey monitor started")
                } catch (t: Throwable) {
                    Log.e(TAG, "could not start hotkey monitor", t)
                }
            } else {
                Log.i(TAG, "hotkey monitor stopped")
            }
        }
    }

    private class HotkeyMonitor(private val listener: IPrivilegedHotkeyListener) {

        private val readers = CopyOnWriteArrayList<FileInputStream>()
        private val threads = CopyOnWriteArrayList<Thread>()
        @Volatile private var stopped = false
        private val ctrlHeld = AtomicBoolean(false)
        private val altHeld = AtomicBoolean(false)
        private val modsHeld = AtomicBoolean(false)
        private var fileObserver: FileObserver? = null

        fun start() {
            val inputDir = File(INPUT_DIR)
            inputDir.listFiles { f -> f.name.startsWith("event") }?.forEach(::spawnReader)
            fileObserver = newFileObserver(inputDir).also { it.startWatching() }
        }

        fun stop() {
            stopped = true
            runCatching { fileObserver?.stopWatching() }
            readers.forEach { runCatching { it.close() } }
            // Threads exit on EOF when their FD closes; no need to interrupt.
        }

        private fun newFileObserver(dir: File): FileObserver {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(dir, CREATE) {
                    override fun onEvent(event: Int, name: String?) = handleNewDevice(name)
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(dir.absolutePath, CREATE) {
                    override fun onEvent(event: Int, name: String?) = handleNewDevice(name)
                }
            }
        }

        private fun handleNewDevice(name: String?) {
            if (stopped || name == null || !name.startsWith("event")) return
            // uinput creates the event node before sysfs publishes its name.  Defer
            // hot-plug inspection briefly so our own virtual mouse cannot slip
            // through the name check and be fed back into itself.
            Thread({
                val dev = File(INPUT_DIR, name)
                repeat(INPUT_NAME_WAIT_ATTEMPTS) {
                    if (stopped || !dev.exists()) return@Thread
                    when (inputDeviceName(dev.name)) {
                        NATIVE_MOUSE_NAME -> return@Thread
                        null -> Thread.sleep(INPUT_NAME_WAIT_MS)
                        else -> {
                            spawnReader(dev)
                            return@Thread
                        }
                    }
                }
                spawnReader(dev)
            }, "lateral-input-probe-$name").apply { isDaemon = true }.start()
        }

        private fun spawnReader(dev: File) {
            if (stopped) return
            // The virtual pointer is intentionally delivered to Android's
            // InputReader.  Never let the hotkey/raw-evdev monitor grab its
            // node, otherwise the system cursor on the external display will
            // never see the events we inject below.
            if (isNativePointerDevice(dev.name)) return
            val fis = try {
                FileInputStream(dev)
            } catch (e: Exception) {
                // Most event devices are readable by shell uid via the `input` group;
                // a handful (e.g. accelerometer) may be locked down. Skip silently.
                Log.d(TAG, "hotkey: skip ${dev.name} (${e.message})")
                return
            }
            // Logged at INFO so the user can correlate keyboard attach with
            // any downstream USB / DOF disturbance in a single logcat scroll.
            Log.i(TAG, "hotkey: opened ${dev.name} (${describeInputDevice(dev.name)})")
            // Mice (devices that report EV_REL relative motion) get EVIOCGRAB'd so
            // system_server stops seeing them. Without the grab, clicks land on
            // whatever phone UI sits under the (hidden) system pointer — the home
            // gesture pill, the status bar, anywhere the cursor wanders. With it,
            // the kernel only delivers mouse events to this reader; the workspace
            // cursor + click are the only consumers.
            //
            // Why EVIOCGRAB and not the "proper" Pointer Capture: Pointer Capture
            // requires MainActivity's window to HOLD input focus, which is forbidden —
            // a focused window on display 0 becomes top-focused, so Samsung GameBooster
            // pauses the launched apps on their secondary displays and tears down their
            // input channels (it also ANRs the BT mouse and starves the pseudo-root
            // pairing field; see MainActivity.onCreate). EVIOCGRAB takes the device at
            // the kernel level WITHOUT touching Android's focus system — the only
            // approach compatible with secondary-display apps + the privileged bootstrap.
            // Strategy-gated. The legacy SYSFS detector decides here, at open time.
            // The MOTION detector instead defers to readLoop, which grabs once the
            // node proves itself a pointer from its own event stream (the only path
            // that works for Bluetooth HID mice, whose sysfs caps are unreadable).
            if (MOUSE_GRAB_STRATEGY == MouseGrabStrategy.SYSFS && isMouseDevice(dev.name)) {
                grabExclusive(fis, dev.name)
            }
            readers.add(fis)
            val t = Thread({ readLoop(fis, dev) }, "lateral-input-${dev.name}").apply {
                isDaemon = true
            }
            threads.add(t)
            t.start()
        }

        private fun inputDeviceName(eventName: String): String? {
            // Some vendors deny shell uid access to the sysfs name file, including
            // this device. `getevent -il <node>` uses EVIOCGNAME instead and is
            // available to the same shell uid that owns the privileged helper.
            runCatching {
                File("/sys/class/input/$eventName/device/name").readText().trim()
            }.getOrNull()?.let { return it }
            return runCatching {
                val process = ProcessBuilder(
                    "/system/bin/getevent", "-il", "$INPUT_DIR/$eventName",
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                Regex("""name:\\s+\"([^\"]+)\"""")
                    .find(output)?.groupValues?.getOrNull(1)
            }.getOrNull()
        }

        private fun isNativePointerDevice(eventName: String): Boolean =
            inputDeviceName(eventName) == NATIVE_MOUSE_NAME

        /**
         * Legacy detector for [MouseGrabStrategy.SYSFS]. A device reports relative
         * motion (EV_REL bit set in its capabilities bitmap) — that's the kernel's
         * definition of a mouse. Touchscreens use EV_ABS instead, so this filter
         * never accidentally grabs the phone's actual touchscreen and breaks touch
         * input.
         *
         * Caveat (why [MouseGrabStrategy.MOTION] exists): the capabilities file is
         * empty / SELinux-unreadable from shell uid for Bluetooth HID mice, so this
         * returns false for them and their pointer leaks into the system UI. Kept
         * intact so the old behavior can be plugged back in via [MOUSE_GRAB_STRATEGY].
         */
        private fun isMouseDevice(eventName: String): Boolean {
            val cap = runCatching {
                File("/sys/class/input/$eventName/device/capabilities/ev")
                    .readText().trim()
            }.getOrNull() ?: return false
            val capLong = runCatching { java.lang.Long.parseLong(cap, 16) }.getOrNull() ?: return false
            // bit 2 = EV_REL.
            return (capLong and 0x4L) != 0L
        }

        /**
         * New pluggable mouse detector for [MouseGrabStrategy.MOTION]. Identifies a
         * pointer by what it actually emits rather than by sysfs metadata or device
         * name — so it works for any mouse on any bus (USB or Bluetooth), for combo
         * HID devices whose pointer is a separate evdev node, and needs no sysfs.
         *
         * A node is declared a pointer once it has reported two-axis relative motion
         * (REL_X *and* REL_Y) or pressed a mouse button. Requiring *both* axes is
         * deliberate: this very phone has sensors (ambient-light `als_rear`, the grip
         * sensors) that abuse REL_X as a data channel — they emit REL_X but never
         * REL_Y, so they are correctly never grabbed. Stateful; one per reader thread.
         */
        private class MotionMouseDetector {
            private var sawRelX = false
            private var sawRelY = false

            /** Feed one parsed evdev tuple; returns true once the node looks like a pointer. */
            fun observe(type: Int, code: Int, value: Int): Boolean {
                if (type == EV_KEY && value != 0 &&
                    (code == BTN_LEFT || code == BTN_RIGHT || code == BTN_MIDDLE)
                ) {
                    return true
                }
                if (type == EV_REL) {
                    if (code == REL_X) sawRelX = true
                    else if (code == REL_Y) sawRelY = true
                }
                return sawRelX && sawRelY
            }
        }

        /** Which detector gates the EVIOCGRAB. Switchable so the new MOTION layer can
         *  be A/B'd against the legacy SYSFS one and reverted instantly. */
        private enum class MouseGrabStrategy { SYSFS, MOTION }

        /**
         * Call EVIOCGRAB(1) on the device's FD so the kernel routes its events
         * exclusively to this reader. system_server (the input dispatcher behind
         * the on-screen cursor + click routing) stops receiving anything from
         * this device until the FD is closed or EVIOCGRAB(0) releases it. The
         * grab is dropped automatically when [stop] closes the FD.
         */
        private fun grabExclusive(fis: FileInputStream, devName: String) {
            // EVIOCGRAB(1) takes a non-null arg pointer to "grab"; ioctlInt passes
            // &arg, which the evdev handler reads as non-null → exclusive grab.
            //
            // ioctlInt is @hide and — critically — is NOT a member of the public
            // `android.system.Os` facade at all (getMethod there throws
            // NoSuchMethodException, which silently defeated the grab and let the
            // pointer roam). It lives on the internal `libcore.io.Os` interface,
            // whose singleton is `libcore.io.Libcore.os`. Shell uid bypasses the
            // hidden-API blocklist so reflection reaches it. Try that first, then
            // fall back to a declared-method lookup on android.system.Os in case a
            // future build relocates it.
            val fd = fis.fd
            try {
                val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
                // Find ioctlInt on the *concrete* Os impl (BlockGuardOs/ForwardingOs/
                // Linux), where it actually lives — the public `libcore.io.Os`
                // interface and `android.system.Os` facade don't expose it on this
                // build, which is why the earlier interface lookups failed.
                val ioctlInt = os.javaClass.methods.firstOrNull { it.name == "ioctlInt" }
                if (ioctlInt == null) {
                    val ioctlLike = os.javaClass.methods
                        .filter { it.name.contains("ioctl", ignoreCase = true) }
                        .joinToString("; ") { m ->
                            m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")"
                        }
                    Log.w(
                        TAG,
                        "hotkey: no ioctlInt on ${os.javaClass.name} — ioctl-like methods: [$ioctlLike]",
                    )
                    Log.w(TAG, "hotkey: EVIOCGRAB unavailable for $devName — system pointer will still roam")
                    return
                }
                // Build args positionally by parameter type: the FileDescriptor, the
                // request code (first int = EVIOCGRAB), and the arg pointer (MutableInt
                // holding 1, or a second int = 1) — non-null/non-zero means "grab".
                var sawCmd = false
                val args = ioctlInt.parameterTypes.map { p ->
                    when {
                        p == java.io.FileDescriptor::class.java -> fd
                        p == android.util.MutableInt::class.java -> android.util.MutableInt(1)
                        p == Integer.TYPE && !sawCmd -> { sawCmd = true; EVIOCGRAB }
                        p == Integer.TYPE -> 1
                        else -> null
                    }
                }.toTypedArray()
                ioctlInt.invoke(os, *args)
                Log.i(
                    TAG,
                    "hotkey: EVIOCGRAB ok for $devName via " +
                        "ioctlInt(${ioctlInt.parameterTypes.joinToString { it.simpleName }})",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "hotkey: EVIOCGRAB failed for $devName: ${t.message}")
            }
        }

        /**
         * Map an event-N node to its human-readable device name by reading
         * `/sys/class/input/eventN/device/name` — the sysfs surface for input devices.
         * `/proc/bus/input/devices` would be richer but it's `EACCES` from shell uid on
         * recent Android; sysfs files are world-readable, so this path actually works.
         * Returns "?" / a short failure string when the file isn't present.
         */
        private fun describeInputDevice(eventName: String): String {
            return runCatching {
                File("/sys/class/input/$eventName/device/name").readText().trim()
            }.getOrElse { "name unavailable: ${it.message}" }
        }

        private fun readLoop(fis: FileInputStream, dev: File) {
            val buf = ByteArray(INPUT_EVENT_SIZE)
            // MOTION strategy: identify the pointer from its own event stream and
            // EVIOCGRAB on first proof. Null under the legacy SYSFS strategy, which
            // already decided (and grabbed, or not) at open time.
            val pointerProbe =
                if (MOUSE_GRAB_STRATEGY == MouseGrabStrategy.MOTION) MotionMouseDetector() else null
            var motionGrabbed = false
            try {
                while (!stopped) {
                    // A race at uinput creation may have opened this node before
                    // its sysfs name existed. Stop immediately once it identifies
                    // itself as LATERAL_'s synthetic pointer; Android must be the
                    // sole consumer of that device.
                    if (isNativePointerDevice(dev.name)) return
                    var read = 0
                    while (read < buf.size) {
                        val n = fis.read(buf, read, buf.size - read)
                        if (n <= 0) return
                        read += n
                    }
                    handle(buf)
                    if (pointerProbe != null && !motionGrabbed && pointerProbe.observe(
                            u16le(buf, OFFSET_TYPE), u16le(buf, OFFSET_CODE), i32le(buf, OFFSET_VALUE),
                        )
                    ) {
                        Log.i(TAG, "hotkey: ${dev.name} proved a pointer — EVIOCGRAB (motion strategy)")
                        grabExclusive(fis, dev.name)
                        motionGrabbed = true
                    }
                }
            } catch (_: IOException) {
                // Closed (stop()) or device unplugged — exit cleanly.
            } catch (t: Throwable) {
                Log.w(TAG, "hotkey: ${dev.name} reader error", t)
            } finally {
                readers.remove(fis)
                runCatching { fis.close() }
            }
        }

        /**
         * Recompute the combined Ctrl+Alt held state and, on a transition, fire the
         * synthetic [PrivilegedHotkeys.HK_MODIFIERS_DOWN] / `_UP` hotkey so the app can
         * surface its keymap-legend overlay while the modifier pair is held. Called from
         * the key event handler whenever either Ctrl or Alt changes state.
         */
        private fun updateModifiersHeld() {
            val both = ctrlHeld.get() && altHeld.get()
            if (modsHeld.compareAndSet(!both, both)) {
                val code = if (both) PrivilegedHotkeys.HK_MODIFIERS_DOWN
                           else      PrivilegedHotkeys.HK_MODIFIERS_UP
                try {
                    listener.onHotkey(code)
                } catch (_: RemoteException) {
                    stop()
                }
            }
        }

        // Per-reader-thread accumulators for batched mouse motion. EV_REL events
        // come one axis at a time (separate REL_X and REL_Y), terminated by an
        // EV_SYN_REPORT to mark "frame ready". We sum within a frame and flush
        // on SYN so the listener gets one delta per logical mouse movement.
        private var mouseDxAccum = 0
        private var mouseDyAccum = 0
        private var mouseWheelAccum = 0

        private fun handle(buf: ByteArray) {
            val type = u16le(buf, OFFSET_TYPE)
            val code = u16le(buf, OFFSET_CODE)
            val value = i32le(buf, OFFSET_VALUE)
            if (type == EV_REL) {
                // A normal mouse tick is tiny. Ignore malformed values instead
                // of allowing an evdev glitch (or a stale virtual-device loop)
                // to turn into a full-screen pointer jump.
                if (value !in -127..127) return
                when (code) {
                    REL_X -> mouseDxAccum = (mouseDxAccum + value).coerceIn(-127, 127)
                    REL_Y -> mouseDyAccum = (mouseDyAccum + value).coerceIn(-127, 127)
                    REL_WHEEL -> mouseWheelAccum = (mouseWheelAccum + value).coerceIn(-127, 127)
                }
                return
            }
            if (type == EV_SYN && code == SYN_REPORT) {
                if (mouseDxAccum != 0 || mouseDyAccum != 0 || mouseWheelAccum != 0) {
                    val dx = mouseDxAccum
                    val dy = mouseDyAccum
                    val wh = mouseWheelAccum
                    mouseDxAccum = 0
                    mouseDyAccum = 0
                    mouseWheelAccum = 0
                    try {
                        listener.onMouseDelta(dx, dy, wh)
                    } catch (_: RemoteException) {
                        stop()
                    }
                }
                return
            }
            if (type != EV_KEY) return
            // Mouse buttons — forward press/release to the app. value==2 is
            // autorepeat which mice don't really emit but skip just in case.
            if (code == BTN_LEFT || code == BTN_RIGHT || code == BTN_MIDDLE) {
                if (value != 0 && value != 1) return
                try {
                    listener.onMouseButton(code, value == 1)
                } catch (_: RemoteException) {
                    stop()
                }
                return
            }
            when (code) {
                KEY_LEFTCTRL, KEY_RIGHTCTRL -> {
                    ctrlHeld.set(value != 0)
                    updateModifiersHeld()
                }
                KEY_LEFTALT, KEY_RIGHTALT -> {
                    altHeld.set(value != 0)
                    updateModifiersHeld()
                }
                else -> {
                    // value: 0 = up, 1 = down, 2 = autorepeat. Only fire on the
                    // initial press so a held key doesn't spam zoom steps.
                    if (value != 1) return
                    if (!(ctrlHeld.get() && altHeld.get())) return
                    val hk = when (code) {
                        KEY_A -> PrivilegedHotkeys.HK_CYCLE_LAYOUT
                        KEY_Z -> PrivilegedHotkeys.HK_CYCLE_SCREEN_BAND
                        KEY_X -> PrivilegedHotkeys.HK_TOGGLE_VIEW_MODE
                        KEY_R -> PrivilegedHotkeys.HK_SDK_RECENTER
                        KEY_C -> PrivilegedHotkeys.HK_ANCHOR_POSE
                        KEY_EQUAL, KEY_KPPLUS -> PrivilegedHotkeys.HK_ZOOM_IN
                        KEY_MINUS, KEY_KPMINUS -> PrivilegedHotkeys.HK_ZOOM_OUT
                        else -> return
                    }
                    try {
                        listener.onHotkey(hk)
                    } catch (_: RemoteException) {
                        // App side died — stop the whole monitor so we don't keep
                        // racing against a dead Binder.
                        stop()
                    }
                }
            }
        }

        companion object {
            /**
             * Which mouse-detection layer gates the EVIOCGRAB.
             *
             *  - [MouseGrabStrategy.SYSFS] (legacy): probe the node's capability bitmap
             *    from `/sys/class/input/eventN/device/capabilities/ev` at open time.
             *    Reliable for the phone's built-in devices but EMPTY/SELinux-locked for
             *    Bluetooth HID mice — so BT mice are never grabbed and their pointer
             *    leaks into the system UI (status bar, nav pill, screen edges).
             *  - [MouseGrabStrategy.MOTION] (new, default): ignore metadata; identify a
             *    pointer from the events it actually emits — two-axis relative motion
             *    (REL_X *and* REL_Y) or a mouse button — and grab on first proof. Bus/
             *    vendor/name independent; sensor-safe (the REL_Y requirement excludes
             *    REL_X-only sensors). Costs at most one event frame of leaked motion
             *    before the grab engages.
             *
             * Flip to SYSFS to restore the old behavior verbatim.
             */
            val MOUSE_GRAB_STRATEGY = MouseGrabStrategy.MOTION

            private const val INPUT_DIR = "/dev/input"
            private const val NATIVE_MOUSE_NAME = "LATERAL_ Virtual Mouse"
            private const val INPUT_NAME_WAIT_ATTEMPTS = 10
            private const val INPUT_NAME_WAIT_MS = 25L

            // struct input_event on Android 11+ (64-bit user space):
            //   struct timeval { __kernel_long_t tv_sec; __kernel_long_t tv_usec; }  // 16 bytes
            //   __u16 type, __u16 code, __s32 value                                  //  8 bytes
            // Total: 24 bytes. 32-bit Android is no longer in scope for this app
            // (PrivilegedService gates the helper on Android 11+).
            private const val INPUT_EVENT_SIZE = 24
            private const val OFFSET_TYPE = 16
            private const val OFFSET_CODE = 18
            private const val OFFSET_VALUE = 20

            // <linux/input-event-codes.h> — kernel keycodes (not Android KeyEvent codes).
            private const val EV_SYN = 0
            private const val EV_KEY = 1
            private const val EV_REL = 2
            private const val SYN_REPORT = 0
            private const val REL_X = 0
            private const val REL_Y = 1
            private const val REL_WHEEL = 8
            // Mouse button kernel codes.
            private const val BTN_LEFT = 272
            private const val BTN_RIGHT = 273
            private const val BTN_MIDDLE = 274
            // _IOW('E', 0x90, int) — exclusive-grab ioctl for evdev devices.
            private const val EVIOCGRAB = 0x40044590
            private const val KEY_MINUS = 12
            private const val KEY_EQUAL = 13
            private const val KEY_R = 19
            private const val KEY_LEFTCTRL = 29
            private const val KEY_A = 30
            private const val KEY_Z = 44
            private const val KEY_X = 45
            private const val KEY_C = 46
            private const val KEY_LEFTALT = 56
            private const val KEY_KPMINUS = 74
            private const val KEY_KPPLUS = 78
            private const val KEY_RIGHTCTRL = 97
            private const val KEY_RIGHTALT = 100

            private fun u16le(b: ByteArray, off: Int): Int =
                (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

            private fun i32le(b: ByteArray, off: Int): Int =
                (b[off].toInt() and 0xff) or
                    ((b[off + 1].toInt() and 0xff) shl 8) or
                    ((b[off + 2].toInt() and 0xff) shl 16) or
                    ((b[off + 3].toInt() and 0xff) shl 24)
        }
    }
    // endregion

    /** Run a shell command, log anything it prints, and report a clean exit. */
    private fun run(vararg command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val output = (
                process.inputStream.bufferedReader().readText() +
                    process.errorStream.bufferedReader().readText()
                ).trim()
            val exit = process.waitFor()
            if (exit != 0 || output.isNotEmpty()) {
                Log.i(TAG, "[$exit] ${command.joinToString(" ")}${if (output.isEmpty()) "" else " :: $output"}")
            }
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "command failed: ${command.joinToString(" ")}", e)
            false
        }
    }

    companion object {
        private const val TAG = "Lateral/Privileged"
        private const val MEDIA_STRATEGY_NAME = "STRATEGY_MEDIA"
        private const val NATIVE_MOUSE_PORT = "lateral-pointer"
        private const val CLICK_HOLD_MS = 32L
        private const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
        private val CURRENT_IME_CLIENT = Regex("mCurClient=.*?mSelfReportedDisplayId=(\\d+)")
        private val EDITOR_INFO = Regex("inputType=0x([0-9a-fA-F]+)\\s+imeOptions=0x([0-9a-fA-F]+)")
        private val EDITOR_PACKAGE = Regex("packageName=([^\\s}]+)")
        private const val IME_SESSION_SUCCESS = 0
        private const val IME_SESSION_QUERY_FAILED = 1
        private const val IME_SESSION_ENABLE_FAILED = 2
        private const val IME_SESSION_SELECT_FAILED = 3
        private const val IME_SESSION_VERIFY_FAILED = 4
        private const val IME_SESSION_OWNER_DEAD = 5
        private const val IME_SESSION_NOT_SELECTED = 6
        private const val IME_SESSION_NO_PREVIOUS = 7
        private const val IME_SESSION_RESTORE_FAILED = 8
        private const val IME_SESSION_VERIFY_ATTEMPTS = 20
        private const val IME_SESSION_VERIFY_INTERVAL_MS = 50L
        private const val IME_SESSION_ACTIVE_SETTING = "lateral_session_ime"
        private const val IME_SESSION_PREVIOUS_SETTING = "lateral_session_previous_ime"
        private const val IME_POLICY_UNKNOWN = -1
        private const val IME_ROUTING_UNAVAILABLE = -2
        private const val IME_POLICY_VERIFY_ATTEMPTS = 16
        private const val IME_POLICY_VERIFY_INTERVAL_MS = 50L
        private const val FORCE_DESKTOP_MODE_SETTING =
            "force_desktop_mode_on_external_displays"
        private const val FORCE_DESKTOP_MODE_ORIGINAL_SETTING =
            "lateral_force_desktop_mode_original"
        private const val FORCE_DESKTOP_MODE_SETTLE_MS = 350L

        const val RESTORE_SUCCESS = 0
        const val RESTORE_TARGET_FAILED = 1
        const val RESTORE_PHONE_FOCUS_FAILED = 2
        const val RESTORE_VERIFICATION_FAILED = 3
        const val RESTORE_GUARD_SETUP_FAILED = 4
        private const val PHONE_GUARD_HARD_TIMEOUT_MS = 2_000L
        private const val PHONE_GUARD_MAIN_THREAD_WAIT_MS = 750L
        private const val PHONE_FOCUS_LEASE_MS = 600L
        private const val PHONE_FOCUS_LEASE_POLL_MS = 150L
        private const val RESTORE_VERIFY_ATTEMPTS = 5
        private const val RESTORE_VERIFY_DELAY_MS = 60L
        private const val ACTIVITY_TYPE_HOME = 2
        private val TASK_ID_IN_DUMP = Regex("#(\\d+)")

        // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK. The third bit we want
        // (FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS, 0x00800000) is no longer ORed in here —
        // we pass it via am's `--activity-exclude-from-recents` argument instead so it
        // doesn't ride in the same raw -f blob. See launchOnDisplay() for the reason.
        private const val FLAG_NEW_TASK_MULTIPLE = "0x18000000"

        /** Frame spacing for the pinch interpolation in [pinchOnDisplay]. */
        private const val PINCH_STEP_MS = 8

        /** Package owning the shell uid — the virtual display is created under it. */
        private const val SHELL_PACKAGE = "com.android.shell"

        /** VITURE Technology vendor id, lowercased hex as sysfs reports it. */
        private const val VITURE_VID_HEX = "35ca"

        /** Brief pause between unbind and bind during a USB rescan, ms. */
        private const val USB_REBIND_GAP_MS = 200L

        /**
         * Flags for the workspace's virtual displays. `PUBLIC` so the system places activities
         * on it; `OWN_CONTENT_ONLY` so it never mirrors the phone; `PRESENTATION` marks it as
         * secondary content; `TRUSTED` (1 << 10 — a hidden constant) so an app launched onto
         * it may follow its own activity launches there rather than escaping to the phone.
         *
         * `OWN_DISPLAY_GROUP` (1<<11) + `ALWAYS_UNLOCKED` (1<<12) were tried as a way to keep
         * the Samsung-injected KEYGUARD_DIALOG window off this display — but together they
         * leave the display in `state OFF` (DisplayPowerManager in the new group never
         * receives a power-on), which kills rendering entirely. Don't combine them again.
         */
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        /**
         * Without this flag Android reparents tasks from a removed public virtual display
         * onto display 0. That made a Beast task suddenly appear on the NX789J phone when
         * LATERAL_ was updated, stopped, or its surface was torn down.
         */
        private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val BASE_TRUSTED_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                VIRTUAL_DISPLAY_FLAG_TRUSTED

        /**
         * Entry point when this class is loaded by `app_process` from the ADB shell bootstrap
         * (see `ServerBootstrap`). Sets up a system context, builds the Stub, hands its
         * Binder to the UxSpace app through [BinderReceiverProvider], then loops forever.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                Log.i(TAG, "PrivilegedServer.main entered, pid=${android.os.Process.myPid()}")
                killOrphanPrivilegedServers()
                Log.i(TAG, "orphan sweep returned; preparing Looper")
                Looper.prepareMainLooper()
                val systemContext = obtainSystemContext()
                    ?: throw IllegalStateException("could not obtain a system context")
                val server = PrivilegedServer().also { it.setContext(systemContext) }
                sendBinderToApp(server)
                Log.i(TAG, "PrivilegedServer ready; entering main loop")
                Looper.loop()
            } catch (t: Throwable) {
                Log.e(TAG, "PrivilegedServer crashed during start-up", t)
                exitProcess(1)
            }
        }

        /**
         * Kill any other `lateral_privileged` processes left over from previous app
         * sessions before claiming the role ourselves. `app_process` detaches once
         * started; `adb shell am force-stop com.uxspace` only kills the app uid, not
         * the shell-uid helper, so without this sweep every reinstall would leave
         * behind another orphan holding FDs on every `/dev/input/event*` node — a
         * pattern observed in logcat as multiple `hotkey monitor started` lines and
         * 3×+ FD use on every input device, which in turn correlates with the
         * Bluetooth-keyboard-kills-DOF symptom.
         *
         * Identifies peers by `--nice-name=lateral_privileged` riding in their
         * `/proc/<pid>/cmdline`. Shell uid has signal permission for shell-owned
         * processes, so `Process.killProcess` does what we need; the SIGKILL on
         * our SDK handle / Binder is handled by Android's process teardown.
         */
        private fun killOrphanPrivilegedServers() {
            val self = android.os.Process.myPid()
            Log.i(TAG, "orphan sweep starting (self pid=$self)")
            val procRoot = File("/proc")
            val children = procRoot.listFiles()
            if (children == null) {
                Log.w(TAG, "orphan sweep: /proc.listFiles() returned null — skipping")
                return
            }
            var examined = 0
            var killed = 0
            try {
                for (entry in children) {
                    val pid = entry.name.toIntOrNull() ?: continue
                    if (pid == self) continue
                    examined++
                    val cmdline = runCatching { File(entry, "cmdline").readText() }.getOrNull()
                        ?: continue
                    // /proc/<pid>/cmdline is NUL-separated; argv[0] is everything before
                    // the first NUL. Match strictly on argv[0] — substring matches caught
                    // the parent shell whose own command line embedded the helper's
                    // command string, and killing that shell tore down the ADB stream
                    // the app reads the privileged binder over.
                    val argv0 = cmdline.substringBefore('\u0000')
                    if (argv0 != "lateral_privileged") continue
                    Log.i(TAG, "killing orphan lateral_privileged pid=$pid")
                    runCatching { android.os.Process.killProcess(pid) }
                    killed++
                }
            } catch (t: Throwable) {
                Log.w(TAG, "orphan sweep loop crashed (examined=$examined)", t)
            }
            if (killed > 0) {
                // Give the kernel a tick to reap the FDs (incl. /dev/input/event* opens)
                // so the new helper's HotkeyMonitor sees a clean state.
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
            }
            Log.i(TAG, "orphan sweep complete; examined=$examined killed=$killed")
        }

        /** `ActivityThread.systemMain().getSystemContext()` — the standard app_process bootstrap. */
        private fun obtainSystemContext(): Context? = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val systemMain = activityThread.getMethod("systemMain").invoke(null)
            activityThread.getMethod("getSystemContext").invoke(systemMain) as Context
        }.onFailure { Log.e(TAG, "obtainSystemContext failed", it) }.getOrNull()

        /**
         * Pass [binder] back to the UxSpace app process by calling its
         * [BinderReceiverProvider].
         *
         * Cannot use [Context.getContentResolver] because this process wasn't started by
         * AMS — `acquireProvider` calls `IActivityManager.getContentProvider`, which
         * requires a registered application record for the calling pid and rejects us with
         * "Unable to find app for caller". Instead we go straight through
         * `IActivityManager.getContentProviderExternal` — the same hidden API the `cmd
         * content` shell command uses to call into providers from shell-uid — and invoke
         * `IContentProvider.call` directly. Both are accessed reflectively because they
         * are not in the public SDK.
         */
        private fun sendBinderToApp(binder: IBinder) {
            val authority = BinderReceiverProvider.AUTHORITY
            val token = Binder()
            val extras = Bundle().apply { putBinder(BinderReceiverProvider.EXTRA_BINDER, binder) }
            val activityManager = activityManagerService()
                ?: throw IllegalStateException("no IActivityManager binder")
            val iAmClass = Class.forName("android.app.IActivityManager")
            val holder = iAmClass
                .getMethod(
                    "getContentProviderExternal",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    IBinder::class.java,
                    String::class.java,
                )
                .invoke(activityManager, authority, 0, token, SHELL_PACKAGE)
                ?: throw IllegalStateException("getContentProviderExternal returned null")
            val provider = holder.javaClass.getField("provider").get(holder)
                ?: throw IllegalStateException("ContentProviderHolder.provider was null")
            try {
                invokeProviderCall(provider, authority, extras)
                Log.i(TAG, "sent binder via getContentProviderExternal")
            } finally {
                runCatching {
                    iAmClass.getMethod(
                        "removeContentProviderExternal",
                        String::class.java,
                        IBinder::class.java,
                    ).invoke(activityManager, authority, token)
                }
            }
        }

        /** `ActivityManager.getService()` — the singleton `IActivityManager` binder proxy. */
        private fun activityManagerService(): Any? = runCatching {
            Class.forName("android.app.ActivityManager")
                .getMethod("getService")
                .invoke(null)
        }.onFailure { Log.e(TAG, "ActivityManager.getService() failed", it) }.getOrNull()

        /**
         * Invoke `IContentProvider.call(...)` reflectively, building whichever calling
         * identity the platform expects: API 31+ uses an `AttributionSource`; older
         * versions take a plain `(callingPkg, callingFeatureId)` pair.
         */
        private fun invokeProviderCall(provider: Any, authority: String, extras: Bundle) {
            val iCpClass = Class.forName("android.content.IContentProvider")
            val callMethods = iCpClass.declaredMethods.filter {
                it.name == "call" && it.returnType == Bundle::class.java
            }
            val attribClass = runCatching { Class.forName("android.content.AttributionSource") }
                .getOrNull()
            val attribCall = if (attribClass != null) {
                callMethods.firstOrNull { it.parameterTypes.firstOrNull() == attribClass }
            } else {
                null
            }
            if (attribCall != null) {
                val ctor = attribClass!!.getConstructor(
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                )
                val src = ctor.newInstance(Process.SHELL_UID, SHELL_PACKAGE, null)
                attribCall.invoke(
                    provider, src, authority,
                    BinderReceiverProvider.METHOD_SET_BINDER, null, extras,
                )
                return
            }
            // Pre-API 31 fallback: (callingPkg, callingFeatureId?, authority, method, arg, extras).
            val stringCall = callMethods.firstOrNull {
                it.parameterTypes.firstOrNull() == String::class.java
            } ?: throw IllegalStateException("no usable IContentProvider.call signature")
            val params = stringCall.parameterTypes
            val args: Array<Any?> = when (params.size) {
                5 -> arrayOf(
                    SHELL_PACKAGE, authority,
                    BinderReceiverProvider.METHOD_SET_BINDER, null, extras,
                )
                6 -> arrayOf(
                    SHELL_PACKAGE, null, authority,
                    BinderReceiverProvider.METHOD_SET_BINDER, null, extras,
                )
                else -> throw IllegalStateException(
                    "unexpected IContentProvider.call arity ${params.size}",
                )
            }
            stringCall.invoke(provider, *args)
        }
    }
}
