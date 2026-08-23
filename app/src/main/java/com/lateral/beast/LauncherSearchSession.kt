package com.lateral.beast

import java.util.concurrent.CopyOnWriteArraySet

/** Shared query/submit lifecycle for Beast results with a native PhoneUI text field. */
object LauncherSearchSession {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    var active: Boolean = false
        private set
    var query: String = ""
        private set
    var submitSequence: Long = 0L
        private set

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun begin() {
        query = ""
        active = true
        notifyChanged()
    }

    fun updateQuery(value: String) {
        if (!active || query == value) return
        query = value
        notifyChanged()
    }

    fun submit() {
        if (!active) return
        submitSequence++
        notifyChanged()
    }

    fun close() {
        if (!active) return
        active = false
        query = ""
        notifyChanged()
    }

    private fun notifyChanged() = listeners.forEach { it() }
}
