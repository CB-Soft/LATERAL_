package com.lateral.beast

import android.content.Context
import android.os.SystemClock
import com.lateral.privileged.PrivilegedService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

enum class PresentationMode(val marker: String) {
    PHONE("P"), TABLET("T"), FULLSCREEN("F");
    fun nextFramed(): PresentationMode = if (this == PHONE) TABLET else PHONE
}

enum class TaskPlacement { PHONE, BEAST_VISIBLE, BEAST_MINIMIZED, PENDING_BEAST }

data class BeastTask(
    val id: Long,
    var packageName: String,
    var activityName: String,
    var label: String,
    var shortLabel: String,
    var androidTaskId: Int? = null,
    var instanceTitle: String = "",
    var mode: PresentationMode = PresentationMode.PHONE,
    var placement: TaskPlacement = TaskPlacement.PHONE,
    var lastInteractionSequence: Long = 0L,
    var androidDisplayId: Int = 0,
    var overviewRank: Int = Int.MAX_VALUE,
    var supportsMultiWindow: Boolean = true,
    var closeError: String? = null,
    var beastRequestUntil: Long = 0L,
    /** Trusted display assigned to a provisional launcher request before task-id binding. */
    var pendingDisplayId: Int? = null,
) {
    var minimized: Boolean
        get() = placement != TaskPlacement.BEAST_VISIBLE
        set(value) {
            placement = if (!value) TaskPlacement.BEAST_VISIBLE else when (placement) {
                TaskPlacement.PHONE -> TaskPlacement.PHONE
                else -> TaskPlacement.BEAST_MINIMIZED
            }
        }
}

/** Shared Android-task registry and Beast presentation state. Main-thread mutations only. */
object WorkspaceState {
    private val pendingIds = AtomicLong(-1L)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val viewportListeners = CopyOnWriteArraySet<(Float) -> Unit>()
    private val mutableTasks = mutableListOf<BeastTask>()
    private val missingCounts = mutableMapOf<Int, Int>()
    private var interactionSequence = 0L
    private var displayModeRestoreTaskId: Long? = null
    private var displayModeRestoreExpiresAt = 0L
    private var displayModeExpectedUltrawide: Boolean? = null
    private var revealFocusedTaskRequested = false
    private var prefs: android.content.SharedPreferences? = null
    private var restored = emptyMap<Int, Persisted>()

    var onImmediateSyncRequested: (() -> Unit)? = null

    val tasks: List<BeastTask> get() = mutableTasks.toList()
    var focusedTaskId: Long? = null
        private set
    var viewportPosition: Float = 0f
        private set

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("workspace_tasks", Context.MODE_PRIVATE)
        restored = readPersisted()
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    fun addViewportListener(listener: (Float) -> Unit) { viewportListeners += listener }
    fun removeViewportListener(listener: (Float) -> Unit) { viewportListeners -= listener }

    fun setViewportPosition(position: Float) {
        val next = position.coerceIn(0f, 1f)
        if (kotlin.math.abs(next - viewportPosition) < .001f) return
        viewportPosition = next
        viewportListeners.forEach { it(next) }
    }

    /** Consumes a one-shot reveal request from an explicit app/taskbar interaction. */
    fun consumeRevealFocusedTaskRequest(): Boolean {
        val requested = revealFocusedTaskRequested
        revealFocusedTaskRequested = false
        return requested
    }

    fun beginDisplayModeSwitch(ultrawide: Boolean) {
        displayModeRestoreTaskId = focusedTaskId
        displayModeExpectedUltrawide = ultrawide
        displayModeRestoreExpiresAt = SystemClock.uptimeMillis() + DISPLAY_MODE_HANDOFF_TIMEOUT_MS
    }

    fun cancelDisplayModeSwitch() {
        displayModeRestoreTaskId = null
        displayModeExpectedUltrawide = null
        displayModeRestoreExpiresAt = 0L
    }

