@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * A chat is a conversation context — distinct from an agent, which is an
 * execution entity (D003). Chats are server-side objects; the phone renders.
 */
@Serializable
data class ChatThread(
    override val id: EntityId,
    val title: String,
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("updated_at")
    override val updatedAt: Instant,
    override val revision: Long = 1,
    @SerialName("project_id")
    val projectId: EntityId? = null,
    @SerialName("is_temporary")
    val isTemporary: Boolean = false,
    @SerialName("last_message_preview")
    val lastMessagePreview: String? = null,
    // T-022d: conversation scope — topic (vault bucket seed) or workspace
    // (git checkout, OpenCode session). Null = general chat. Defaults keep
    // old-server payloads decoding (explicitNulls=false → absent = null).
    @SerialName("scope_type")
    val scopeType: String? = null,
    @SerialName("scope_ref")
    val scopeRef: String? = null,
    /** Server-computed display label for the current scope (e.g. "Evershift"). */
    @SerialName("scope_label")
    val scopeLabel: String? = null,
    /** Workspace tier: a turn is still running; messages GET triggers catch-up. */
    @SerialName("pending_reply")
    val pendingReply: Boolean = false,
) : SyncEntity

/** Closed vocabulary, but still forward-compat: unknown wire → UNKNOWN (§9). */
@Serializable(with = MessageRole.Serializer::class)
enum class MessageRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<MessageRole>(UNKNOWN, entries, MessageRole::wire)
}

/**
 * Message delivery state. PENDING = queued for the server (offline queue,
 * protocol §17); SENT = acknowledged; FAILED = permanently rejected.
 */
@Serializable(with = MessageStatus.Serializer::class)
enum class MessageStatus(val wire: String) {
    PENDING("pending"),
    SENT("sent"),
    FAILED("failed"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<MessageStatus>(UNKNOWN, entries, MessageStatus::wire)
}

@Serializable
data class Message(
    override val id: EntityId,
    @SerialName("chat_id")
    val chatId: EntityId,
    val role: MessageRole,
    /** Safe structured/plain content only — never arbitrary HTML. */
    val content: String,
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("updated_at")
    override val updatedAt: Instant = createdAt,
    override val revision: Long = 1,
    val status: MessageStatus = MessageStatus.SENT,
) : SyncEntity

@Serializable
data class ChatThreadDraft(
    val title: String,
    @SerialName("project_id")
    val projectId: EntityId? = null,
    @SerialName("is_temporary")
    val isTemporary: Boolean = false,
    // T-022d: optional scope at creation — the picker's selection.
    @SerialName("scope_type")
    val scopeType: String? = null,
    @SerialName("scope_ref")
    val scopeRef: String? = null,
)

/**
 * T-022d: a chat topic — the notes sorter's bucket registry, one source of
 * truth (anti-bloat). Reference data like [Workspace]: no id namespace, no
 * revisions, no sync. Labels the topic picker and the propose chip.
 */
@Serializable
data class ChatTopic(
    val id: String,
    val label: String,
)

/**
 * Result of one send: the acknowledged user message plus the generated
 * assistant reply (null when the backend produced none — LLM failure
 * degrades server-side to an honest note, so null only on odd wire).
 *
 * T-022d: an unscoped thread may carry [proposedTopic] — a suggestion,
 * NEVER auto-applied; Apply is an explicit POST /scope. A workspace-scoped
 * thread carries [workspaceState] (settled|pending|busy|error|unavailable)
 * for inline feedback; pending turns surface via thread.pendingReply.
 */
data class ChatExchange(
    val user: Message,
    val assistant: Message?,
    val proposedTopic: ChatTopic? = null,
    val workspaceState: String? = null,
)
