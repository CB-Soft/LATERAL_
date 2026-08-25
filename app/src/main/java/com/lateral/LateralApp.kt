package com.lateral

import com.lateral.privileged.PrivilegedService
import com.lateral.beast.AndroidTaskSynchronizer
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.embedded.EmbeddedImeRuntime

class LateralApp : FlorisApplication() {
    override fun onCreate() {
        EmbeddedImeRuntime.enableHostMode()
        super.onCreate()
        // Warm FlorisBoard's app-owned service without selecting it as Android's IME.
        // This moves its class/resource and engine initialization off the first editor tap.
        EmbeddedImeRuntime.prewarm(this)
        PrivilegedService.init(this)
        AndroidTaskSynchronizer.init(this)
        PrivilegedService.ensureRunning()
    }
}
