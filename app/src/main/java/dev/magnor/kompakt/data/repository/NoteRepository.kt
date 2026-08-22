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
}
