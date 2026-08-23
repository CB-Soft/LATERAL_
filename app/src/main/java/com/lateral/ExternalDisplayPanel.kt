package com.lateral

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** PhoneUI panel for the physical external display and its hardware mode. */
object ExternalDisplayPanel {
    fun show(
        context: Context,
        displayProvider: () -> Display?,
        requestUltrawide: (Boolean, (Result<Boolean>) -> Unit) -> Unit,
        requestDisplayMode: (Display, Display.Mode, (Result<Unit>) -> Unit) -> Unit,
        isBeastDisplay: (Display) -> Boolean,
        requestBeastNativeTiming: (Int, Int, Int, (Result<Unit>) -> Unit) -> Unit,
        onModalVisibilityChanged: (Boolean) -> Unit = {},
    ) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        var dialog: android.app.AlertDialog? = null
        val lease = TransientPanelCoordinator.claim(
            activity.display?.displayId ?: Display.DEFAULT_DISPLAY,
            TransientPanelCoordinator.Kind.SETTINGS,
        ) { dialog?.dismiss() }
        val density = context.resources.displayMetrics.density
        val mainHandler = Handler(Looper.getMainLooper())
        val displayManager = context.getSystemService(DisplayManager::class.java)
        fun dp(value: Int) = (value * density).toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val displayInfo = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(210, 220, 218))
            setPadding(0, 0, 0, dp(14))
        }
        val divider = View(context).apply {
            setBackgroundColor(Color.rgb(55, 66, 69))
        }
        val timingTitle = TextView(context).apply {
            text = "DISPLAY TIMING"
            typeface = Typeface.MONOSPACE
            setTextColor(InputSettings.accentColor)
            setPadding(0, dp(14), 0, dp(4))
        }
        val resolution = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(210, 220, 218))
            setPadding(0, dp(8), 0, dp(8))
        }
        val refreshRate = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(210, 220, 218))
            setPadding(0, dp(8), 0, dp(8))
        }
        val applyTiming = TextView(context).apply {
            text = "APPLY DISPLAY TIMING"
            typeface = Typeface.MONOSPACE
            gravity = android.view.Gravity.CENTER
            setTextColor(InputSettings.accentColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(36, 48, 51))
        }
        val ultrawide = Switch(context).apply {
            text = "Beast Ultrawide  (3840 × 1200)"
            isChecked = InputSettings.ultrawideEnabled
        }
        val beastNativeTitle = TextView(context).apply {
            text = "BEAST NATIVE TIMING"
            typeface = Typeface.MONOSPACE
            setTextColor(InputSettings.accentColor)
            setPadding(0, dp(14), 0, dp(4))
        }
        val beast1080 = Switch(context).apply {
            text = "Beast native 1920 × 1080"
        }
        val beast120 = Switch(context).apply {
            text = "Beast native 120 Hz"
        }
        val status = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.rgb(150, 162, 166))
            setPadding(0, dp(8), 0, dp(12))
        }
        var syncingSwitch = false
        var switching = false
        var selectedTiming: Timing? = null

        fun currentDisplay(): Display? = displayProvider()

        fun modes(display: Display): List<Display.Mode> = display.supportedModes
            .filter { it.physicalWidth > 0 && it.physicalHeight > 0 && it.refreshRate.isFinite() && it.refreshRate > 0f }
            .distinctBy { Timing(it) }
            .sortedWith(
                compareByDescending<Display.Mode> { it.physicalWidth.toLong() * it.physicalHeight }
                    .thenBy { it.physicalWidth }
                    .thenBy { it.physicalHeight }
                    .thenBy { it.refreshRate },
            )

        fun activeOrSelectedMode(display: Display): Display.Mode? {
            val options = modes(display)
            val active = Timing(display.mode)
            if (selectedTiming == null || options.none { Timing(it) == selectedTiming }) {
                selectedTiming = active
            }
            return options.firstOrNull { Timing(it) == selectedTiming }
                ?: options.firstOrNull { Timing(it) == active }
        }

        fun updateTimingControls(display: Display?) {
            val selected = display?.let(::activeOrSelectedMode)
            val enabled = display != null && selected != null && !switching
            resolution.isEnabled = enabled
            refreshRate.isEnabled = enabled
            applyTiming.isEnabled = enabled
            val disabledColor = Color.rgb(120, 130, 132)
            resolution.setTextColor(if (enabled) Color.rgb(210, 220, 218) else disabledColor)
            refreshRate.setTextColor(if (enabled) Color.rgb(210, 220, 218) else disabledColor)
            applyTiming.setTextColor(if (enabled) InputSettings.accentColor else disabledColor)
            if (selected == null) {
                resolution.text = "Resolution  unavailable"
                refreshRate.text = "Refresh rate  unavailable"
                return
            }
            resolution.text = "Resolution  ${selected.physicalWidth} × ${selected.physicalHeight}"
            refreshRate.text = "Refresh rate  ${formatRefreshRate(selected.refreshRate)}"
        }

        fun updateBeastTimingControls(display: Display?) {
            val visible = display != null && isBeastDisplay(display)
            beastNativeTitle.visibility = if (visible) View.VISIBLE else View.GONE
            ultrawide.visibility = if (visible) View.VISIBLE else View.GONE
            beast1080.visibility = if (visible) View.VISIBLE else View.GONE
            beast120.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible || display == null) return
            val mode = display.mode
            syncingSwitch = true
            ultrawide.isChecked = mode.physicalWidth >= 3000
            beast1080.isChecked = mode.physicalWidth == 1920 && mode.physicalHeight == 1080
            beast120.isChecked = mode.refreshRate >= 110f
            syncingSwitch = false
            ultrawide.isEnabled = !switching
            beast1080.isEnabled = !switching && !ultrawide.isChecked
            beast120.isEnabled = !switching
        }

        fun refresh() {
            val display = currentDisplay()
            val mode = display?.mode
            val connected = display != null && display.state == Display.STATE_ON && mode != null
            if (!connected || mode == null) {
                displayInfo.text = "NO EXTERNAL DISPLAY\nConnect VITURE Beast to view display details."
                ultrawide.isEnabled = false
                updateTimingControls(null)
                updateBeastTimingControls(null)
                status.text = "Display unavailable"
                return
            }

            val refreshRate = if (mode.refreshRate > 0f && mode.refreshRate.isFinite()) {
                String.format(Locale.getDefault(), "%.2f Hz", mode.refreshRate)
            } else {
                "unknown"
            }
            displayInfo.text = buildString {
                append(display.name)
                append("\nDISPLAY ID  ").append(display.displayId)
                append("\nRESOLUTION  ").append(mode.physicalWidth).append(" × ").append(mode.physicalHeight)
                append("\nREFRESH     ").append(refreshRate)
            }
            val hardwareUltrawide = mode.physicalWidth >= 3000
            if (InputSettings.ultrawideEnabled != hardwareUltrawide) {
                InputSettings.setUltrawideEnabled(context, hardwareUltrawide)
            }
            updateTimingControls(display)
            updateBeastTimingControls(display)
            status.text = if (switching) "Switching display mode…" else "Display connected"
        }

        fun requestBeastTiming() {
            val display = currentDisplay() ?: return
            if (!isBeastDisplay(display) || switching) return
            val ultrawideSelected = ultrawide.isChecked
            val width = if (ultrawideSelected) 3840 else 1920
            val height = if (ultrawideSelected) 1200 else if (beast1080.isChecked) 1080 else 1200
            val refresh = if (beast120.isChecked) 120 else 60
            switching = true
            refresh()
            status.text = "Switching Beast native timing…"
            requestBeastNativeTiming(width, height, refresh) { result ->
                result.onSuccess {
                    InputSettings.setUltrawideEnabled(context, width >= 3000)
                    switching = false
                    mainHandler.postDelayed(::refresh, DISPLAY_CHANGE_SETTLE_MS)
                }.onFailure { error ->
                    switching = false
                    refresh()
                    Toast.makeText(
                        context,
                        error.message ?: "Beast native timing switch failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        resolution.setOnClickListener {
            val display = currentDisplay() ?: return@setOnClickListener
            val options = modes(display)
            val resolutions = options.map { it.physicalWidth to it.physicalHeight }.distinct()
            val selected = activeOrSelectedMode(display) ?: return@setOnClickListener
            val checked = resolutions.indexOf(selected.physicalWidth to selected.physicalHeight)
            android.app.AlertDialog.Builder(context)
                .setTitle("Resolution")
                .setSingleChoiceItems(
                    resolutions.map { "${it.first} × ${it.second}" }.toTypedArray(),
                    checked,
                ) { chooser, which ->
                    val (width, height) = resolutions[which]
                    val candidates = options.filter {
                        it.physicalWidth == width && it.physicalHeight == height
                    }
                    selectedTiming = Timing(candidates.minByOrNull {
                        kotlin.math.abs(it.refreshRate - selected.refreshRate)
                    } ?: return@setSingleChoiceItems)
                    updateTimingControls(display)
                    chooser.dismiss()
                }
                .show()
        }

        refreshRate.setOnClickListener {
            val display = currentDisplay() ?: return@setOnClickListener
            val selected = activeOrSelectedMode(display) ?: return@setOnClickListener
            val options = modes(display).filter {
                it.physicalWidth == selected.physicalWidth && it.physicalHeight == selected.physicalHeight
            }
            val checked = options.indexOfFirst { Timing(it) == Timing(selected) }
            android.app.AlertDialog.Builder(context)
                .setTitle("Refresh rate")
                .setSingleChoiceItems(
                    options.map { formatRefreshRate(it.refreshRate) }.toTypedArray(),
                    checked,
                ) { chooser, which ->
                    selectedTiming = Timing(options[which])
                    updateTimingControls(display)
                    chooser.dismiss()
                }
                .show()
        }

        applyTiming.setOnClickListener {
            val display = currentDisplay() ?: return@setOnClickListener
            val mode = activeOrSelectedMode(display) ?: return@setOnClickListener
            switching = true
            refresh()
            status.text = "Requesting ${mode.physicalWidth} × ${mode.physicalHeight} at ${formatRefreshRate(mode.refreshRate)}…"
            requestDisplayMode(display, mode) { result ->
                switching = false
                result.onSuccess {
                    status.text = "Timing request sent; waiting for display…"
                    mainHandler.postDelayed(::refresh, DISPLAY_CHANGE_SETTLE_MS)
                }.onFailure { error ->
                    refresh()
                    Toast.makeText(
                        context,
                        error.message ?: "Display timing switch failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        ultrawide.setOnCheckedChangeListener { view, checked ->
            if (syncingSwitch || checked == InputSettings.ultrawideEnabled || switching) return@setOnCheckedChangeListener
            if (currentDisplay() == null) {
                syncingSwitch = true
                view.isChecked = InputSettings.ultrawideEnabled
                syncingSwitch = false
                return@setOnCheckedChangeListener
            }
            switching = true
            view.isEnabled = false
            status.text = "Switching display mode…"
            requestUltrawide(checked) { result ->
                result.onSuccess { enabled ->
                    InputSettings.setUltrawideEnabled(context, enabled)
                    switching = false
                    refresh()
                }.onFailure { error ->
                    switching = false
                    syncingSwitch = true
                    view.isChecked = InputSettings.ultrawideEnabled
                    syncingSwitch = false
                    refresh()
                    Toast.makeText(
                        context,
                        error.message ?: "Beast display mode failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        beast1080.setOnCheckedChangeListener { _, _ ->
            if (!syncingSwitch) requestBeastTiming()
        }
        beast120.setOnCheckedChangeListener { _, _ ->
            if (!syncingSwitch) requestBeastTiming()
        }

        panel.addView(displayInfo)
        panel.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        panel.addView(timingTitle)
        panel.addView(resolution)
        panel.addView(refreshRate)
        panel.addView(applyTiming, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        panel.addView(beastNativeTitle)
        panel.addView(ultrawide)
        panel.addView(beast1080)
        panel.addView(beast120)
        panel.addView(status)
        refresh()

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                mainHandler.post(::refresh)
            }

            override fun onDisplayRemoved(displayId: Int) {
                mainHandler.post(::refresh)
            }

            override fun onDisplayChanged(displayId: Int) {
                mainHandler.post(::refresh)
            }
        }
        displayManager?.registerDisplayListener(listener, mainHandler)

        val scrollPanel = ScrollView(context).apply { addView(panel) }
        try {
            dialog = android.app.AlertDialog.Builder(context)
                .setTitle("LATERAL_ / display")
                .setView(scrollPanel)
                .setPositiveButton("Done", null)
                .show()
            onModalVisibilityChanged(true)
            dialog?.setOnDismissListener {
                displayManager?.unregisterDisplayListener(listener)
                lease.release()
                onModalVisibilityChanged(false)
            }
        } catch (error: RuntimeException) {
            displayManager?.unregisterDisplayListener(listener)
            lease.release()
            onModalVisibilityChanged(false)
            Toast.makeText(context, error.message ?: "Could not open display settings", Toast.LENGTH_SHORT).show()
        }
    }

    private data class Timing(val width: Int, val height: Int, val refreshRate: Float) {
        constructor(mode: Display.Mode) : this(mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
    }

    private fun formatRefreshRate(refreshRate: Float): String =
        String.format(Locale.getDefault(), "%.2f Hz", refreshRate)

    private const val DISPLAY_CHANGE_SETTLE_MS = 650L
}
