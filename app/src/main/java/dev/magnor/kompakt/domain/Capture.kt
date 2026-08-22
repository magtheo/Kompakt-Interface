@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Object types a capture can become. The user confirms the type — never guessed. */
@Serializable(with = CaptureType.Serializer::class)
enum class CaptureType(val wire: String) {
    TASK("task"),
    NOTE("note"),
    CHAT("chat"),
    AGENT_REQUEST("agent_request"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<CaptureType>(UNKNOWN, entries, CaptureType::wire)
}

/**
 * Server interpretation of raw capture input (Phase 6 API concept:
 * POST /v1/capture/interpret). The user confirms or changes the proposed
 * type before commit — the app never silently decides (D0xx: explicit
 * transitions).
 */
@Serializable
data class CaptureProposal(
    @SerialName("proposed_type") val proposedType: CaptureType,
    val title: String,
    val text: String? = null,
    @SerialName("due_at") val dueAt: Instant? = null,
    @SerialName("project_id") val projectId: EntityId? = null,
    @SerialName("area_id") val areaId: EntityId? = null,
)

/** Outcome of a committed capture — references the created object. */
sealed interface CaptureResult {
    data class TaskCreated(val task: Task) : CaptureResult
    data class NoteCreated(val note: Note) : CaptureResult
    data class ChatCreated(val thread: ChatThread) : CaptureResult
    data class AgentRequested(val run: AgentRun) : CaptureResult
}
