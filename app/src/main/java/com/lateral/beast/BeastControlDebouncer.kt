package com.lateral.beast

import android.os.SystemClock

/** One gate for all Beast shell actions; hosted app input bypasses it entirely. */
class BeastControlDebouncer {
    private var blockedUntil = SystemClock.uptimeMillis() + DEBOUNCE_MS

    fun submit(action: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now < blockedUntil) return
        blockedUntil = now + DEBOUNCE_MS
        action()
    }

    companion object {
        const val DEBOUNCE_MS = 320L
    }
}
