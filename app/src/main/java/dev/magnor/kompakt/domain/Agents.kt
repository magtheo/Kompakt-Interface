@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Phase 8 agent surface, aligned to the coordinator's /v1 agents contract
 * (V-052). Agents are LIVE VIEWS over swappable backends (D025), not sync
 * entities: a role is identified by (name, backend) — backends own the ids.
 */

/** What an agent backend actually honors (coordinator BackendCapabilities). */
@Serializable
data class AgentBackendInfo(
    /** Not on the wire — /v1/agents keys backends by name (live-verified
     *  T-011: values carry only the six capability booleans). Kept for
     *  fake parity; defaults empty. */
    val name: String = "",
    val sandboxed: Boolean = false,
    val resumable: Boolean = false,
    @SerialName("live_steering") val liveSteering: Boolean = false,
    val commands: Boolean = false,
    @SerialName("event_stream") val eventStream: Boolean = false,
    @SerialName("project_registration") val projectRegistration: Boolean = false,
)

/**
 * A worker role offered by a backend (Warren `pi`, OpenCode `build`…).
 * Roles have no server-side id — the pair (name, backend) is the key.
 */
@Serializable
data class AgentRole(
    val name: String,
    val description: String = "",
    /** spawn_only | live | none (role-level steering vocabulary). */
    val steering: String = "none",
    val backend: String,
)

/** GET /v1/agents summary — the whole surface in one read. */
@Serializable
data class AgentsSurface(
    val backends: Map<String, AgentBackendInfo> = emptyMap(),
    val agents: List<AgentRole> = emptyList(),
    @SerialName("default_backend") val defaultBackend: String? = null,
) {
    fun backendOf(role: AgentRole): AgentBackendInfo? = backends[role.backend]
}

/** Run vs. session execution kinds (port ExecutionKind). */
@Serializable(with = AgentRunKind.Serializer::class)
enum class AgentRunKind(val wire: String) {
    RUN("run"),
    SESSION("session"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<AgentRunKind>(UNKNOWN, entries, AgentRunKind::wire)
}

/** Execution state vocabulary (port ExecutionState, wire-safe). */
@Serializable(with = AgentRunState.Serializer::class)
enum class AgentRunState(val wire: String) {
    QUEUED("queued"),
    RUNNING("running"),
    WAITING_FOR_INPUT("waiting_for_input"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    IDLE("idle"),
    UNKNOWN("unknown");

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED

    object Serializer : SafeEnumSerializer<AgentRunState>(UNKNOWN, entries, AgentRunState::wire)
}

/**
 * One execution: a Warren run (atomic, sandboxed) or an OpenCode session
 * (resumable, trusted lane). `id` is the BACKEND-native id (`run_…`/`ses_…`).
 */
@Serializable
data class AgentRun(
    val id: String,
    val backend: String,
    val kind: AgentRunKind = AgentRunKind.UNKNOWN,
    val agent: String = "",
    val state: AgentRunState = AgentRunState.UNKNOWN,
    @SerialName("project_ref") val projectRef: String? = null,
    val title: String? = null,
    val prompt: String? = null,
    @SerialName("result_summary") val resultSummary: String? = null,
    @SerialName("tokens_in") val tokensIn: Long? = null,
    @SerialName("tokens_out") val tokensOut: Long? = null,
    @SerialName("created_at") val createdAt: Instant? = null,
    @SerialName("updated_at") val updatedAt: Instant? = null,
) {
    /** Display title: explicit title, else the prompt, else the id. */
    val displayTitle: String get() = title ?: prompt?.takeIf { it.isNotBlank() } ?: id
}

/** Honest steering outcome (port SteerOutcome — never silently pretend). */
@Serializable(with = SteerOutcome.Serializer::class)
enum class SteerOutcome(val wire: String) {
    DELIVERED("delivered"),
    QUEUED("queued"),
    UNSUPPORTED("unsupported"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<SteerOutcome>(UNKNOWN, entries, SteerOutcome::wire)
}

/** Evidence event from a run's durable stream (payload kept as JSON text). */
@Serializable
data class AgentEvent(
    val seq: Long,
    val kind: String,
    val role: String? = null,
    val text: String? = null,
)

/** Full result payload (port AgentResult — commit/salvage evidence). */
@Serializable
data class AgentRunResult(
    val outcome: AgentRunState = AgentRunState.UNKNOWN,
    val summary: String? = null,
    val branch: String? = null,
    @SerialName("commit_refs") val commitRefs: List<String> = emptyList(),
    @SerialName("salvage_ref") val salvageRef: String? = null,
    @SerialName("tokens_in") val tokensIn: Long? = null,
    @SerialName("tokens_out") val tokensOut: Long? = null,
)

/** A backend slash-command (OpenCode command surface). */
@Serializable
data class AgentCommand(
    val name: String,
    val description: String = "",
    val template: String? = null,
)

/** New-execution request. Backend optional → default_backend; Warren needs a projectRef. */
data class AgentDispatchDraft(
    val prompt: String,
    val backend: String? = null,
    val agent: String? = null,
    val projectRef: String? = null,
)
