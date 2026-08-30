package com.lateral.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Owns agent identity, grants, the active job, and provider lifecycle. */
class AgentController(context: Context) {
    private val store = AgentStore(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(AgentSnapshot) -> Unit>()
    private val provider = TawcBridgeProvider { store.load().bridgeUrl }
    private var state = store.load()
    private var activeHandle: JobHandle? = null

    init {
        val restoredJob = state.activeJob
        if (restoredJob?.status == AgentJobStatus.RUNNING) {
            state = state.copy(activeJob = null, history = (state.history + restoredJob.copy(status = AgentJobStatus.FAILED).withEvent(
                AgentEventKind.ERROR,
                "LATERAL_ restarted while the job was running",
            )).takeLast(MAX_HISTORY))
            persist()
        }
    }

    fun addListener(listener: (AgentSnapshot) -> Unit) {
        listeners += listener
        listener(snapshot())
    }

    fun removeListener(listener: (AgentSnapshot) -> Unit) = listeners.remove(listener)

    fun snapshot(): AgentSnapshot = AgentSnapshot(
        bridgeUrl = state.bridgeUrl,
        job = state.activeJob ?: state.history.lastOrNull(),
        grants = state.grants,
        history = state.history,
    )

    fun setBridgeUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (normalized.isBlank()) return
        state = state.copy(bridgeUrl = normalized)
        persist()
        notifyChanged()
    }

    fun probe(callback: (Result<ProviderCapabilities>) -> Unit) = provider.probe(callback)

    fun grantProjectAutomation(projectRoot: String) {
        val capabilities = AgentPolicy.projectCapabilities.map { it.id }
        val grants = state.grants.filterNot { it.projectRoot == projectRoot } +
            capabilities.map { CapabilityGrant(it, PermissionScope.PROJECT, projectRoot) }
        state = state.copy(grants = grants)
        persist()
        notifyChanged()
    }

    fun hasProjectAutomation(projectRoot: String): Boolean =
        AgentPolicy.grantsProjectAutomation(state.grants, projectRoot)

    fun start(objective: String, projectRoot: String): Boolean {
        if (objective.isBlank() || projectRoot.isBlank() || state.activeJob?.status == AgentJobStatus.RUNNING) {
            return false
        }
        if (!hasProjectAutomation(projectRoot)) return false
        val now = System.currentTimeMillis()
        val job = AgentJob(
            id = UUID.randomUUID().toString(),
            objective = objective.trim(),
            projectRoot = projectRoot.trim(),
            conversationId = null,
            status = AgentJobStatus.RUNNING,
            createdAt = now,
            updatedAt = now,
        ).withEvent(AgentEventKind.STATUS, "Starting TAWC bridge job")
        state = state.copy(activeJob = job)
        persist()
        notifyChanged()
        activeHandle = provider.start(
            AgentJobRequest(
                jobId = job.id,
                objective = job.objective,
                projectRoot = job.projectRoot,
                grantedCapabilities = AgentPolicy.grantedIds(state.grants, job.projectRoot),
            ),
            object : ProviderListener {
                override fun onEvent(event: AgentEvent) = updateJob { it.withEvent(event.kind, event.message) }

                override fun onCompleted(conversationId: String?, artifactUri: String?) {
                    updateJob {
                        it.copy(
                            status = AgentJobStatus.COMPLETED,
                            conversationId = conversationId,
                            artifactUri = artifactUri,
                        ).withEvent(AgentEventKind.STATUS, "Job completed")
                    }
                    activeHandle = null
                }

                override fun onFailed(message: String) {
                    updateJob {
                        it.copy(status = AgentJobStatus.FAILED)
                            .withEvent(AgentEventKind.ERROR, message)
                    }
                    activeHandle = null
                }
            },
        )
        return true
    }

    fun cancel(): Boolean {
        val job = state.activeJob ?: return false
        if (job.status != AgentJobStatus.RUNNING) return false
        activeHandle?.cancel()
        updateJob {
            it.copy(status = AgentJobStatus.CANCELLED)
                .withEvent(AgentEventKind.STATUS, "Job cancelled")
        }
        activeHandle = null
        return true
    }

    private fun updateJob(transform: (AgentJob) -> AgentJob) {
        main.post {
            val job = state.activeJob ?: return@post
            val updated = transform(job).copy(updatedAt = System.currentTimeMillis())
            state = if (updated.status == AgentJobStatus.RUNNING) {
                state.copy(activeJob = updated)
            } else {
                state.copy(activeJob = null, history = (state.history + updated).takeLast(MAX_HISTORY))
            }
            persist()
            notifyChanged()
        }
    }

    private fun persist() = runCatching { store.save(state) }

    private fun notifyChanged() {
        val value = snapshot()
        listeners.forEach { listener -> main.post { runCatching { listener(value) } } }
    }

    companion object {
        private const val MAX_HISTORY = 50
    }
}

data class AgentSnapshot(
    val bridgeUrl: String,
    val job: AgentJob?,
    val grants: List<CapabilityGrant>,
    val history: List<AgentJob>,
)

private fun AgentJob.withEvent(kind: AgentEventKind, message: String): AgentJob = copy(
    updatedAt = System.currentTimeMillis(),
    events = (events + AgentEvent(System.currentTimeMillis(), kind, message)).takeLast(200),
)
