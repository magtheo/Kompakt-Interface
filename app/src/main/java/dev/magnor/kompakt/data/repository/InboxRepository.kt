package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.flow.Flow

interface InboxRepository {
    fun observeInbox(): Flow<List<InboxItem>>

    /** Dismiss an attention item. Dismissal is idempotent per request. */
    suspend fun dismiss(id: EntityId, expectedRevision: Long, requestId: RequestId)
}
