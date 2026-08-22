package dev.magnor.kompakt.data.fake

import dev.magnor.kompakt.domain.ChangeEnvelope
import dev.magnor.kompakt.domain.ChangePage
import dev.magnor.kompakt.domain.ChangeType
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.RevisionConflictException
import dev.magnor.kompakt.domain.SyncCursorValue
import dev.magnor.kompakt.domain.SyncEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory change stream. Every mutation through a [FakeStore] appends a
 * [ChangeEnvelope] here; [FakeSyncRepository] serves pages from it. This
 * exercises the cursor protocol (docs/protocol-and-sync.md §11/§12)
 * end-to-end in-process before the server contract lands in Phase 3.
 *
 * Seeding the baseline records NO changes — the initial fetch brings the
 * baseline; the change stream carries only post-sync mutations.
 */
class FakeChangeLog(private val now: () -> Instant) {

    private val log = AtomicLong(0)
    private val entries = java.util.concurrent.CopyOnWriteArrayList<ChangeEnvelope>()

    val all: List<ChangeEnvelope> get() = entries.toList()

    fun record(
        type: ChangeType,
        kind: EntityKind,
        id: EntityId,
        revision: Long,
        timestamp: Instant = now(),
    ) {
        val n = log.incrementAndGet()
        entries += ChangeEnvelope(
            type = type,
            entityKind = kind,
            entityId = id,
            revision = revision,
            deletedAt = if (type == ChangeType.DELETED) timestamp else null,
            cursor = formatCursor(n),
            timestamp = timestamp,
        )
    }

    /** Page of changes strictly after `since` (null → full feed). */
    fun page(since: SyncCursorValue?): ChangePage {
        val sinceN = since?.let(::parseCursor) ?: 0L
        val changes = entries.filter { parseCursor(it.cursor) > sinceN }
        return ChangePage(nextCursor = changes.lastOrNull()?.cursor, changes = changes)
    }

    companion object {
        fun formatCursor(n: Long): String = "chg_%06d".format(n)
        fun parseCursor(cursor: String): Long =
            cursor.removePrefix("chg_").toLongOrNull() ?: 0L
    }
}

/**
 * In-memory entity store behind every fake repository. Enforces the sync
 * contract on writes:
 *  - created entities start at revision 1,
 *  - mutations carry `expectedRevision` and throw [RevisionConflictException]
 *    when stale (409 analog, protocol §13),
 *  - the transform must advance the revision exactly by one,
 *  - deletes emit tombstones (protocol §12).
 */
class FakeStore<T : SyncEntity>(
    private val kind: EntityKind,
    private val changeLog: FakeChangeLog,
    private val now: () -> Instant,
) {

    private val state = MutableStateFlow<Map<EntityId, T>>(emptyMap())

    val snapshot: Map<EntityId, T> get() = state.value

    fun observeAll(order: Comparator<T>): Flow<List<T>> =
        state.map { m -> m.values.sortedWith(order) }.distinctUntilChanged()

    fun observe(id: EntityId): Flow<T?> =
        state.map { m -> m[id] }.distinctUntilChanged()

    suspend fun get(id: EntityId): T? = state.value[id]

    suspend fun create(value: T): T {
        require(value.revision == 1L) { "created entities start at revision 1" }
        state.value = state.value + (value.id to value)
        changeLog.record(ChangeType.UPDATED, kind, value.id, value.revision)
        return value
    }

    suspend fun mutate(id: EntityId, expectedRevision: Long, transform: (T) -> T): T {
        val current = state.value[id]
            ?: throw NoSuchElementException("$kind '$id' not found")
        if (current.revision != expectedRevision) {
            throw RevisionConflictException(current.revision)
        }
        val next = transform(current)
        check(next.revision == expectedRevision + 1L) {
            "fake transform must advance revision to ${expectedRevision + 1}"
        }
        state.value = state.value + (id to next)
        changeLog.record(ChangeType.UPDATED, kind, id, next.revision)
        return next
    }

    suspend fun delete(id: EntityId) {
        val current = state.value[id]
            ?: throw NoSuchElementException("$kind '$id' not found")
        state.value = state.value - id
        changeLog.record(ChangeType.DELETED, kind, id, current.revision + 1)
    }

    /** Baseline seed — visible to readers, absent from the change stream. */
    fun seed(values: List<T>) {
        state.value = state.value + values.associateBy { it.id }
    }
}

/**
 * request_id registry (protocol §15): replaying a mutation with the same
 * key returns the original outcome instead of creating a second object.
 */
class IdempotencyRegistry {

    private val remembered = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> once(requestId: String, op: suspend () -> T): T {
        remembered[requestId]?.let { return it as T }
        return op().also { remembered[requestId] = it }
    }
}
