package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.ActionType
import dev.magnor.kompakt.domain.Agent
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.flow.Flow

interface AgentRepository {
    fun observeAgents(): Flow<List<Agent>>
    fun observeAgent(id: EntityId): Flow<Agent?>
    fun observeRuns(agentId: EntityId? = null): Flow<List<AgentRun>>
    fun observeRun(id: EntityId): Flow<AgentRun?>

    suspend fun getAgent(id: EntityId): Agent?
    suspend fun getRun(id: EntityId): AgentRun?

    /** Queue a new execution of a persistent agent. */
    suspend fun requestRun(agentId: EntityId, objective: String, requestId: RequestId): AgentRun

    /**
     * Invoke a known action on a run (approve / reject / stop / archive …).
     * Unknown actions are rejected client-side — actions from a known set only.
     */
    suspend fun actOnRun(runId: EntityId, action: ActionType, requestId: RequestId): AgentRun
}
