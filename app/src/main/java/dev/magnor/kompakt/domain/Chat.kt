@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
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
    val createdAt: Instant,
    override val updatedAt: Instant,
    override val revision: Long = 1,
    val projectId: EntityId? = null,
    val isTemporary: Boolean = false,
    val lastMessagePreview: String? = null,
) : SyncEntity

/** Closed vocabulary — plain enum serialization is sufficient. */
@Serializable
enum class MessageRole { USER, ASSISTANT, SYSTEM }

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
    val chatId: EntityId,
    val role: MessageRole,
    /** Safe structured/plain content only — never arbitrary HTML. */
    val content: String,
    val createdAt: Instant,
    override val updatedAt: Instant = createdAt,
    override val revision: Long = 1,
    val status: MessageStatus = MessageStatus.SENT,
) : SyncEntity

@Serializable
data class ChatThreadDraft(
    val title: String,
    val projectId: EntityId? = null,
    val isTemporary: Boolean = false,
)
