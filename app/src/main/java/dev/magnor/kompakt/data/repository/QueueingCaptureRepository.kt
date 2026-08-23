package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.data.PendingCaptureStore
import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.CaptureResult
import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.RequestId

/**
 * Offline-queueing decorator around the active capture repository
 * (Phase 6, dev plan §8).
 *
 * - interpret is NOT queued: proposal quality is the server's job, and a
 *   capture made offline simply shows the retry error.
 * - commit IS queued: on transport failure the proposal is parked with its
 *   request id and re-sent verbatim when connectivity returns — the server
 *   replays idempotently, so double-flush can never duplicate objects.
 * - a successful commit proves connectivity → opportunistically flush any
 *   older queued captures in the background.
 */
class QueueingCaptureRepository(
    private val inner: CaptureRepository,
    private val queue: PendingCaptureStore,
    private val flush: suspend () -> Unit,
) : CaptureRepository by inner {

    override suspend fun commit(proposal: CaptureProposal, requestId: RequestId): CaptureResult =
        try {
            val result = inner.commit(proposal, requestId)
            // We are clearly online — drain anything parked earlier.
            runCatching { flush() }
            result
        } catch (e: OfflineException) {
            queue.enqueue(proposal, requestId)
            CaptureResult.QueuedOffline
        }
}
