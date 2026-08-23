package com.lateral.beast

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.lateral.InputSettings
import java.util.WeakHashMap

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
