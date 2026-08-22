@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class Note(
    override val id: EntityId,
    val text: String,
    val createdAt: Instant,
    override val updatedAt: Instant = createdAt,
    override val revision: Long = 1,
    val projectId: EntityId? = null,
    val areaId: EntityId? = null,
    val sourceType: EntityKind? = null,
    val sourceId: EntityId? = null,
) : SyncEntity {
    /** First line — list preview. */
    val preview: String get() = text.lineSequence().firstOrNull().orEmpty()
}

@Serializable
data class NoteDraft(
    val text: String,
    val projectId: EntityId? = null,
    val areaId: EntityId? = null,
    val sourceType: EntityKind? = null,
    val sourceId: EntityId? = null,
)
