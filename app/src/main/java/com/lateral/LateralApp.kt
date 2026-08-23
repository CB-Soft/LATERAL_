package com.lateral

import android.app.Application
import com.lateral.privileged.PrivilegedService
import com.lateral.beast.AndroidTaskSynchronizer

class LateralApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivilegedService.init(this)
        AndroidTaskSynchronizer.init(this)
        PrivilegedService.ensureRunning()
    }
}
