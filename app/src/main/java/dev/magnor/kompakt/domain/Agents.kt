@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * A persistent worker/role: Job Search Agent, PR Reviewer, …
 * Status vocabulary follows the Agents surface UI (running / idle /
 * waiting for input / finished).
 */
@Serializable(with = AgentStatus.Serializer::class)
enum class AgentStatus(val wire: String) {
    RUNNING("running"),
    IDLE("idle"),
    WAITING_FOR_INPUT("waiting_for_input"),
    FINISHED("finished"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<AgentStatus>(UNKNOWN, entries, AgentStatus::wire)
}

@Serializable
data class Agent(
    override val id: EntityId,
    val name: String,
    val status: AgentStatus = AgentStatus.IDLE,
    val description: String = "",
    val capabilities: List<String> = emptyList(),
    val lastActivity: Instant? = null,
    override val revision: Long = 1,
    override val updatedAt: Instant,
) : SyncEntity

/**
 * One concrete execution of an agent. Status blueprint follows the
 * Executor's JobSubmit/JobStatus schema pattern (queued → running →
 * terminal), plus WAITING_FOR_INPUT for approval gates.
 */
@Serializable(with = AgentRunStatus.Serializer::class)
enum class AgentRunStatus(val wire: String) {
    QUEUED("queued"),
    RUNNING("running"),
    WAITING_FOR_INPUT("waiting_for_input"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    STOPPED("stopped"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<AgentRunStatus>(UNKNOWN, entries, AgentRunStatus::wire)
}

@Serializable
data class AgentRun(
    override val id: EntityId,
    val agentId: EntityId,
    val title: String,
    val objective: String,
    val status: AgentRunStatus = AgentRunStatus.QUEUED,
    val startedAt: Instant,
    override val updatedAt: Instant = startedAt,
    override val revision: Long = 1,
    val resultSummary: String? = null,
    val requiresInput: Boolean = false,
    val projectId: EntityId? = null,
    val archived: Boolean = false,
) : SyncEntity
