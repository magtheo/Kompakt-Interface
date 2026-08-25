package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.NoteConflictException
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.RepositoryException
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-022a: notes list derivation (scratchpad pinned as Unprocessed, category
 * sections with Inbox first) and the editor's checksum optimistic lock —
 * especially the 409 contract: user text survives, fresh checksum adopted,
 * second Save overwrites, Reload is the only server-text path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelsTest {

    private val t0 = Instant.parse("2026-08-24T09:00:00Z")
    private lateinit var repo: TestNoteRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = TestNoteRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun row(
        id: String,
        role: String? = "note",
        category: String? = "Projects",
        updatedAt: Instant = t0,
    ) = Note(
        id = id,
        title = id.replaceFirstChar { it.uppercase() },
        preview = "preview of $id",
        category = category,
        role = role,
        updatedAt = updatedAt,
    )

    // ---- NotesViewModel: derivation ----

    @Test
    fun `scratchpad pinned, unprocessed counted from detail, inbox section first`() = kotlinx.coroutines.test.runTest {
        repo.rows.value = listOf(
            row("n-projects", category = "Projects"),
            row("n-inbox", category = "Inbox"),
            row("n-areas", category = "Areas"),
            row("scratch", role = "scratchpad", category = "Inbox"),
        )
        repo.detail["scratch"] = Note(
            id = "scratch",
            role = "scratchpad",
            text = "## 2026-08-24 — a\nbody\n## 2026-08-24 — b\n## 2026-08-23 — c",
            checksum = "ck-scratch",
            updatedAt = t0,
        )
        val vm = NotesViewModel(repo, t0)
        val state = vm.state.first { it.loaded }

        assertEquals(3, state.unprocessedCount)
        assertNotNull(state.scratchpad)
        assertEquals(listOf("Inbox", "Areas", "Projects"), state.sections.map { it.label })
        // Scratchpad itself is the Unprocessed header — not repeated below.
        assertTrue(state.sections.all { s -> s.notes.none { it.isScratchpad } })
    }

    @Test
    fun `scratchpad detail failure yields zero but still lists notes`() = kotlinx.coroutines.test.runTest {
        repo.rows.value = listOf(
            row("n-1", category = "Projects"),
            row("scratch", role = "scratchpad"),
        )
        repo.detailFailures += "scratch"
        val vm = NotesViewModel(repo, t0)
        val state = vm.state.first { it.loaded }

        assertEquals(0, state.unprocessedCount)
        assertEquals(1, state.sections.size)
        assertNull(state.error)
    }

    @Test
    fun `blank category falls back to Notes bucket`() = kotlinx.coroutines.test.runTest {
        repo.rows.value = listOf(row("n-x", category = "  "))
        val vm = NotesViewModel(repo, t0)
        val state = vm.state.first { it.loaded }

        assertEquals(listOf("Notes"), state.sections.map { it.label })
    }

    // ---- NoteEditorViewModel: load / dirty / save ----

    private fun editor(id: String = "n-1") = NoteEditorViewModel(
        noteRepository = repo,
        taskRepository = idleTasks,
        organizationRepository = emptyOrg,
        noteId = id,
        newRequestId = { "req-1" },
        now = { t0 },
    )

    private fun seededEditor(): NoteEditorViewModel {
        repo.detail["n-1"] = Note(
            id = "n-1",
            title = "Kodeverket ideas",
            text = "original text",
            checksum = "ck-1",
            updatedAt = t0,
        )
        return editor()
    }

    @Test
    fun `load populates text checksum and lock`() {
        val vm = seededEditor()
        val s = vm.state.value

        assertFalse(s.loading)
        assertFalse(s.notFound)
        assertEquals("original text", s.text)
        assertEquals("ck-1", s.checksum)
        assertEquals("Kodeverket ideas", s.title)
        assertFalse(s.canSave) // clean
    }

    @Test
    fun `typing marks dirty and save adopts new checksum`() {
        val vm = seededEditor()
        vm.onTextChange("edited text")

        assertTrue(vm.state.value.canSave)
        vm.save()
        val s = vm.state.value

        assertFalse(s.saving)
        assertFalse(s.dirty)
        assertFalse(s.conflict)
        assertEquals(t0, s.savedAt)
        assertEquals("ck-2", s.checksum) // fake minted a new checksum
        assertEquals("ck-1", repo.lastExpectedChecksum) // locked on the loaded one
    }

    @Test
    fun `clean note cannot be saved`() {
        val vm = seededEditor()
        vm.save()

        assertNull(repo.lastExpectedChecksum) // save() returned before calling repo
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `missing note shows notFound`() {
        val vm = editor("n-gone")
        assertTrue(vm.state.value.notFound)
    }

    // ---- NoteEditorViewModel: the 409 contract ----

    @Test
    fun `conflict keeps user text, adopts fresh checksum, second save overwrites`() {
        val vm = seededEditor()
        vm.onTextChange("my edit")
        repo.conflictOnce = Note(
            id = "n-1",
            text = "server text after sweep",
            checksum = "ck-server",
            updatedAt = t0,
        )
        vm.save()
        var s = vm.state.value

        assertTrue(s.conflict)
        assertEquals("my edit", s.text) // NEVER silently replaced
        assertFalse(s.savedAt != null)
        assertEquals("ck-server", s.checksum) // adopted for the retry

        vm.save() // user chose to overwrite
        s = vm.state.value

        assertFalse(s.conflict)
        assertEquals(t0, s.savedAt)
        assertEquals("ck-2", s.checksum)
        assertEquals("ck-server", repo.lastExpectedChecksum)
    }

    @Test
    fun `reload is the only path that pulls server text`() {
        val vm = seededEditor()
        vm.onTextChange("my edit")
        repo.detail["n-1"] = repo.detail["n-1"]!!.copy(text = "server text after sweep", checksum = "ck-server")
        vm.reload()
        val s = vm.state.value

        assertEquals("server text after sweep", s.text)
        assertFalse(s.dirty)
        assertFalse(s.conflict)
        assertEquals("ck-server", s.checksum)
    }

    // ---- minimal in-test repository: rows + detail + scripted conflicts ----

    private class TestNoteRepository : NoteRepository {
        val rows = MutableStateFlow<List<Note>>(emptyList())
        val detail = mutableMapOf<EntityId, Note>()
        val detailFailures = mutableSetOf<EntityId>()
        var lastExpectedChecksum: String? = null
            private set
        private var conflictPending: Note? = null
        var conflictOnce: Note?
            get() = conflictPending
            set(value) { conflictPending = value }

        override fun observeNotes(projectId: EntityId?, areaId: EntityId?): Flow<List<Note>> = rows
        override fun observeNote(id: EntityId): Flow<Note?> = rows.map { list -> list.firstOrNull { it.id == id } }
        override suspend fun getNote(id: EntityId): Note? {
            if (id in detailFailures) throw RepositoryException("boom")
            return detail[id]
        }
        override suspend fun createNote(draft: NoteDraft, requestId: RequestId): Note =
            throw RepositoryException("not used in these tests")
        override suspend fun updateNote(id: EntityId, text: String, expectedChecksum: String): Note {
            lastExpectedChecksum = expectedChecksum
            conflictPending?.let { fresh ->
                conflictPending = null
                throw NoteConflictException(fresh = fresh)
            }
            val base = detail[id] ?: throw RepositoryException("note not found: $id")
            val updated = base.copy(text = text, checksum = "ck-2")
            detail[id] = updated
            return updated
        }
    }
}

/** T-022b: triage deps the editor never exercises in these tests. */
private val idleTasks = object : dev.magnor.kompakt.data.repository.TaskRepository {
    override fun observeTasks(filter: dev.magnor.kompakt.domain.TaskFilter): kotlinx.coroutines.flow.Flow<List<dev.magnor.kompakt.domain.Task>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override fun observeTask(id: dev.magnor.kompakt.domain.EntityId): kotlinx.coroutines.flow.Flow<dev.magnor.kompakt.domain.Task?> = kotlinx.coroutines.flow.flowOf(null)
    override suspend fun getTask(id: dev.magnor.kompakt.domain.EntityId): dev.magnor.kompakt.domain.Task? = null
    override suspend fun createTask(draft: dev.magnor.kompakt.domain.TaskDraft, requestId: dev.magnor.kompakt.domain.RequestId): dev.magnor.kompakt.domain.Task = throw UnsupportedOperationException()
    override suspend fun completeTask(id: dev.magnor.kompakt.domain.EntityId, expectedRevision: Long, requestId: dev.magnor.kompakt.domain.RequestId): dev.magnor.kompakt.domain.Task = throw UnsupportedOperationException()
    override suspend fun postponeTask(id: dev.magnor.kompakt.domain.EntityId, expectedRevision: Long, newDueAt: kotlinx.datetime.Instant?, requestId: dev.magnor.kompakt.domain.RequestId): dev.magnor.kompakt.domain.Task = throw UnsupportedOperationException()
    override suspend fun updateTask(id: dev.magnor.kompakt.domain.EntityId, expectedRevision: Long, patch: dev.magnor.kompakt.domain.TaskPatch, requestId: dev.magnor.kompakt.domain.RequestId): dev.magnor.kompakt.domain.Task = throw UnsupportedOperationException()
}

private val emptyOrg = object : dev.magnor.kompakt.data.repository.OrganizationRepository {
    override fun observeProjects(): kotlinx.coroutines.flow.Flow<List<dev.magnor.kompakt.domain.Project>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override fun observeProject(id: dev.magnor.kompakt.domain.EntityId): kotlinx.coroutines.flow.Flow<dev.magnor.kompakt.domain.Project?> = kotlinx.coroutines.flow.flowOf(null)
    override fun observeAreas(): kotlinx.coroutines.flow.Flow<List<dev.magnor.kompakt.domain.Area>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override fun observeArea(id: dev.magnor.kompakt.domain.EntityId): kotlinx.coroutines.flow.Flow<dev.magnor.kompakt.domain.Area?> = kotlinx.coroutines.flow.flowOf(null)
}
