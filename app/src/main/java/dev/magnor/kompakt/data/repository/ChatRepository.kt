package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeThreads(): Flow<List<ChatThread>>
    fun observeThread(id: EntityId): Flow<ChatThread?>
    fun observeMessages(chatId: EntityId): Flow<List<Message>>

    suspend fun getThread(id: EntityId): ChatThread?

    suspend fun createThread(draft: ChatThreadDraft, requestId: RequestId): ChatThread

    /**
     * Submit a user message. Returns the acknowledged message (status SENT);
     * while offline the message is queued PENDING instead (protocol §17).
     */
    suspend fun sendMessage(chatId: EntityId, text: String, requestId: RequestId): Message
}
