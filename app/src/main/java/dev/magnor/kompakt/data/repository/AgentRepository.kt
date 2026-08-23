package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.AgentCommand
import dev.magnor.kompakt.domain.AgentDispatchDraft
import dev.magnor.kompakt.domain.AgentEvent
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunResult
import dev.magnor.kompakt.domain.AgentsSurface
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.SteerOutcome
import kotlinx.coroutines.flow.Flow

/**
 * Phase 8 agents surface (D025): a process manager over swappable
 * backends. Reads are live views; runs are backend-owned (`run_…`/`ses_…`).
 * Capabilities are surfaced so the UI can adapt (e.g. hide "resume" for
 * non-resumable backends) — never assume support the backend doesn't have.
 */
interface AgentRepository {
    /** Backends + roles + default backend in one read (GET /v1/agents). */
    fun observeSurface(): Flow<AgentsSurface>

    fun observeRuns(): Flow<List<AgentRun>>
    fun observeRun(id: String): Flow<AgentRun?>

    /** Start a new execution. Warren requires a projectRef (mapped server-side). */
    suspend fun dispatch(draft: AgentDispatchDraft, requestId: RequestId): AgentRun

    /** Continue a resumable execution (session backends only). */
    suspend fun send(runId: String, message: String, requestId: RequestId): AgentRun

    /** Mid-execution injection — the backend reports honestly if unsupported. */
    suspend fun steer(runId: String, message: String, requestId: RequestId): SteerOutcome

    suspend fun cancel(runId: String, requestId: RequestId)

    suspend fun result(runId: String): AgentRunResult?

    suspend fun events(runId: String, since: Long = 0): List<AgentEvent>

    /** Slash-commands for a backend (empty for commandless backends). */
    suspend fun commands(backend: String): List<AgentCommand>

    suspend fun runCommand(runId: String, command: String, arguments: String, requestId: RequestId): AgentRun
}
