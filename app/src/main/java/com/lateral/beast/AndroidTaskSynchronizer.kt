package com.lateral.beast

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.lateral.InputSettings
import com.lateral.privileged.PrivilegedService

/** Continuously reconciles Android's task model into the shared workspace registry. */
object AndroidTaskSynchronizer {
    private const val POLL_MS = 500L

    private val main = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private var started = false
    private var inFlight = false
    private var firstSnapshot = true
    private var homePackages = emptySet<String>()

    private val privilegedListener: () -> Unit = {
        if (PrivilegedService.state == PrivilegedService.State.READY) requestImmediate()
    }
    private val poll = object : Runnable {
        override fun run() {
            reconcileNow()
            main.postDelayed(this, POLL_MS)
        }
    }

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        WorkspaceState.init(appContext)
        InputSettings.load(appContext)
        homePackages = appContext.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        ).mapTo(hashSetOf()) { it.activityInfo.packageName }
        WorkspaceState.onImmediateSyncRequested = ::requestImmediate
        PrivilegedService.addListener(privilegedListener)
        main.post(poll)
    }

    fun requestImmediate() {
        if (!started) return
        main.removeCallbacks(poll)
        main.post(poll)
    }

    private fun reconcileNow() {
        if (PrivilegedService.state != PrivilegedService.State.READY || inFlight) return
        inFlight = true
        PrivilegedService.queryTaskSnapshots { raw ->
            inFlight = false
            if (raw == null) return@queryTaskSnapshots
            val snapshots = raw.asSequence()
                .filter { it.packageName != appContext.packageName }
                .filter { it.packageName !in homePackages }
                .filterNot(PrivilegedService.TaskSnapshot::excluded)
                .filter(PrivilegedService.TaskSnapshot::isAvailable)
                .filter { isInstalledAndEnabled(it.packageName) }
                .filter { it.activityType == 0 || it.activityType == 1 }
                // Android Overview membership is the source of truth. Cached and
                // stopped-but-restorable entries are still open tasks even when the OEM
                // reports both isRunning and hasBeenVisible as false.
                .toList()
            WorkspaceState.reconcile(
                snapshots,
                initialImportMinimized = InputSettings.startOpenAppsMinimized,
                initial = firstSnapshot,
            )
            applyLabels(snapshots)
            firstSnapshot = false
        }
    }

    /** Vendor recents can retain rows for packages unavailable to the current user. */
    private fun isInstalledAndEnabled(packageName: String): Boolean = runCatching {
        appContext.packageManager.getApplicationInfo(packageName, 0).enabled
    }.getOrDefault(false)

    private fun applyLabels(snapshots: List<PrivilegedService.TaskSnapshot>) {
        val packageManager = appContext.packageManager
        snapshots.forEach { snapshot ->
            val label = runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(snapshot.packageName, 0),
                ).toString()
            }.getOrDefault(snapshot.packageName.substringAfterLast('.'))
            WorkspaceState.updateLabel(snapshot.taskId, label)
        }
    }

    fun closeTask(task: BeastTask, callback: (Boolean) -> Unit) {
        val taskId = task.androidTaskId
        if (taskId == null) {
            WorkspaceState.removeClosed(task.id)
            callback(true)
            return
        }
        PrivilegedService.removeTaskAsync(taskId) { removed ->
            if (removed) {
                WorkspaceState.removeClosed(task.id)
                requestImmediate()
            } else {
                WorkspaceState.markCloseFailed(task.id, "Android did not remove task $taskId")
            }
            callback(removed)
        }
    }
}
