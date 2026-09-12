package com.lateral

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import com.lateral.beast.BeastActivity
import com.lateral.privileged.PrivilegedService
import java.lang.ref.WeakReference

/** Process-owned device monitoring continues while PhoneUI is behind the workspace. */
object PhoneView : InputManager.InputDeviceListener, DisplayManager.DisplayListener {
    private lateinit var app: Application
    private val handler = Handler(Looper.getMainLooper())
    private val policy = PhoneViewPolicy()
    private var foreground = WeakReference<Activity>(null)
    private var offer: AlertDialog? = null
    private val listeners = linkedSetOf<() -> Unit>()
    val active get() = policy.active
    val eligible get() = policy.eligible
    var exiting = false
        private set

    fun beginExit() { exiting = true }

    /** Called only after the old workspace has detached and retained its task surfaces. */
    fun completeExit() {
        exiting = false
        if (active) return
        app.startActivity(Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }, ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }.toBundle())
        // Cross-display task moves can expose Home after MainActivity has paused.
        // Keep this bounded repair process-owned; the helper never steals focus
        // from a non-Home app the user has deliberately opened.
        for (delay in listOf(300L, 800L, 1600L)) handler.postDelayed({
            if (!active && !exiting) {
                val taskId = MainActivity.currentPhoneTaskId()
                if (taskId >= 0) PrivilegedService.restorePhoneTaskIfStillHomeAsync(taskId)
            }
        }, delay)
    }

    fun init(application: Application) {
        app = application
        app.getSystemService(InputManager::class.java).registerInputDeviceListener(this, handler)
        app.getSystemService(DisplayManager::class.java).registerDisplayListener(this, handler)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foreground = WeakReference(activity)
                handler.post { refresh(); maybeOffer() }
            }
            override fun onActivityPaused(activity: Activity) {
                if (foreground.get() === activity) foreground.clear()
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (offer?.context === activity) { offer?.dismiss(); offer = null }
            }
            override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
        })
        refresh()
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun refresh() {
        val wasActive = active
        val wasEligible = eligible
        val keyboard = InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let {
                !it.isVirtual && it.isExternal && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
            } == true
        }
        policy.update(keyboard, BeastActivity.findExternalDisplay(app) != null)
        if (!eligible) { offer?.dismiss(); offer = null }
        if (wasActive != active || wasEligible != eligible) {
            PrivilegedService.setHotkeyMonitoringEnabled(!active)
            listeners.toList().forEach { it() }
        }
        maybeOffer()
    }

    private fun maybeOffer() {
        val activity = foreground.get() as? MainActivity ?: return
        if (activity.isFinishing || activity.isDestroyed || offer != null || !policy.takeOffer()) return
        offer = AlertDialog.Builder(activity)
            .setTitle("PhoneView")
            .setMessage("View the LATERAL_ workspace on your phone with the connected keyboard and mouse?")
            .setPositiveButton("Use PhoneView") { _, _ -> setEnabled(activity, true) }
            .setNegativeButton("Not now", null)
            .create().also { dialog ->
                dialog.setOnDismissListener { if (offer === dialog) offer = null }
                dialog.show()
            }
    }

    fun setEnabled(activity: Activity, enabled: Boolean) {
        if (exiting) return
        refresh()
        val before = active
        if (enabled && eligible) com.lateral.beast.HostedTextInputSession.close()
        policy.setActive(enabled)
        PrivilegedService.setHotkeyMonitoringEnabled(!active)
        if (before != active) listeners.toList().forEach { it() }
        if (active) {
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }
            try {
                activity.startActivity(Intent(activity, BeastActivity::class.java).apply {
                    action = BeastActivity.ACTION_ENSURE_WORKSPACE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }, options.toBundle())
            } catch (error: RuntimeException) {
                policy.setActive(false)
                PrivilegedService.setHotkeyMonitoringEnabled(true)
                listeners.toList().forEach { it() }
                android.widget.Toast.makeText(activity, error.message ?: "Could not open PhoneView", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onInputDeviceAdded(id: Int) = refresh()
    override fun onInputDeviceRemoved(id: Int) = refresh()
    override fun onInputDeviceChanged(id: Int) = refresh()
    override fun onDisplayAdded(id: Int) = refresh()
    override fun onDisplayRemoved(id: Int) = refresh()
    override fun onDisplayChanged(id: Int) = refresh()
}
