package com.lateral

import android.content.Context
import android.graphics.Color
import java.util.concurrent.CopyOnWriteArraySet

enum class BarAlignment { LEFT, CENTER, RIGHT }

/** Persisted PhoneUI input preferences shared with the Beast workspace. */
object InputSettings {
    private const val PREFS = "lateral_input"
    private const val KEY_SENSITIVITY = "cursor_sensitivity"
    private const val KEY_SCROLL_SENSITIVITY = "scroll_sensitivity"
    private const val KEY_PINCH_ZOOM_SENSITIVITY = "pinch_zoom_sensitivity"
    /** Retained only to migrate installations created before scroll sources were split. */
    private const val KEY_INVERT_SCROLL = "invert_scroll"
    private const val KEY_INVERT_TWO_FINGER_SCROLL = "invert_two_finger_scroll"
    private const val KEY_INVERT_SCROLLBAR_SCROLL = "invert_scrollbar_scroll"
    private const val KEY_MOMENTUM = "scroll_momentum"
    private const val KEY_ULTRAWIDE = "beast_ultrawide"
    private const val KEY_STANDARD_UI_SCALE = "standard_ui_scale"
    private const val KEY_ULTRAWIDE_UI_SCALE = "ultrawide_ui_scale"
    private const val KEY_STANDARD_FONT_SCALE = "standard_font_scale"
    private const val KEY_ULTRAWIDE_FONT_SCALE = "ultrawide_font_scale"
    private const val KEY_START_MINIMIZED = "start_open_apps_minimized"
    private const val KEY_CENTER_BOTTOM_CONTROLS = "center_bottom_controls"
    private const val KEY_TOOLBAR_ALIGNMENT = "toolbar_alignment"
    private const val KEY_TASKBAR_ALIGNMENT = "taskbar_alignment"
    private const val KEY_ACCENT_HUE = "accent_hue"
    private const val KEY_ACCENT_SATURATION = "accent_saturation"

    private val defaultAccentHsv = FloatArray(3).also {
        Color.colorToHSV(Color.rgb(121, 199, 211), it)
    }

