package com.lateral

import com.lateral.privileged.PrivilegedService
import com.lateral.beast.AndroidTaskSynchronizer
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.embedded.EmbeddedImeRuntime

class LateralApp : FlorisApplication() {
    override fun onCreate() {
        EmbeddedImeRuntime.enableHostMode()
        super.onCreate()
        PrivilegedService.init(this)
        AndroidTaskSynchronizer.init(this)
        PrivilegedService.ensureRunning()
    }
}
