package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.data.PendingCaptureStore
import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.CaptureResult
import dev.magnor.kompakt.domain.CaptureType
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QueueingCaptureRepository (Phase 6): offline commit parks the proposal
 * with its request id; a successful commit opportunistically drains the
 * queue; interpret passes straight through.
 */
class QueueingCaptureRepositoryTest {

    private class StubInner(
        var behavior: suspend (CaptureProposal, RequestId) -> CaptureResult,
    ) : CaptureRepository {
        override suspend fun interpret(input: String): CaptureProposal =
            throw IllegalStateException("not used in these tests")

        override suspend fun commit(proposal: CaptureProposal, requestId: RequestId): CaptureResult =
            behavior(proposal, requestId)
    }

    private fun proposal(title: String) = CaptureProposal(
        proposedType = CaptureType.NOTE,
        title = title,
    )

    @Test
    fun `offline commit parks in queue and reports queued`() = runTest {
        val queue = PendingCaptureStore(dir = null)
        var flushed = 0
        val repo = QueueingCaptureRepository(
            inner = StubInner { _, _ -> throw OfflineException(RuntimeException("no route")) },
            queue = queue,
            flush = { flushed++ },
        )
        val result = repo.commit(proposal("offline note"), "req-off")
        assertTrue(result is CaptureResult.QueuedOffline)
        assertEquals(1, queue.all().size)
        assertEquals("req-off", queue.all().single().requestId)
        assertEquals("offline note", queue.all().single().proposal.title)
        assertEquals(0, flushed) // no opportunistic flush when we just went offline
    }

    @Test
    fun `successful commit passes through and triggers flush`() = runTest {
        val queue = PendingCaptureStore(dir = null)
        var flushed = 0
        val note = Note(id = "n1", text = "made it", createdAt = Instant.fromEpochSeconds(1))
        val repo = QueueingCaptureRepository(
            inner = StubInner { _, _ -> CaptureResult.NoteCreated(note) },
            queue = queue,
            flush = { flushed++ },
        )
        val result = repo.commit(proposal("online note"), "req-on")
        assertTrue(result is CaptureResult.NoteCreated)
        assertTrue(queue.all().isEmpty())
        assertEquals(1, flushed)
    }

    @Test
    fun `non-transport failures are not queued`() = runTest {
        val queue = PendingCaptureStore(dir = null)
        val repo = QueueingCaptureRepository(
            inner = StubInner { _, _ -> throw dev.magnor.kompakt.domain.CaptureRejectedException("bad") },
            queue = queue,
            flush = { },
        )
        var thrown = false
        try {
            repo.commit(proposal("rejected"), "req-bad")
        } catch (e: dev.magnor.kompakt.domain.CaptureRejectedException) {
            thrown = true
        }
        assertTrue(thrown)
        assertTrue(queue.all().isEmpty())
    }
}
