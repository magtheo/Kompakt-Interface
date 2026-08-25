package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.Workspace
import kotlinx.coroutines.flow.Flow

/**
 * T-022c: read-only workspace registry (GET /v1/workspaces). Feeds the
 * agent dispatch picker (and later T-022d chat scopes). Cold one-shot
 * flows like the other remote reference reads.
 */
interface WorkspaceRepository {
    fun observeWorkspaces(): Flow<List<Workspace>>
}
