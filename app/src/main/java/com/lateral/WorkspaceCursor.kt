package com.lateral

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Cursor state rendered by the Beast workspace itself.
 *
 * Keeping this separate from Android's system pointer mirrors uxspace: raw mouse
 * deltas are useful even when Android has captured or hidden its own cursor.
 */
object WorkspaceCursor {
    data class Position(val xFraction: Float, val yFraction: Float, val visible: Boolean)
    data class Viewport(val displayId: Int, val width: Int, val height: Int)
    data class HorizontalTransform(val displayId: Int, val scaleX: Float)

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val scrollListeners = CopyOnWriteArraySet<(Float) -> Unit>()

    @Volatile private var position = Position(.5f, .5f, false)
    @Volatile private var viewport: Viewport? = null
    @Volatile private var horizontalTransform: HorizontalTransform? = null

    fun position(): Position = position
    fun viewport(displayId: Int): Viewport? = viewport?.takeIf { it.displayId == displayId }

    fun publishViewport(displayId: Int, width: Int, height: Int) {
        if (displayId < 0 || width <= 0 || height <= 0) return
        viewport = Viewport(displayId, width, height)
    }

    /** Matches the centred horizontal render correction used by BeastActivity. */
    fun publishHorizontalTransform(displayId: Int, scaleX: Float) {
        if (displayId < 0) return
        horizontalTransform = HorizontalTransform(displayId, scaleX.coerceIn(.1f, 1f))
    }

    /** Maps a content-space cursor coordinate to the display-space input coordinate. */
    fun inputX(displayId: Int, contentX: Float, width: Float): Float {
        val scale = horizontalTransform?.takeIf { it.displayId == displayId }?.scaleX ?: 1f
        return width / 2f + (contentX - width / 2f) * scale
    }

    fun moveTo(x: Float, y: Float, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        position = Position(
            (x / width).coerceIn(0f, 1f),
            (y / height).coerceIn(0f, 1f),
            true,
        )
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun scroll(wheel: Float) {
        if (wheel != 0f) scrollListeners.forEach { it(wheel) }
    }

    fun addScrollListener(listener: (Float) -> Unit) {
        scrollListeners += listener
    }

    fun removeScrollListener(listener: (Float) -> Unit) {
        scrollListeners -= listener
    }
}
