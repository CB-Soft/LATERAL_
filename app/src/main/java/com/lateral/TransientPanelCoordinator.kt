package com.lateral

import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the one transient LATERAL_ panel that may be visible across PhoneUI and BeastUI.
 *
 * Activities keep their own views and dialogs; this object only arbitrates ownership.
 * Claiming a panel closes the previous owner before returning, so two display clicks
 * cannot leave overlapping windows or race two modal operations against one Activity.
 */
object TransientPanelCoordinator {
    enum class Kind { APPS, SETTINGS, CONTROLLER }

    private data class ActivePanel(
        val token: Long,
        val displayId: Int,
        val kind: Kind,
        val close: () -> Unit,
    )

    class Lease internal constructor(private val token: Long) {
        fun release() = releaseToken(token)
    }

    private val nextToken = AtomicLong(1L)
    private var active: ActivePanel? = null

    /** Must be called by a UI callback; Android dispatches both activities on this thread. */
    fun claim(displayId: Int, kind: Kind, close: () -> Unit): Lease {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Transient panels must be claimed on the main thread"
        }
        val previous = synchronized(this) {
            active.also { active = null }
        }
        // Close outside the monitor: dismiss callbacks are allowed to release their lease.
        runCatching { previous?.close?.invoke() }

        val token = nextToken.getAndIncrement()
        synchronized(this) { active = ActivePanel(token, displayId, kind, close) }
        return Lease(token)
    }

    private fun releaseToken(token: Long) {
        synchronized(this) {
            if (active?.token == token) active = null
        }
    }
}
