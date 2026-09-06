package com.lateral.beast

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.lateral.InputSettings
import java.util.WeakHashMap

internal const val OMARCHY_WINDOW_BORDER_DP = 2
private val WINDOW_INTERIOR = Color.rgb(12, 14, 15)
private val INACTIVE_BORDER = Color.argb(0xAA, 0x59, 0x59, 0x59)

/** Omarchy-style 2px frame: 45° accent gradient when focused, muted gray otherwise. */
internal fun omarchyWindowBorder(focused: Boolean, borderPx: Int): Drawable {
    val outer = if (focused) {
        GradientDrawable(GradientDrawable.Orientation.TL_BR, omarchyActiveBorderColors())
    } else {
        GradientDrawable().apply { setColor(INACTIVE_BORDER) }
    }
    val inner = GradientDrawable().apply { setColor(WINDOW_INTERIOR) }
    return LayerDrawable(arrayOf(outer, inner)).apply {
        setLayerInset(1, borderPx, borderPx, borderPx, borderPx)
    }
}

private fun omarchyActiveBorderColors(): IntArray {
    val hsv = FloatArray(3)
    Color.colorToHSV(InputSettings.accentColor, hsv)
    val start = Color.HSVToColor(0xEE, hsv)
    hsv[0] = if (hsv[0] in 140f..175f) 193f else 156f
    hsv[1] = hsv[1].coerceAtLeast(.75f)
    hsv[2] = hsv[2].coerceAtLeast(.85f)
    return intArrayOf(start, Color.HSVToColor(0xEE, hsv))
}

private data class BeastHoverState(
    val normalBackground: android.graphics.drawable.Drawable?,
    val normalAlpha: Float,
    val textColors: List<Pair<TextView, () -> Int>>,
    var hovered: Boolean = false,
)

private val hoverStates = WeakHashMap<View, BeastHoverState>()

/** Shared pointer feedback for BeastUI's text-based controls. */
internal fun View.installBeastHover(
    textColors: List<Pair<TextView, () -> Int>> = emptyList(),
) {
    synchronized(hoverStates) {
        hoverStates[this] = BeastHoverState(background, alpha, textColors)
    }
    setOnHoverListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> updateBeastHover(view, true)
            MotionEvent.ACTION_HOVER_EXIT -> updateBeastHover(view, false)
        }
        false
    }
}

internal fun TextView.installBeastHover(normalColor: (() -> Int)? = null) {
    val initialColor = currentTextColor
    installBeastHover(listOf(this to (normalColor ?: { initialColor })))
}

private fun beastHoverBackground(view: View) = GradientDrawable().apply {
    setColor(Color.rgb(28, 35, 36))
    setStroke(
        view.resources.displayMetrics.density.toInt().coerceAtLeast(1),
        InputSettings.accentOutlineColor(),
    )
}

internal fun View.isBeastHoverTarget(): Boolean = synchronized(hoverStates) {
    hoverStates.containsKey(this)
}

internal fun updateBeastHover(view: View, hovered: Boolean) {
    val state = synchronized(hoverStates) { hoverStates[view] } ?: return
    if (!view.isEnabled || state.hovered == hovered) return
    state.hovered = hovered
    if (hovered) {
        view.background = beastHoverBackground(view)
        state.textColors.forEach { (text, _) -> text.setTextColor(Color.WHITE) }
        view.alpha = 1f
    } else {
        view.background = state.normalBackground
        state.textColors.forEach { (text, color) -> text.setTextColor(color()) }
        view.alpha = state.normalAlpha
    }
}
