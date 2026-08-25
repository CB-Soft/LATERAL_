package com.lateral

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.lateral.beast.HostedTextInputSession
import com.lateral.privileged.PrivilegedService
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.embedded.EmbeddedImeRuntime

/** Owns the temporary, exact-editor FlorisBoard IME lease. Contains no entered text. */
class HostedKeyboardSessionController(
    context: Context,
    private val onStateChanged: (State, String?) -> Unit,
) {
    enum class State { IDLE, ACTIVATING, ACTIVE, RESTORING, UNAVAILABLE }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val ownerToken = Binder()
    private val imeId = ComponentName(appContext, FlorisImeService::class.java).flattenToString()
    private var generation = 0L
    private var target: HostedTextInputSession.Target? = null
    private var previousImeId = ""
    private var deadline = 0L
    private var state = State.IDLE

    private val embeddedListener: () -> Unit = {
        handler.post { if (state == State.ACTIVATING) verifyTarget() }
        Unit
    }

    init {
        EmbeddedImeRuntime.addListener(embeddedListener)
    }

    fun recoverStaleSession(complete: (() -> Unit)? = null) {
        val stalePrevious = prefs.getString(KEY_PREVIOUS_IME, "").orEmpty()
        if (stalePrevious.isBlank()) {
            complete?.invoke()
            return
        }
        PrivilegedService.restoreSessionInputMethod(imeId, stalePrevious) {
            if (it != PrivilegedService.SessionImeResult.HELPER_UNAVAILABLE) clearRecoveryRecord()
            complete?.invoke()
        }
    }

    fun begin(next: HostedTextInputSession.Target) {
        generation++
        target = next
        deadline = SystemClock.uptimeMillis() + ACTIVATION_TIMEOUT_MS
        updateState(State.ACTIVATING, "Connecting keyboard…")
        val requestGeneration = generation
        PrivilegedService.beginSessionInputMethod(imeId, ownerToken) { activation ->
            if (requestGeneration != generation || target != next) return@beginSessionInputMethod
            if (activation.result != PrivilegedService.SessionImeResult.SUCCESS) {
                fail("Keyboard unavailable (${activation.result.name.lowercase()})")
                return@beginSessionInputMethod
            }
            previousImeId = activation.previousImeId
            if (previousImeId.isNotBlank()) {
                prefs.edit().putString(KEY_PREVIOUS_IME, previousImeId).apply()
            }
            verifyTarget()
        }
    }

    fun end(reason: String = "session ended") {
        generation++
        target = null
        handler.removeCallbacksAndMessages(null)
        if (state == State.IDLE && previousImeId.isBlank()) return
        updateState(State.RESTORING, null)
        val restoreId = previousImeId.ifBlank {
            prefs.getString(KEY_PREVIOUS_IME, "").orEmpty()
        }
        if (restoreId.isBlank()) {
            val switched = EmbeddedImeRuntime.switchToPreviousInputMethod()
            Log.i(TAG, "session end without recorded previous IME reason=$reason fallback=$switched")
            finishRestore()
            return
        }
        PrivilegedService.restoreSessionInputMethod(imeId, restoreId) { result ->
            if (result == PrivilegedService.SessionImeResult.HELPER_UNAVAILABLE) {
                EmbeddedImeRuntime.switchToPreviousInputMethod()
            }
            Log.i(TAG, "session IME restored reason=$reason result=$result")
            finishRestore()
        }
    }

    fun onHelperUnavailable() {
        if (state == State.IDLE) return
        EmbeddedImeRuntime.switchToPreviousInputMethod()
        fail("Keyboard helper unavailable", restore = false)
    }

    fun destroy() {
        end("controller destroyed")
        EmbeddedImeRuntime.removeListener(embeddedListener)
    }

    private fun verifyTarget() {
        val expected = target ?: return
        val requestGeneration = generation
        val embedded = EmbeddedImeRuntime.editorSession.value
        if (embedded == null || embedded.packageName != expected.packageName) {
            retryVerification(requestGeneration)
            return
        }
        PrivilegedService.getImeClientSnapshot { snapshot ->
            if (requestGeneration != generation || target != expected) return@getImeClientSnapshot
            val valid = snapshot != null && snapshot.selectedImeId == imeId &&
                snapshot.displayId == expected.displayId &&
                snapshot.taskId == expected.androidTaskId &&
                snapshot.packageName == expected.packageName
            if (valid) {
                updateState(State.ACTIVE, null)
            } else {
                retryVerification(requestGeneration)
            }
        }
    }

    private fun retryVerification(requestGeneration: Long) {
        if (requestGeneration != generation) return
        if (SystemClock.uptimeMillis() >= deadline) {
            fail("Keyboard could not verify the active Beast editor")
        } else {
            handler.postDelayed({
                if (requestGeneration == generation && state == State.ACTIVATING) verifyTarget()
            }, VERIFY_INTERVAL_MS)
        }
    }

    private fun fail(message: String, restore: Boolean = true) {
        Log.w(TAG, "$message targetTask=${target?.androidTaskId} display=${target?.displayId}")
        updateState(State.UNAVAILABLE, message)
        if (restore) {
            val restoreId = previousImeId.ifBlank {
                prefs.getString(KEY_PREVIOUS_IME, "").orEmpty()
            }
            if (restoreId.isNotBlank()) {
                PrivilegedService.restoreSessionInputMethod(imeId, restoreId) {
                    if (it != PrivilegedService.SessionImeResult.HELPER_UNAVAILABLE) {
                        clearRecoveryRecord()
                    }
                }
            }
        }
    }

    private fun finishRestore() {
        previousImeId = ""
        clearRecoveryRecord()
        updateState(State.IDLE, null)
    }

    private fun clearRecoveryRecord() {
        prefs.edit().remove(KEY_PREVIOUS_IME).apply()
    }

    private fun updateState(next: State, detail: String?) {
        state = next
        onStateChanged(next, detail)
    }

    companion object {
        private const val TAG = "Lateral/HostedKeyboard"
        private const val PREFS = "hosted_keyboard_session"
        private const val KEY_PREVIOUS_IME = "previous_ime"
        private const val ACTIVATION_TIMEOUT_MS = 2_000L
        // The IME service is pre-warmed, so a short retry interval makes the keyboard
        // appear on the first valid onStartInput rather than waiting an extra frame.
        private const val VERIFY_INTERVAL_MS = 40L
    }
}
