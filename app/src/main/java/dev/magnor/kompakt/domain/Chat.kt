@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
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
)
