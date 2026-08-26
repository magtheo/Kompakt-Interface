@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable(with = InboxPriority.Serializer::class)
enum class InboxPriority(val wire: String) {
    LOW("low"),
    NORMAL("normal"),
    HIGH("high"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<InboxPriority>(UNKNOWN, entries, InboxPriority::wire)
}

/**
 * The Inbox is a *view*, not a source of truth: it aggregates attention
 * items from agents, tasks, calendar, and the server. Selecting an item
 * opens its original source object.
 */
@Serializable
data class InboxItem(
    override val id: EntityId,
    @SerialName("source_type")
    val sourceType: EntityKind? = null,
    @SerialName("source_id")
    val sourceId: EntityId? = null,
    val title: String,
    val summary: String? = null,
    val timestamp: Instant,
    val priority: InboxPriority = InboxPriority.NORMAL,
    val actions: List<Action> = emptyList(),
    override val revision: Long = 1,
    @SerialName("updated_at")
    override val updatedAt: Instant = timestamp,
) : SyncEntity
