@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Kind of entity referenced by the change stream and cross-object links. */
@Serializable(with = EntityKind.Serializer::class)
enum class EntityKind(val wire: String) {
    TASK("task"),
    NOTE("note"),
    CHAT("chat"),
    MESSAGE("message"),
    AGENT("agent"),
    AGENT_RUN("agent_run"),
    PROJECT("project"),
    AREA("area"),
    INBOX_ITEM("inbox_item"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<EntityKind>(UNKNOWN, entries, EntityKind::wire)
}

@Serializable(with = ChangeType.Serializer::class)
enum class ChangeType(val wire: String) {
    UPDATED("updated"),
    DELETED("deleted"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<ChangeType>(UNKNOWN, entries, ChangeType::wire)
}

/**
 * One entry in the ordered change stream (protocol §3/§11). The event does
 * not need to carry the complete canonical object — the client fetches or
 * syncs the referenced entity. Deletions are tombstones (protocol §12).
 */
@Serializable
data class ChangeEnvelope(
    val type: ChangeType,
    @SerialName("entity_type") val entityKind: EntityKind,
    @SerialName("entity_id") val entityId: EntityId,
    val revision: Long,
    @SerialName("deleted_at") val deletedAt: Instant? = null,
    val cursor: SyncCursorValue,
    val timestamp: Instant? = null,
)

/**
 * One page of changes. `nextCursor == null` means the client is caught up.
 * Protocol: GET /v1/changes?since=<cursor>.
 */
@Serializable
data class ChangePage(
    @SerialName("next_cursor") val nextCursor: SyncCursorValue? = null,
    val changes: List<ChangeEnvelope> = emptyList(),
)
