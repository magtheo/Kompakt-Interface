package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.ChatExchange
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
     * Submit a user message. Returns the acknowledged exchange (user message
     * SENT + assistant reply, server-generated, protocol §17); the reply can
     * take tens of seconds — callers must tolerate the latency.
     */
    suspend fun sendMessage(chatId: EntityId, text: String, requestId: RequestId): ChatExchange

    /**
     * Destructive history cut (V-054): delete every message strictly after
     * [keepThrough]; null empties the thread. No branch is kept. The single
     * primitive behind revert / edit / regenerate — the client composes
     * truncate + send.
     */
    suspend fun truncate(chatId: EntityId, keepThrough: EntityId?, requestId: RequestId)

    /**
     * T-022d: apply or clear the thread's scope (topic or workspace).
     * Nulls clear → general chat. The ONLY re-scoping path — propose-chip
     * Apply and picker changes both land here. Explicit user action;
     * the server never auto-applies a proposal.
     */
    suspend fun setScope(chatId: EntityId, scopeType: String?, scopeRef: String?, requestId: RequestId): ChatThread
}
