package com.lateral

import com.lateral.privileged.PrivilegedService
import com.lateral.beast.AndroidTaskSynchronizer
import com.lateral.agent.AgentController
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.embedded.EmbeddedImeRuntime

class LateralApp : FlorisApplication() {
    lateinit var agentController: AgentController
        private set

    override fun onCreate() {
        EmbeddedImeRuntime.enableHostMode()
        super.onCreate()
        agentController = AgentController(this)
        PrivilegedService.init(this)
        PhoneView.init(this)
        AndroidTaskSynchronizer.init(this)
        PrivilegedService.ensureRunning()
    }
}
