package com.lateral

import android.content.Context
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Shared, compact input/settings menu used by PhoneUI and the external workspace. */
object InputSettingsPanel {
    fun show(
        context: Context,
        onModalVisibilityChanged: (Boolean) -> Unit = {},
    ) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        var dialog: android.app.AlertDialog? = null
        val lease = TransientPanelCoordinator.claim(
            activity.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY,
            TransientPanelCoordinator.Kind.SETTINGS,
        ) { dialog?.dismiss() }
        val density = context.resources.displayMetrics.density
        val padding = (20 * density).toInt()
        fun dp(value: Int) = (value * density).toInt()
        fun colorHex(color: Int) = "#%06X".format(color and 0xFFFFFF)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val sensitivityLabel = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(210, 220, 218))
        }
        fun updateSensitivityLabel(value: Float) {
            sensitivityLabel.text = "Cursor sensitivity  ${"%.1f".format(value)}×"
        }
        updateSensitivityLabel(InputSettings.cursorSensitivity)
        val sensitivity = SeekBar(context).apply {
            max = 21
            progress = ((InputSettings.cursorSensitivity - .4f) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = .4f + progress / 10f
                    InputSettings.setCursorSensitivity(context, value)
                    updateSensitivityLabel(value)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        fun gestureSensitivityControl(
            title: String,
            current: () -> Float,
            set: (Float) -> Unit,
        ): LinearLayout {
            val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val valueLabel = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(Color.rgb(210, 220, 218))
            }
            fun update(value: Float) {
                valueLabel.text = "$title  ${"%.1f".format(value)}×"
            }
            update(current())
            row.addView(valueLabel)
            row.addView(SeekBar(context).apply {
                max = 21
                progress = ((current() - .4f) * 10).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = .4f + progress / 10f
                        set(value)
                        update(value)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            })
            return row
        }
        val scrollSensitivity = gestureSensitivityControl(
            "Scroll sensitivity",
            { InputSettings.scrollSensitivity },
            { InputSettings.setScrollSensitivity(context, it) },
        )
        val pinchZoomSensitivity = gestureSensitivityControl(
            "Pinch zoom sensitivity",
            { InputSettings.pinchZoomSensitivity },
            { InputSettings.setPinchZoomSensitivity(context, it) },
        )
        fun appearanceSlider(title: String, ultrawide: Boolean): LinearLayout {
            val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val valueLabel = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(Color.rgb(210, 220, 218))
            }
            fun current() = InputSettings.uiScale(ultrawide)
            fun update(value: Float) {
                valueLabel.text = "$title  ${"%.0f".format(value * 100)}%"
            }
            update(current())
            row.addView(valueLabel)
            row.addView(SeekBar(context).apply {
                // One locked geometry/text scale per display mode.
                max = if (ultrawide) 415 else 115
                progress = ((current() - .85f) * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = .85f + progress / 100f
                        InputSettings.setUiScale(context, ultrawide, value)
                        update(value)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            })
            return row
        }
        val invertTwoFingerScroll = Switch(context).apply {
            text = "Invert two-finger scroll"
            isChecked = InputSettings.invertTwoFingerScroll
            setOnCheckedChangeListener { _, checked ->
                InputSettings.setInvertTwoFingerScroll(context, checked)
            }
        }
        val invertScrollbarScroll = Switch(context).apply {
            text = "Invert scrollbar scroll"
            isChecked = InputSettings.invertScrollbarScroll
            setOnCheckedChangeListener { _, checked ->
                InputSettings.setInvertScrollbarScroll(context, checked)
            }
        }
        val momentum = Switch(context).apply {
            text = "Scroll momentum"
            isChecked = InputSettings.momentumEnabled
            setOnCheckedChangeListener { _, checked -> InputSettings.setMomentumEnabled(context, checked) }
        }
        val startMinimized = Switch(context).apply {
            text = "Startup: open apps minimized"
            isChecked = InputSettings.startOpenAppsMinimized
            setOnCheckedChangeListener { _, checked ->
                InputSettings.setStartOpenAppsMinimized(context, checked)
            }
        }
        fun alignmentControl(title: String, current: () -> BarAlignment, set: (BarAlignment) -> Unit) =
            TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(Color.rgb(210, 220, 218))
                setPadding(0, dp(8), 0, dp(8))
                fun update() { text = "$title  ${current().name.lowercase()}" }
                update()
                setOnClickListener {
                    val choices = BarAlignment.entries.map { it.name.lowercase() }.toTypedArray()
                    android.app.AlertDialog.Builder(context)
                        .setTitle(title)
                        .setSingleChoiceItems(choices, current().ordinal) { dialog, which ->
                            set(BarAlignment.entries[which])
                            update()
                            dialog.dismiss()
                        }
                        .show()
                }
            }
        val toolbarAlignment = alignmentControl(
            "Toolbar alignment",
            { InputSettings.toolbarAlignment },
            { InputSettings.setToolbarAlignment(context, it) },
        )
        val taskbarAlignment = alignmentControl(
            "Taskbar alignment",
            { InputSettings.taskbarAlignment },
            { InputSettings.setTaskbarAlignment(context, it) },
        )
        val personalizationTitle = TextView(context).apply {
            text = "Personalization"
            typeface = Typeface.MONOSPACE
            setTextColor(InputSettings.accentColor)
            setPadding(0, dp(16), 0, dp(4))
        }
        val accentValueLabel = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(210, 220, 218))
        }
        val accentSwatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(28)).apply {
                setMargins(0, 0, dp(10), 0)
            }
        }
        fun updateAccentPreview(color: Int = InputSettings.accentColor) {
            accentValueLabel.text = "Accent  ${colorHex(color)}   hue ${"%.0f".format(InputSettings.accentHue)}°   saturation ${"%.0f".format(InputSettings.accentSaturation * 100)}%"
            accentSwatch.background = GradientDrawable().apply {
                setColor(color)
                setStroke(dp(1), Color.rgb(210, 220, 218))
                cornerRadius = dp(4).toFloat()
            }
        }
        val accentWheel = AccentColorWheelView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(260),
            ).apply { setMargins(0, dp(4), 0, dp(4)) }
            setSelection(InputSettings.accentHue, InputSettings.accentSaturation)
            onColorChanged = { hue, saturation, color ->
                InputSettings.setAccent(context, hue, saturation)
                updateAccentPreview(color)
            }
        }
        val accentPreview = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(accentSwatch)
            addView(accentValueLabel)
        }
        val resetAccent = TextView(context).apply {
            text = "reset to cyan default"
            typeface = Typeface.MONOSPACE
            setTextColor(InputSettings.accentColor)
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener {
                InputSettings.resetAccent(context)
                accentWheel.setSelection(InputSettings.accentHue, InputSettings.accentSaturation)
                updateAccentPreview()
                setTextColor(InputSettings.accentColor)
            }
        }
        updateAccentPreview()
        val note = TextView(context).apply {
            text = "Accent changes apply immediately to the phone and Beast and are saved with LATERAL_. Brightness stays matched to the cyan icon while hue and saturation are adjustable."
            textSize = 12f
            setTextColor(Color.rgb(210, 220, 218))
            setPadding(0, dp(10), 0, 0)
        }
        val about = TextView(context).apply {
            text = "About / open-source licenses"
            typeface = Typeface.MONOSPACE
            setTextColor(InputSettings.accentColor)
            setPadding(0, dp(18), 0, dp(18))
            setOnClickListener {
                android.app.AlertDialog.Builder(context)
                    .setTitle("LATERAL_ / licenses")
                    .setMessage(
                        "Embedded keyboard: FlorisBoard v0.5.2\n" +
                            "Copyright © FlorisBoard contributors\n\n" +
                            "FlorisBoard and LATERAL_'s integration changes are licensed " +
                            "under the Apache License 2.0. The complete license and " +
                            "attribution are distributed in third_party/florisboard/LICENSE " +
                            "and NOTICE.\n\nhttps://www.apache.org/licenses/LICENSE-2.0",
                    )
                    .setPositiveButton("Done", null)
                    .show()
            }
        }
        panel.addView(sensitivityLabel)
        panel.addView(sensitivity)
        panel.addView(scrollSensitivity)
        panel.addView(pinchZoomSensitivity)
        panel.addView(invertTwoFingerScroll)
        panel.addView(invertScrollbarScroll)
        panel.addView(momentum)
        panel.addView(startMinimized)
        panel.addView(toolbarAlignment)
        panel.addView(taskbarAlignment)
        panel.addView(appearanceSlider("UI + text scale · Standard", ultrawide = false))
        panel.addView(appearanceSlider("UI + text scale · Ultrawide", ultrawide = true))
        panel.addView(personalizationTitle)
        panel.addView(accentWheel)
        panel.addView(accentPreview)
        panel.addView(resetAccent)
        panel.addView(note)
        panel.addView(about)
        val scrollPanel = ScrollView(context).apply { addView(panel) }
        try {
            dialog = android.app.AlertDialog.Builder(context)
                .setTitle("LATERAL_ / settings")
                .setView(scrollPanel)
                .setPositiveButton("Done", null)
                .show()
            onModalVisibilityChanged(true)
            dialog?.setOnDismissListener {
                lease.release()
                onModalVisibilityChanged(false)
            }
        } catch (error: RuntimeException) {
            lease.release()
            onModalVisibilityChanged(false)
            Toast.makeText(context, error.message ?: "Could not open settings", Toast.LENGTH_SHORT).show()
        }
    }
}