    @Volatile var cursorSensitivity = 1.4f
        private set
    @Volatile var scrollSensitivity = 1.0f
        private set
    @Volatile var pinchZoomSensitivity = 1.0f
        private set
    @Volatile var invertTwoFingerScroll = false
        private set
    @Volatile var invertScrollbarScroll = false
        private set
    @Volatile var momentumEnabled = true
        private set
    @Volatile var ultrawideEnabled = false
        private set
    @Volatile var standardUiScale = 1.10f
        private set
    @Volatile var ultrawideUiScale = 1.50f
        private set
    @Volatile var standardFontScale = 1.10f
        private set
    @Volatile var ultrawideFontScale = 1.50f
        private set
    @Volatile var startOpenAppsMinimized = true
        private set
    @Volatile var toolbarAlignment = BarAlignment.RIGHT
        private set
    @Volatile var taskbarAlignment = BarAlignment.LEFT
        private set
    @Volatile var accentHue = defaultAccentHsv[0]
        private set
    @Volatile var accentSaturation = defaultAccentHsv[1]
        private set
    @Volatile var accentColor = Color.rgb(121, 199, 211)
        private set
    /** PhoneUI is authoritative for hosted-app resources, independent of Beast chrome. */
    @Volatile var phoneAppDensityDpi = DEFAULT_PHONE_APP_DENSITY_DPI
        private set
    @Volatile var phoneFontScale = 1f
        private set
    @Volatile var phoneAppWindowHeightPx = 1
        private set
    private val sensitivityListeners = CopyOnWriteArraySet<(Float) -> Unit>()
    private val appearanceListeners = CopyOnWriteArraySet<() -> Unit>()
    private val phoneConfigurationListeners = CopyOnWriteArraySet<(PhoneAppConfiguration) -> Unit>()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cursorSensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.4f)
        scrollSensitivity = prefs.getFloat(KEY_SCROLL_SENSITIVITY, 1.0f).coerceIn(.4f, 2.5f)
        pinchZoomSensitivity = prefs.getFloat(KEY_PINCH_ZOOM_SENSITIVITY, 1.0f).coerceIn(.4f, 2.5f)
        val legacyInvertScroll = prefs.getBoolean(KEY_INVERT_SCROLL, true)
        // Prior builds had hard-coded app directions and used the one toggle only
        // for workspace scrolling. Preserve that behavior on migration.
        invertTwoFingerScroll = prefs.getBoolean(KEY_INVERT_TWO_FINGER_SCROLL, false)
        invertScrollbarScroll = prefs.getBoolean(
            KEY_INVERT_SCROLLBAR_SCROLL,
            !legacyInvertScroll,
        )
        momentumEnabled = prefs.getBoolean(KEY_MOMENTUM, true)
        ultrawideEnabled = prefs.getBoolean(KEY_ULTRAWIDE, false)
        standardUiScale = prefs.getFloat(KEY_STANDARD_UI_SCALE, 1.10f).coerceIn(.85f, 2.00f)
        ultrawideUiScale = prefs.getFloat(KEY_ULTRAWIDE_UI_SCALE, 1.50f).coerceIn(.85f, 5.00f)
        // UI geometry is authoritative for the one consolidated appearance scale.
        // Ignore legacy divergent font values and lock text to the same value.
        standardFontScale = standardUiScale
        ultrawideFontScale = ultrawideUiScale
        startOpenAppsMinimized = prefs.getBoolean(KEY_START_MINIMIZED, true)
        val legacyBottomControlsCentered = prefs.getBoolean(KEY_CENTER_BOTTOM_CONTROLS, true)
        // Preserve the old two-position preference as the migration default.
        toolbarAlignment = prefs.getString(KEY_TOOLBAR_ALIGNMENT, null)
            ?.let { runCatching { BarAlignment.valueOf(it) }.getOrNull() }
            ?: if (legacyBottomControlsCentered) BarAlignment.RIGHT else BarAlignment.LEFT
        taskbarAlignment = prefs.getString(KEY_TASKBAR_ALIGNMENT, BarAlignment.LEFT.name)
            ?.let { runCatching { BarAlignment.valueOf(it) }.getOrNull() }
            ?: BarAlignment.LEFT
        accentHue = prefs.getFloat(KEY_ACCENT_HUE, defaultAccentHsv[0]).coerceIn(0f, 360f)
        accentSaturation = prefs.getFloat(KEY_ACCENT_SATURATION, defaultAccentHsv[1]).coerceIn(0f, 1f)
        accentColor = colorForAccent(accentHue, accentSaturation)
    }

    fun setCursorSensitivity(context: Context, value: Float) {
        cursorSensitivity = value.coerceIn(.4f, 2.5f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SENSITIVITY, cursorSensitivity).apply()
        sensitivityListeners.forEach { it(cursorSensitivity) }
    }

    fun setScrollSensitivity(context: Context, value: Float) {
        scrollSensitivity = value.coerceIn(.4f, 2.5f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SCROLL_SENSITIVITY, scrollSensitivity).apply()
    }

    fun setPinchZoomSensitivity(context: Context, value: Float) {
        pinchZoomSensitivity = value.coerceIn(.4f, 2.5f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_PINCH_ZOOM_SENSITIVITY, pinchZoomSensitivity).apply()
    }

    fun addSensitivityListener(listener: (Float) -> Unit) { sensitivityListeners += listener }
    fun removeSensitivityListener(listener: (Float) -> Unit) { sensitivityListeners -= listener }

    fun uiScale(ultrawide: Boolean) = if (ultrawide) ultrawideUiScale else standardUiScale
    fun fontScale(ultrawide: Boolean) = uiScale(ultrawide)

    fun setUiScale(context: Context, ultrawide: Boolean, value: Float) {
        val scale = value.coerceIn(.85f, if (ultrawide) 5.00f else 2.00f)
        if (ultrawide) {
            ultrawideUiScale = scale
            ultrawideFontScale = scale
        } else {
            standardUiScale = scale
            standardFontScale = scale
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(if (ultrawide) KEY_ULTRAWIDE_UI_SCALE else KEY_STANDARD_UI_SCALE, scale)
            .putFloat(if (ultrawide) KEY_ULTRAWIDE_FONT_SCALE else KEY_STANDARD_FONT_SCALE, scale)
            .apply()
        appearanceListeners.forEach { it() }
    }

    fun setFontScale(context: Context, ultrawide: Boolean, value: Float) {
        setUiScale(context, ultrawide, value)
    }

    fun addAppearanceListener(listener: () -> Unit) { appearanceListeners += listener }
    fun removeAppearanceListener(listener: () -> Unit) { appearanceListeners -= listener }

    fun updatePhoneAppConfiguration(densityDpi: Int, fontScale: Float, windowHeightPx: Int) {
        val next = PhoneAppConfiguration(
            densityDpi.coerceIn(MIN_PHONE_APP_DENSITY_DPI, MAX_PHONE_APP_DENSITY_DPI),
            fontScale.coerceIn(.5f, 2.0f),
            windowHeightPx.coerceAtLeast(1),
        )
        if (phoneAppDensityDpi == next.densityDpi && phoneFontScale == next.fontScale &&
            phoneAppWindowHeightPx == next.windowHeightPx
        ) return
        phoneAppDensityDpi = next.densityDpi
        phoneFontScale = next.fontScale
        phoneAppWindowHeightPx = next.windowHeightPx
        phoneConfigurationListeners.forEach { it(next) }
    }

    fun addPhoneAppConfigurationListener(listener: (PhoneAppConfiguration) -> Unit) {
        phoneConfigurationListeners += listener
    }

    fun removePhoneAppConfigurationListener(listener: (PhoneAppConfiguration) -> Unit) {
        phoneConfigurationListeners -= listener
    }

    fun setAccent(context: Context, hue: Float, saturation: Float) {
        accentHue = ((hue % 360f) + 360f) % 360f
        accentSaturation = saturation.coerceIn(0f, 1f)
        accentColor = colorForAccent(accentHue, accentSaturation)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_ACCENT_HUE, accentHue)
            .putFloat(KEY_ACCENT_SATURATION, accentSaturation)
            .apply()
        appearanceListeners.forEach { it() }
    }

    fun resetAccent(context: Context) {
        setAccent(context, defaultAccentHsv[0], defaultAccentHsv[1])
    }

    fun accentOutlineColor(): Int {
        val hsv = floatArrayOf(accentHue, maxOf(.18f, accentSaturation * .72f), .48f)
        return Color.HSVToColor(hsv)
    }

    fun accentValue() = defaultAccentHsv[2]

    private fun colorForAccent(hue: Float, saturation: Float): Int =
        Color.HSVToColor(floatArrayOf(hue, saturation, defaultAccentHsv[2]))

    fun setStartOpenAppsMinimized(context: Context, value: Boolean) {
        startOpenAppsMinimized = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_START_MINIMIZED, value).apply()
    }

    fun setToolbarAlignment(context: Context, value: BarAlignment) {
        toolbarAlignment = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOOLBAR_ALIGNMENT, value.name).apply()
        appearanceListeners.forEach { it() }
    }

    fun setTaskbarAlignment(context: Context, value: BarAlignment) {
        taskbarAlignment = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TASKBAR_ALIGNMENT, value.name).apply()
        appearanceListeners.forEach { it() }
    }

    fun setInvertTwoFingerScroll(context: Context, value: Boolean) {
        invertTwoFingerScroll = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_INVERT_TWO_FINGER_SCROLL, value).apply()
    }

    fun setInvertScrollbarScroll(context: Context, value: Boolean) {
        invertScrollbarScroll = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_INVERT_SCROLLBAR_SCROLL, value).apply()
    }

    fun setMomentumEnabled(context: Context, value: Boolean) {
        momentumEnabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MOMENTUM, value).apply()
    }

    fun setUltrawideEnabled(context: Context, value: Boolean) {
        ultrawideEnabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ULTRAWIDE, value).apply()
        appearanceListeners.forEach { it() }
    }

    data class PhoneAppConfiguration(val densityDpi: Int, val fontScale: Float, val windowHeightPx: Int)

    private const val DEFAULT_PHONE_APP_DENSITY_DPI = 240
    private const val MIN_PHONE_APP_DENSITY_DPI = 120
    private const val MAX_PHONE_APP_DENSITY_DPI = 640
}
