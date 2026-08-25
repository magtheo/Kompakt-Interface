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

/**
 * T-022b: one `## ` section of a scratchpad/inbox doc (D028 v2 pipeline
 * stage 3 — explicit triage). Scratchpad sections are `## {ts} — {title}`;
 * [title] is the post-em-dash text when present, else the whole heading.
 * The preamble before the first `## ` is not a section.
 */
data class NoteSection(
    /** Full heading line without the `## ` marker. */
    val heading: String,
    /** Cleaned title: text after ` — ` when present, else the heading. */
    val title: String,
    /** Body lines after the heading, verbatim (may be empty). */
    val body: String,
    /** Index of the heading line in the doc (0-based, for stable identity). */
    val lineIndex: Int,
)

object NoteSections {

    /** Split a doc into `## ` sections; preamble lines are skipped. */
    fun parse(text: String): List<NoteSection> {
        val lines = text.lines()
        val sections = mutableListOf<NoteSection>()
        var i = 0
        while (i < lines.size) {
            val heading = lines[i].removePrefix("## ").trimEnd()
            if (lines[i].startsWith("## ") && heading.isNotBlank()) {
                val body = buildList {
                    var j = i + 1
                    while (j < lines.size && !lines[j].startsWith("## ")) {
                        add(lines[j])
                        j++
                    }
                }
                val title = heading.substringAfter(" — ", heading).trim()
                sections += NoteSection(
                    heading = heading,
                    title = title.ifBlank { heading },
                    body = body.joinToString("\n").trim(),
                    lineIndex = i,
                )
                i += 1 + body.size
            } else {
                i++
            }
        }
        return sections
    }

    /**
     * The doc with [section] removed: its heading + body lines gone, the
     * blank lines it occupied collapsed so no doubled gaps remain. Identity
     * is [NoteSection.lineIndex] against a re-parse of the SAME text.
     */
    fun remove(text: String, section: NoteSection): String {
        val lines = text.lines().toMutableList()
        val i = section.lineIndex
        // Heading + body: everything until the next `## ` (or EOF).
        var end = i
        while (end < lines.size && (end == i || !lines[end].startsWith("## "))) end++
        lines.subList(i, end).clear()
        // Collapse a doubled blank gap the removal may leave behind.
        if (i > 0 && i < lines.size && lines[i].isBlank() && lines[i - 1].isBlank()) {
            lines.removeAt(i)
        }
        return lines.joinToString("\n").trimEnd() + if (text.endsWith("\n")) "\n" else ""
    }
}

