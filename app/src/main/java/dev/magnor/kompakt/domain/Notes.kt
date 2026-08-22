@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class Note(
    override val id: EntityId,
    val text: String,
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("updated_at")
    override val updatedAt: Instant = createdAt,
    override val revision: Long = 1,
    @SerialName("project_id")
    val projectId: EntityId? = null,
    @SerialName("area_id")
    val areaId: EntityId? = null,
    @SerialName("source_type")
    val sourceType: EntityKind? = null,
    @SerialName("source_id")
    val sourceId: EntityId? = null,
) : SyncEntity {
    /** First line — list preview. */
    val preview: String get() = text.lineSequence().firstOrNull().orEmpty()
}

@Serializable
data class NoteDraft(
    val text: String,
    @SerialName("project_id")
    val projectId: EntityId? = null,
    @SerialName("area_id")
    val areaId: EntityId? = null,
    @SerialName("source_type")
    val sourceType: EntityKind? = null,
    @SerialName("source_id")
    val sourceId: EntityId? = null,
)
