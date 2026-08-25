package com.lateral.beast

import java.util.concurrent.CopyOnWriteArraySet

/** Write-only PhoneUI keyboard target for the currently focused hosted editor. */
object HostedTextInputSession {
    data class Target(
        val displayId: Int,
        val taskId: Long,
        val androidTaskId: Int,
        val packageName: String,
        val label: String,
        val inputType: Int,
        val imeOptions: Int,
    )

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    var target: Target? = null
        private set

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun begin(target: Target) {
        LauncherSearchSession.close()
        this.target = target
        listeners.forEach { it() }
    }

    fun close(displayId: Int? = null) {
        val current = target ?: return
        if (displayId != null && current.displayId != displayId) return
        target = null
        listeners.forEach { it() }
    }
}
