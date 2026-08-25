package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.ChatTopic
import kotlinx.coroutines.flow.Flow

/**
 * T-022d: read-only topic registry (GET /v1/chat/topics). Topics ARE the
 * notes sorter's bucket registry — one source of truth. Feeds the chat
 * scope picker and labels the propose chip. Cold one-shot flows like the
 * other remote reference reads.
 */
interface TopicRepository {
    fun observeTopics(): Flow<List<ChatTopic>>
}