    fun hasPendingDisplayModeFocusRestore(): Boolean {
        if (displayModeRestoreTaskId == null) return false
        if (SystemClock.uptimeMillis() <= displayModeRestoreExpiresAt) return true
        cancelDisplayModeSwitch()
        return false
    }

    fun restoreFocusAfterDisplayModeSwitch(physicalWidth: Int? = null): Boolean {
        if (!hasPendingDisplayModeFocusRestore()) return false
        val expected = displayModeExpectedUltrawide
        if (expected != null && physicalWidth != null && (physicalWidth >= 3000) != expected) {
            return false
        }
        val id = displayModeRestoreTaskId ?: return false
        cancelDisplayModeSwitch()
        if (mutableTasks.none { it.id == id }) return false
        if (focusedTaskId != id) {
            focusedTaskId = id
            notifyChanged()
        }
        return true
    }

    fun reconcile(
        snapshots: List<PrivilegedService.TaskSnapshot>,
        initialImportMinimized: Boolean,
        initial: Boolean,
    ) {
        val incomingIds = snapshots.mapTo(hashSetOf()) { it.taskId }
        var changed = false

        snapshots.forEach { snapshot ->
            missingCounts.remove(snapshot.taskId)
            var task = mutableTasks.firstOrNull { it.androidTaskId == snapshot.taskId }
            if (task == null) {
                task = mutableTasks.firstOrNull {
                    it.androidTaskId == null &&
                        it.placement == TaskPlacement.PENDING_BEAST &&
                        it.pendingDisplayId == snapshot.displayId &&
                        it.packageName == snapshot.packageName
                }
                if (task != null) {
                    task.androidTaskId = snapshot.taskId
                    task.pendingDisplayId = null
                    if (task.placement == TaskPlacement.PENDING_BEAST) {
                        task.placement = TaskPlacement.BEAST_VISIBLE
                    }
                } else {
                    val saved = restored[snapshot.taskId]
                        ?.takeIf { it.packageName == snapshot.packageName }
                    val placement = saved?.placement ?: when {
                        initial && !initialImportMinimized -> TaskPlacement.BEAST_VISIBLE
                        else -> TaskPlacement.PHONE
                    }
                    val fallbackLabel = snapshot.packageName.substringAfterLast('.')
                    task = BeastTask(
                        id = snapshot.taskId.toLong(),
                        packageName = snapshot.packageName,
                        activityName = snapshot.activityName,
                        label = fallbackLabel,
                        shortLabel = shortLabel(fallbackLabel),
                        androidTaskId = snapshot.taskId,
                        instanceTitle = snapshot.title,
                        mode = saved?.mode ?: PresentationMode.PHONE,
                        placement = placement,
                        lastInteractionSequence = nextInteractionSequence(),
                    )
                    val restoredOrder = saved?.beastOrder
                    if (restoredOrder == null) {
                        mutableTasks += task
                    } else {
                        mutableTasks.add(restoredOrder.coerceIn(0, mutableTasks.size), task)
                    }
                }
                changed = true
            }
            if (task.packageName != snapshot.packageName || task.activityName != snapshot.activityName ||
                task.instanceTitle != snapshot.title || task.androidDisplayId != snapshot.displayId ||
                task.overviewRank != snapshot.overviewRank ||
                task.supportsMultiWindow != snapshot.supportsMultiWindow
            ) {
                task.packageName = snapshot.packageName
                task.activityName = snapshot.activityName
                task.instanceTitle = snapshot.title
                task.androidDisplayId = snapshot.displayId
                task.overviewRank = snapshot.overviewRank
                task.supportsMultiWindow = snapshot.supportsMultiWindow
                changed = true
            }
            val now = SystemClock.uptimeMillis()
            if (snapshot.displayId == android.view.Display.DEFAULT_DISPLAY && snapshot.isVisible &&
                now >= task.beastRequestUntil && task.placement != TaskPlacement.PHONE
            ) {
                task.placement = TaskPlacement.PHONE
                if (focusedTaskId == task.id) focusedTaskId = null
                changed = true
            } else if (snapshot.displayId != android.view.Display.DEFAULT_DISPLAY &&
                snapshot.displayId >= 0 && task.placement == TaskPlacement.PHONE
            ) {
                task.placement = TaskPlacement.BEAST_VISIBLE
                changed = true
            }
        }

        mutableTasks.filter { it.androidTaskId != null && it.androidTaskId !in incomingIds }
            .toList().forEach { task ->
                val taskId = task.androidTaskId ?: return@forEach
                val misses = (missingCounts[taskId] ?: 0) + 1
                missingCounts[taskId] = misses
                if (misses >= MISSING_GRACE_POLLS) {
                    mutableTasks.remove(task)
                    missingCounts.remove(taskId)
                    if (focusedTaskId == task.id) focusedTaskId = null
                    changed = true
                }
            }

        if (focusedTaskId == null) {
            focusedTaskId = mutableTasks.firstOrNull { it.placement == TaskPlacement.BEAST_VISIBLE }?.id
        }
        if (changed) notifyChanged()
    }

