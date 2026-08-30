package com.lateral.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Provider adapter for the versioned local TAWC/OpenCode bridge. */
class TawcBridgeProvider(private val baseUrl: () -> String) : AgentProvider {
    override val id: String = "tawc-opencode"

    private val executor = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "lateral-tawc-bridge").apply { isDaemon = true }
    }

    override fun probe(callback: (Result<ProviderCapabilities>) -> Unit) {
        executor.execute {
            callback(runCatching {
                val health = sendRequest("GET", "/v1/health")
                if (!health.optBoolean("ok", true)) error(health.optString("error", "Bridge is unhealthy"))
                val capabilities = sendRequest("GET", "/v1/capabilities")
                ProviderCapabilities(
                    providerId = capabilities.optString("providerId", id),
                    displayName = capabilities.optString("displayName", "TAWC / OpenCode"),
                    capabilities = parseCapabilities(capabilities.optJSONArray("capabilities")),
                )
            })
        }
    }

    override fun start(jobRequest: AgentJobRequest, listener: ProviderListener): JobHandle {
        val cancelled = AtomicBoolean(false)
        var poll: ScheduledFuture<*>? = null
        val handle = object : JobHandle {
            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    executor.execute { runCatching { sendRequest("POST", "/v1/jobs/${jobRequest.jobId}/cancel", JSONObject()) } }
                }
                poll?.cancel(false)
            }
        }

        executor.execute {
            runCatching {
                    sendRequest("POST", "/v1/jobs", JSONObject().apply {
                    put("jobId", jobRequest.jobId)
                    put("objective", jobRequest.objective)
                    put("projectRoot", jobRequest.projectRoot)
                    put("grantedCapabilities", JSONArray(jobRequest.grantedCapabilities))
                })
            }.onFailure { error ->
                if (!cancelled.get()) listener.onFailed(error.message ?: "Could not start TAWC bridge job")
            }.onSuccess {
                listener.onEvent(AgentEvent(System.currentTimeMillis(), AgentEventKind.STATUS, "Bridge accepted job ${jobRequest.jobId.take(8)}"))
                var after = 0
                fun pollEvents() {
                    if (cancelled.get()) return
                    runCatching {
                        sendRequest("GET", "/v1/jobs/${jobRequest.jobId}/events?after=$after")
                    }.onFailure { error ->
                        if (!cancelled.get()) listener.onFailed(error.message ?: "Bridge event stream failed")
                        cancelled.set(true)
                    }.onSuccess { response ->
                        val events = response.optJSONArray("events") ?: JSONArray()
                        for (index in 0 until events.length()) {
                            val item = events.optJSONObject(index) ?: continue
                            after = maxOf(after, item.optInt("sequence", after + 1))
                            val kind = runCatching {
                                AgentEventKind.valueOf(item.optString("kind", "OUTPUT").uppercase())
                            }.getOrDefault(AgentEventKind.OUTPUT)
                            val message = item.optString("message", "")
                            if (message.isNotBlank()) listener.onEvent(
                                AgentEvent(item.optLong("at", System.currentTimeMillis()), kind, message),
                            )
                        }
                        val status = response.optString("status", "RUNNING").uppercase()
                        if (status == "COMPLETED") {
                            listener.onCompleted(
                                response.optString("conversationId").takeIf(String::isNotBlank),
                                response.optString("artifactUri").takeIf(String::isNotBlank),
                            )
                            cancelled.set(true)
                        } else if (status == "FAILED") {
                            listener.onFailed(response.optString("error", "Bridge job failed"))
                            cancelled.set(true)
                        } else if (!cancelled.get()) {
                            poll = executor.schedule(::pollEvents, POLL_MS, TimeUnit.MILLISECONDS)
                        }
                    }
                }
                pollEvents()
            }
        }
        return handle
    }

    private fun sendRequest(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl().trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Bridge HTTP $code: ${text.take(240)}")
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCapabilities(array: JSONArray?): List<CapabilityDescriptor> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val risk = runCatching {
                CapabilityRisk.valueOf(item.optString("risk", "READ").uppercase())
            }.getOrDefault(CapabilityRisk.READ)
            add(CapabilityDescriptor(item.optString("id"), risk, item.optString("description")))
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val READ_TIMEOUT_MS = 4_000
        private const val POLL_MS = 750L
    }
}
