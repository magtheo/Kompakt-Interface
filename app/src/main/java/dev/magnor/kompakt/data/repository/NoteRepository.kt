package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.flow.Flow

interface NoteRepository {
    fun observeNotes(projectId: EntityId? = null, areaId: EntityId? = null): Flow<List<Note>>
    fun observeNote(id: EntityId): Flow<Note?>

    suspend fun getNote(id: EntityId): Note?

    suspend fun createNote(draft: NoteDraft, requestId: RequestId): Note

    /**
     * Text write-through edit guarded by the file checksum (D028 v2).
     * Stale checksum → [dev.magnor.kompakt.domain.NoteConflictException]
     * carrying the fresh note; callers reload, never merge.
     */
    suspend fun updateNote(id: EntityId, text: String, expectedChecksum: String): Note
}
