package com.lateral.beast

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display
import com.lateral.MainActivity
import com.lateral.privileged.PrivilegedService
import java.util.concurrent.atomic.AtomicBoolean

/** The single routing point for the one process-owned Beast workspace task. */
object BeastWorkspaceController {
    private val liveCleanupDone = AtomicBoolean(false)
    /** Remove task records produced by older standard/MULTIPLE_TASK builds. */
    fun cleanupLegacyTasks(activity: Activity) {
        if (!liveCleanupDone.compareAndSet(false, true)) return
        val tasks = taskRecords(activity)
        val currentTaskId = activity.taskId

        tasks.forEach { record ->
            val info = record.info
            val remove = when {
                info.taskId == currentTaskId -> false
                isMainRoot(info.baseActivity) -> true
                isBeastTask(info) -> true
                else -> false
            }
            if (remove) runCatching { record.appTask.finishAndRemoveTask() }
                .onFailure { Log.w(TAG, "could not remove stale task ${info.taskId}", it) }
        }
    }

    /** Remove non-running recents records that AppTask cannot expose after an upgrade. */
    fun cleanupHistoricalTasks(activity: Activity) {
        if (PrivilegedService.state != PrivilegedService.State.READY) return
        val beastTaskId = taskRecords(activity)
            .filter { isBeastTask(it.info) }
            .maxByOrNull { it.info.taskId }
            ?.info?.taskId
        PrivilegedService.recentTasks()
            .filter { it.packageName == activity.packageName }
            .filter { it.taskId != activity.taskId && it.taskId != beastTaskId }
            .forEach { PrivilegedService.removeTask(it.taskId) }
    }

    /** Reuse, relocate, and signal the single Beast task; create it only when absent. */
    fun ensure(
        activity: Activity,
        display: Display,
        action: String = BeastActivity.ACTION_ENSURE_WORKSPACE,
    ): Boolean {
        val beastTasks = taskRecords(activity).filter { isBeastTask(it.info) }
        val survivor = beastTasks.maxByOrNull { it.info.taskId }
        beastTasks.filterNot { it === survivor }.forEach { duplicate ->
            runCatching { duplicate.appTask.finishAndRemoveTask() }
                .onFailure { Log.w(TAG, "could not remove duplicate Beast task", it) }
        }

        survivor?.info?.let { info ->
            // Starting a recent task is a focus-changing operation on REDMAGIC. Only
            // use it for an actual relocation; the singleTask intent below is enough
            // to deliver ENSURE_WORKSPACE when the task is already on the Beast.
            if (BeastActivity.workspaceDisplayId() != display.displayId &&
                PrivilegedService.state == PrivilegedService.State.READY
            ) {
                PrivilegedService.startRecentTaskOnDisplay(info.taskId, display.displayId)
            }
        }

        val intent = Intent(activity, BeastActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = display.displayId }
        return runCatching { activity.startActivity(intent, options.toBundle()) }
            .onFailure { Log.e(TAG, "could not ensure Beast workspace on ${display.displayId}", it) }
            .isSuccess
    }

    private data class AppTaskRecord(
        val appTask: ActivityManager.AppTask,
        val info: ActivityManager.RecentTaskInfo,
    )

    private fun taskRecords(context: Context): List<AppTaskRecord> =
        context.getSystemService(ActivityManager::class.java).appTasks.orEmpty().mapNotNull { task ->
            task.taskInfo?.let { AppTaskRecord(task, it) }
        }

    private fun isBeastTask(info: ActivityManager.RecentTaskInfo): Boolean =
        isBeastRoot(info.baseActivity) || isBeastRoot(info.topActivity)

    private fun isBeastRoot(component: ComponentName?): Boolean =
        component?.className == BeastActivity::class.java.name

    private fun isMainRoot(component: ComponentName?): Boolean =
        component?.className == MainActivity::class.java.name

    private const val TAG = "Lateral/Workspace"
}
