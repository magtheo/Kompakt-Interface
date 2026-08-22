package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.CaptureResult
import dev.magnor.kompakt.domain.RequestId

/**
 * Universal capture pipeline (Phase 6): server interprets, user confirms
 * or changes the proposed type, then commit. Structure is proposed — never
 * silently applied (explicit transitions only).
 */
interface CaptureRepository {
    /** Server-side interpretation of raw text (offline: returns a raw-note proposal). */
    suspend fun interpret(input: String): CaptureProposal

    /** Commit the (possibly user-changed) proposal. Idempotent per requestId. */
    suspend fun commit(proposal: CaptureProposal, requestId: RequestId): CaptureResult
}
