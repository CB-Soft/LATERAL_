package com.lateral.agent

/** Risk classes are enforced by LATERAL_, never delegated to a model or worker. */
enum class CapabilityRisk { READ, ACTION, WRITE, PRIVILEGED, SENSITIVE }

enum class PermissionScope { ONCE, SESSION, PROJECT, SKILL, ALWAYS, DENY }

data class AgentIdentity(
    val id: String,
    val providerId: String,
    val displayName: String,
)

data class Conversation(
    val id: String,
    val agentId: String,
    val projectRoot: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class CapabilityDescriptor(
    val id: String,
    val risk: CapabilityRisk,
    val description: String,
)

data class CapabilityGrant(
    val capability: String,
    val scope: PermissionScope,
    val projectRoot: String? = null,
)

data class PermissionRequest(
    val id: String,
    val capability: String,
    val scope: PermissionScope,
    val projectRoot: String?,
    val rationale: String,
)

enum class AgentJobStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

enum class AgentEventKind { STATUS, OUTPUT, ERROR, ARTIFACT }

data class AgentEvent(
    val at: Long,
    val kind: AgentEventKind,
    val message: String,
)

data class AgentJob(
    val id: String,
    val objective: String,
    val projectRoot: String,
    val conversationId: String?,
    val status: AgentJobStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val events: List<AgentEvent> = emptyList(),
    val artifactUri: String? = null,
)

data class ProviderCapabilities(
    val providerId: String,
    val displayName: String,
    val capabilities: List<CapabilityDescriptor>,
)

data class AgentJobRequest(
    val jobId: String,
    val objective: String,
    val projectRoot: String,
    val grantedCapabilities: List<String>,
)

interface JobHandle {
    fun cancel()
}

interface AgentProvider {
    val id: String
    fun probe(callback: (Result<ProviderCapabilities>) -> Unit)
    fun start(request: AgentJobRequest, listener: ProviderListener): JobHandle
}

interface ProviderListener {
    fun onEvent(event: AgentEvent)
    fun onCompleted(conversationId: String?, artifactUri: String?)
    fun onFailed(message: String)
}
