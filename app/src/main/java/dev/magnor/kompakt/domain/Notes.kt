@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * A vault note (Phase 12, D028 v2 — file-authoritative). The list wire
 * (`GET /v1/notes`) carries id/title/preview/category/role/updated_at but
 * NOT text or checksum; the detail wire adds both. Fields therefore decode
 * from either shape: `text`/`checksum` are null on list rows.
 *
 * `role` is the pipeline stage: scratchpad | inbox | note (server-computed
 * from folder location — no status metadata on the wire).
 */
@Serializable
data class Note(
    override val id: EntityId,
    val text: String? = null,
    val title: String? = null,
    /** Server-computed list preview (first meaningful body line). */
    val preview: String? = null,
    /** Top-level folder bucket (Inbox, Projects, Areas, Knowledge, …). */
    val category: String? = null,
    /** Pipeline stage: scratchpad | inbox | note. */
    val role: String? = null,
    /** sha256 of the file bytes — optimistic lock for edits (detail only). */
    val checksum: String? = null,
    @SerialName("created_at")
    val createdAt: Instant? = null,
    @SerialName("updated_at")
    override val updatedAt: Instant,
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

    /** Row title: wire title → preview → first text line → placeholder. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: listPreview.takeIf { it.isNotBlank() }
            ?: "Untitled"

    /** Non-null list preview: wire preview → first line of text → "". */
    val listPreview: String
        get() = preview?.takeIf { it.isNotBlank() }
            ?: text.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

    val isScratchpad: Boolean get() = role == ROLE_SCRATCHPAD
    val isInbox: Boolean get() = role == ROLE_INBOX

    companion object {
        const val ROLE_SCRATCHPAD = "scratchpad"
        const val ROLE_INBOX = "inbox"
        const val ROLE_NOTE = "note"
    }
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
