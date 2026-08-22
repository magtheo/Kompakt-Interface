package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskDraft
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.domain.TaskPatch
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

interface TaskRepository {
    fun observeTasks(filter: TaskFilter = TaskFilter.All): Flow<List<Task>>
    fun observeTask(id: EntityId): Flow<Task?>

    suspend fun getTask(id: EntityId): Task?

    suspend fun createTask(draft: TaskDraft, requestId: RequestId): Task

    /** Mutations carry expected_revision; a moved-on server revision throws [dev.magnor.kompakt.domain.RevisionConflictException] (409 analog). */
    suspend fun completeTask(id: EntityId, expectedRevision: Long, requestId: RequestId): Task

    suspend fun postponeTask(
        id: EntityId,
        expectedRevision: Long,
        newDueAt: Instant?,
        requestId: RequestId,
    ): Task

    suspend fun updateTask(
        id: EntityId,
        expectedRevision: Long,
        patch: TaskPatch,
        requestId: RequestId,
    ): Task
}
