package com.lateral.beast

/**
 * Direct in-process bridge from PhoneUI's trackpad cursor to a hosted app display.
 * It bypasses the physical Beast display when the cursor is over an app surface,
 * avoiding a second Android input-dispatch and preserving multi-pointer gestures.
 */
object BeastInputRouter {
    interface Handler {
        fun routeAppScroll(displayId: Int, x: Int, y: Int, amount: Float): Boolean
        fun routeAppPinch(displayId: Int, x: Int, y: Int, scale: Float): Boolean
        fun routeAppClick(displayId: Int, x: Int, y: Int, button: Int): Boolean
        fun routeAppPointer(
            displayId: Int,
            x: Int,
            y: Int,
            action: Int,
            button: Int,
            buttonState: Int,
        ): Boolean
    }

    @Volatile private var handler: Handler? = null

    fun attach(handler: Handler) {
        this.handler = handler
    }

    fun detach(handler: Handler) {
        if (this.handler === handler) this.handler = null
    }

    fun scroll(displayId: Int, x: Int, y: Int, amount: Float): Boolean =
        handler?.routeAppScroll(displayId, x, y, amount) == true

    fun pinch(displayId: Int, x: Int, y: Int, scale: Float): Boolean =
        handler?.routeAppPinch(displayId, x, y, scale) == true

    fun click(displayId: Int, x: Int, y: Int, button: Int): Boolean =
        handler?.routeAppClick(displayId, x, y, button) == true

    fun pointer(
        displayId: Int,
        x: Int,
        y: Int,
        action: Int,
        button: Int,
        buttonState: Int,
    ): Boolean = handler?.routeAppPointer(displayId, x, y, action, button, buttonState) == true
}