    fun updateLabel(androidTaskId: Int, label: String) {
        val task = mutableTasks.firstOrNull { it.androidTaskId == androidTaskId } ?: return
        if (task.label == label) return
        task.label = label
        task.shortLabel = shortLabel(label)
        notifyChanged()
    }

    fun launchNew(packageName: String, activityName: String, label: String): BeastTask {
        val task = BeastTask(
            id = pendingIds.getAndDecrement(), packageName = packageName,
            activityName = activityName, label = label, shortLabel = shortLabel(label),
            placement = TaskPlacement.PENDING_BEAST,
            lastInteractionSequence = nextInteractionSequence(),
        )
        // Beast order is intentionally independent of Android Overview. New instances
        // append without disturbing the user's existing spatial layout.
        mutableTasks.add(task)
        focusedTaskId = task.id
        revealFocusedTaskRequested = true
        notifyChanged()
        onImmediateSyncRequested?.invoke()
        return task
    }

    /** Correlate a provisional launch with its dedicated trusted display. */
    fun markPendingDisplayReady(id: Long, displayId: Int) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        if (task.placement != TaskPlacement.PENDING_BEAST || task.pendingDisplayId == displayId) return
        task.pendingDisplayId = displayId
        task.beastRequestUntil = SystemClock.uptimeMillis() + BEAST_MOVE_GRACE_MS
        notifyChanged()
        onImmediateSyncRequested?.invoke()
    }

    /** Keep the provisional card visible while Android creates and reports its task. */
    fun markPendingHosted(id: Long) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        if (task.placement != TaskPlacement.PENDING_BEAST) return
        task.beastRequestUntil = SystemClock.uptimeMillis() + BEAST_MOVE_GRACE_MS
        focusedTaskId = id
        notifyChanged()
        onImmediateSyncRequested?.invoke()
    }

    fun focus(id: Long, reveal: Boolean = false) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        if (!task.supportsMultiWindow) {
            task.closeError = "This app cannot move to a secondary display"
            notifyChanged()
            return
        }
        task.lastInteractionSequence = nextInteractionSequence()
        task.placement = TaskPlacement.BEAST_VISIBLE
        task.beastRequestUntil = SystemClock.uptimeMillis() + BEAST_MOVE_GRACE_MS
        task.closeError = null
        focusedTaskId = id
        if (reveal) revealFocusedTaskRequested = true
        notifyChanged()
        onImmediateSyncRequested?.invoke()
    }

    fun setMode(id: Long, mode: PresentationMode) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        task.mode = mode
        task.placement = TaskPlacement.BEAST_VISIBLE
        task.lastInteractionSequence = nextInteractionSequence()
        focusedTaskId = id
        notifyChanged()
    }

    fun move(id: Long, delta: Int) {
        val from = mutableTasks.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, mutableTasks.lastIndex)
        if (from == to) return
        val task = mutableTasks.removeAt(from)
        mutableTasks.add(to, task)
        focusedTaskId = id
        notifyChanged()
    }

    fun removeClosed(id: Long): BeastTask? {
        val index = mutableTasks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = mutableTasks.removeAt(index)
        if (focusedTaskId == id) {
            focusedTaskId = mutableTasks.firstOrNull { it.placement == TaskPlacement.BEAST_VISIBLE }?.id
        }
        notifyChanged()
        return removed
    }

    fun markCloseFailed(id: Long, message: String) {
        mutableTasks.firstOrNull { it.id == id }?.let {
            it.closeError = message
            notifyChanged()
        }
    }

    fun minimize(id: Long) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        if (task.placement != TaskPlacement.BEAST_VISIBLE) return
        task.placement = TaskPlacement.BEAST_MINIMIZED
        if (focusedTaskId == id) {
            focusedTaskId = mutableTasks.firstOrNull {
                it.placement == TaskPlacement.BEAST_VISIBLE
            }?.id
        }
        notifyChanged()
    }

    fun showAll() {
        var changed = false
        val requestUntil = SystemClock.uptimeMillis() + BEAST_MOVE_GRACE_MS
        mutableTasks.forEach {
            if (it.placement != TaskPlacement.BEAST_VISIBLE) {
                it.placement = TaskPlacement.BEAST_VISIBLE
                it.beastRequestUntil = requestUntil
                changed = true
            }
        }
        if (changed) {
            focusedTaskId = focusedTaskId ?: mutableTasks.firstOrNull()?.id
            notifyChanged()
        }
    }

    fun minimizeAll() {
        var changed = false
        mutableTasks.forEach {
            if (it.placement == TaskPlacement.BEAST_VISIBLE) {
                it.placement = TaskPlacement.BEAST_MINIMIZED
                changed = true
            }
        }
        if (changed) {
            focusedTaskId = null
            notifyChanged()
        }
    }

    fun closeAllCandidates(): List<BeastTask> = mutableTasks.toList()

    private fun notifyChanged() {
        persist()
        listeners.forEach { it() }
    }

    private fun nextInteractionSequence(): Long = ++interactionSequence
    private fun shortLabel(label: String) =
        label.filter { it.isLetterOrDigit() }.take(12).lowercase().ifBlank { "app" }

    private data class Persisted(
        val packageName: String,
        val placement: TaskPlacement,
        val mode: PresentationMode,
        val beastOrder: Int,
    )

    private fun readPersisted(): Map<Int, Persisted> = runCatching {
        val array = JSONArray(prefs?.getString(KEY_STATE, "[]") ?: "[]")
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                put(item.getInt("taskId"), Persisted(
                    item.getString("package"),
                    TaskPlacement.valueOf(item.getString("placement")),
                    PresentationMode.valueOf(item.getString("mode")),
                    item.optInt("beastOrder", index),
                ))
            }
        }
    }.getOrDefault(emptyMap())

    private fun persist() {
        val array = JSONArray()
        mutableTasks.forEachIndexed { order, task ->
            val taskId = task.androidTaskId ?: return@forEachIndexed
            array.put(JSONObject().apply {
                put("taskId", taskId)
                put("package", task.packageName)
                put("placement", task.placement.name)
                put("mode", task.mode.name)
                put("beastOrder", order)
            })
        }
        prefs?.edit()?.putString(KEY_STATE, array.toString())?.apply()
    }

    private const val KEY_STATE = "state.v2"
    private const val MISSING_GRACE_POLLS = 3
    private const val BEAST_MOVE_GRACE_MS = 2_500L
    private const val DISPLAY_MODE_HANDOFF_TIMEOUT_MS = 15_000L
}
