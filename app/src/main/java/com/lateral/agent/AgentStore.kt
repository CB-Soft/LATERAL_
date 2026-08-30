package com.lateral.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small inspectable JSON store for the MVP; indexed episodic memory is deferred. */
class AgentStore(context: Context) {
    private val file = File(context.filesDir, "agent_state.json")

    @Synchronized
    fun load(): StoredAgentState {
        if (!file.exists()) return StoredAgentState()
        return runCatching { decode(JSONObject(file.readText())) }.getOrDefault(StoredAgentState())
    }

    @Synchronized
    fun save(state: StoredAgentState) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(encode(state).toString())
        if (!temporary.renameTo(file)) {
            temporary.delete()
            error("Could not replace agent state")
        }
    }

    private fun encode(state: StoredAgentState): JSONObject = JSONObject().apply {
        put("bridgeUrl", state.bridgeUrl)
        put("grants", JSONArray().apply {
            state.grants.forEach { grant ->
                put(JSONObject().apply {
                    put("capability", grant.capability)
                    put("scope", grant.scope.name)
                    grant.projectRoot?.let { put("projectRoot", it) }
                })
            }
        })
        state.activeJob?.let { job -> put("activeJob", encode(job)) }
        put("history", JSONArray().apply {
            state.history.takeLast(MAX_HISTORY).forEach { job -> put(encode(job)) }
        })
    }

    private fun encode(job: AgentJob): JSONObject = JSONObject().apply {
        put("id", job.id)
        put("objective", job.objective)
        put("projectRoot", job.projectRoot)
        putOpt("conversationId", job.conversationId)
        put("status", job.status.name)
        put("createdAt", job.createdAt)
        put("updatedAt", job.updatedAt)
        putOpt("artifactUri", job.artifactUri)
        put("events", JSONArray().apply {
            job.events.forEach { event ->
                put(JSONObject().apply {
                    put("at", event.at)
                    put("kind", event.kind.name)
                    put("message", event.message)
                })
            }
        })
    }

    private fun decode(json: JSONObject): StoredAgentState {
        val grants = mutableListOf<CapabilityGrant>()
        val grantArray = json.optJSONArray("grants") ?: JSONArray()
        for (index in 0 until grantArray.length()) {
            val item = grantArray.optJSONObject(index) ?: continue
            grants += CapabilityGrant(
                capability = item.optString("capability"),
                scope = item.optEnum(PermissionScope::class.java, "scope", PermissionScope.DENY),
                projectRoot = item.optString("projectRoot").takeIf(String::isNotBlank),
            )
        }
        val legacyJob = json.optJSONObject("job")?.let(::decodeJob)
        val decodedActiveJob = json.optJSONObject("activeJob")?.let(::decodeJob)
        val activeJob = (decodedActiveJob ?: legacyJob)?.takeIf { it.status == AgentJobStatus.RUNNING }
        val history = decodeJobs(json.optJSONArray("history")).toMutableList().apply {
            val legacyCompletedJob = legacyJob?.takeIf { it.status != AgentJobStatus.RUNNING }
            if (legacyCompletedJob != null && none { it.id == legacyCompletedJob.id }) {
                add(legacyCompletedJob)
            }
            val completedActiveJob = decodedActiveJob?.takeIf { it.status != AgentJobStatus.RUNNING }
            if (completedActiveJob != null && none { it.id == completedActiveJob.id }) {
                add(completedActiveJob)
            }
        }
        return StoredAgentState(
            bridgeUrl = json.optString("bridgeUrl", DEFAULT_BRIDGE_URL),
            grants = grants,
            activeJob = activeJob,
            history = history.takeLast(MAX_HISTORY),
        )
    }

    private fun decodeJobs(array: JSONArray?): List<AgentJob> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { add(decodeJob(it)) }
        }
    }

    private fun decodeJob(json: JSONObject): AgentJob = AgentJob(
        id = json.optString("id"),
        objective = json.optString("objective"),
        projectRoot = json.optString("projectRoot"),
        conversationId = json.optString("conversationId").takeIf(String::isNotBlank),
        status = json.optEnum(AgentJobStatus::class.java, "status", AgentJobStatus.FAILED),
        createdAt = json.optLong("createdAt"),
        updatedAt = json.optLong("updatedAt"),
        artifactUri = json.optString("artifactUri").takeIf(String::isNotBlank),
        events = decodeEvents(json.optJSONArray("events")),
    )

    private fun decodeEvents(array: JSONArray?): List<AgentEvent> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                AgentEvent(
                    at = item.optLong("at"),
                    kind = item.optEnum(AgentEventKind::class.java, "kind", AgentEventKind.OUTPUT),
                    message = item.optString("message"),
                ),
            )
        }
    }.takeLast(MAX_EVENTS)

    companion object {
        const val DEFAULT_BRIDGE_URL = "http://127.0.0.1:8765"
        private const val MAX_EVENTS = 200
        private const val MAX_HISTORY = 50
    }
}

data class StoredAgentState(
    val bridgeUrl: String = AgentStore.DEFAULT_BRIDGE_URL,
    val grants: List<CapabilityGrant> = emptyList(),
    val activeJob: AgentJob? = null,
    val history: List<AgentJob> = emptyList(),
)

private inline fun <reified T : Enum<T>> JSONObject.optEnum(
    type: Class<T>,
    key: String,
    fallback: T,
): T = runCatching { enumValueOf<T>(optString(key)) }.getOrDefault(fallback)
