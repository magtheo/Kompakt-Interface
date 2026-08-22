package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.Project
import kotlinx.coroutines.flow.Flow

/**
 * Vault PARA structure (projects/areas) as projected by the coordinator.
 * The app never creates its own hierarchy (D004); v0.1 is read-only here.
 */
interface OrganizationRepository {
    fun observeProjects(): Flow<List<Project>>
    fun observeProject(id: EntityId): Flow<Project?>
    fun observeAreas(): Flow<List<Area>>
    fun observeArea(id: EntityId): Flow<Area?>
}
