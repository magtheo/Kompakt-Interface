package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.CapabilitySet
import dev.magnor.kompakt.domain.ChangePage
import dev.magnor.kompakt.domain.ServerStatus
import dev.magnor.kompakt.domain.SyncCursorValue
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Capability negotiation + incremental sync (protocol §8, §11).
 * Cursor sync is the correctness mechanism; push is only latency
 * optimization. One internal update interface, transport swappable.
 */
interface SyncRepository {
    suspend fun capabilities(): CapabilitySet
    suspend fun status(): ServerStatus

    /** One page of changes after the cursor. `since = null` = full feed. */
    suspend fun changesSince(cursor: SyncCursorValue?): ChangePage

    fun observeLastSync(): Flow<Instant?>

    /** Mark a successful sync point (client stores last_sync_cursor). */
    suspend fun markSynced(requestId: RequestId)
}
