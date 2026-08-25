package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.OrganizationRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.NoteSections
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-022b: section triage — crash-safe ordering (external effect first, then
 * source removal), explicit-only transitions, checksum-guarded source PUTs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorTriageTest {

    private val t0 = Instant.parse("2026-08-25T12:00:00Z")

    private val scratchText = "# Scratchpad\n\n" +
        "## 2026-08-25 09:12 — Call dentist\n" +
        "Move to Thursday.\n\n" +
        "## 2026-08-24 21:03 — Idea: agent UI split\n" +
        "Process manager, not chat."

    private val scratchpad = Note(
        id = "note_scratch",
        text = scratchText,
        role = Note.ROLE_SCRATCHPAD,
        checksum = "ck-1",
        createdAt = t0,
        updatedAt = t0,
    )

    /** Records call order across repos — the crash-safety oracle. */
    private val calls = mutableListOf<String>()

    private inner class TestNoteRepository(
        private val store: MutableStateFlow<Note?>,
        private val targets: MutableMap<EntityId, Note> = mutableMapOf(),
    ) : NoteRepository {

        /**
         * When set for an id, getNote hands out a checksum that updateNote
         * will reject — simulating a concurrent write between the client's
         * fetch and its PUT (the target PUT-failure race).
         */
        var staleFetchFor: EntityId? = null

        override fun observeNotes(projectId: EntityId?, areaId: EntityId?): Flow<List<Note>> =
            flow { emit((store.value?.let { listOf(it) } ?: emptyList()) + targets.values) }

        override fun observeNote(id: EntityId): Flow<Note?> = store

        override suspend fun getNote(id: EntityId): Note? {
            val stored = targets[id] ?: return if (id == store.value?.id) store.value else null
            return if (id == staleFetchFor) stored.copy(checksum = "ck-drifted") else stored
        }

        override suspend fun createNote(draft: NoteDraft, requestId: RequestId): Note {
            calls += "create:${draft.text.lineSequence().firstOrNull() ?: ""}:${draft.projectId ?: "inbox"}"
            return Note(id = "note_new", text = draft.text, title = "New", createdAt = t0, updatedAt = t0)
        }

        override suspend fun updateNote(id: EntityId, text: String, expectedChecksum: String): Note {
            val current = if (id == store.value?.id) store.value else targets[id]
            if (current == null) error("no note $id")
            if (current.checksum != expectedChecksum) {
                calls += "conflict:$id"
                throw dev.magnor.kompakt.domain.NoteConflictException(fresh = current)
            }
            calls += "update:$id"
            val updated = current.copy(text = text, checksum = "ck-2", updatedAt = t0)
            if (id == store.value?.id) store.value = updated else targets[id] = updated
            return updated
        }
    }
    private inner class TestTaskRepository : TaskRepository {
        val drafts = mutableListOf<TaskDraft>()
        override fun observeTasks(filter: TaskFilter): Flow<List<Task>> = flow { emit(emptyList()) }
        override fun observeTask(id: EntityId): Flow<Task?> = flow { emit(null) }
        override suspend fun getTask(id: EntityId): Task? = null
        override suspend fun completeTask(id: EntityId, expectedRevision: Long, requestId: RequestId): Task = throw UnsupportedOperationException()
        override suspend fun postponeTask(id: EntityId, expectedRevision: Long, newDueAt: kotlinx.datetime.Instant?, requestId: RequestId): Task = throw UnsupportedOperationException()
        override suspend fun updateTask(id: EntityId, expectedRevision: Long, patch: dev.magnor.kompakt.domain.TaskPatch, requestId: RequestId): Task = throw UnsupportedOperationException()
        override suspend fun createTask(draft: TaskDraft, requestId: RequestId): Task {
            drafts += draft
            return Task(id = "task_new", title = draft.title, updatedAt = t0)
        }
    }

    private class TestOrgRepository(private val projects: List<Project>) : OrganizationRepository {
        override fun observeProjects(): Flow<List<Project>> = flow { emit(projects) }
        override fun observeProject(id: EntityId): Flow<Project?> = flow { emit(projects.firstOrNull { it.id == id }) }
        override fun observeAreas(): Flow<List<Area>> = flow { emit(emptyList()) }
        override fun observeArea(id: EntityId): Flow<Area?> = flow { emit(null) }
    }

    private val kodeverket = Project(id = "vault:project:kodeverket", name = "KodeVerket", updatedAt = t0)

    private fun vm(
        note: MutableStateFlow<Note?>,
        targets: Map<EntityId, Note> = emptyMap(),
        tasks: TestTaskRepository = TestTaskRepository(),
    ) = NoteEditorViewModel(
        noteRepository = TestNoteRepository(note, targets.toMutableMap()),
        taskRepository = tasks,
        organizationRepository = TestOrgRepository(listOf(kodeverket)),
        noteId = note.value!!.id,
        newRequestId = { "req-${calls.size}" },
        now = { t0 },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scratchpad loads triageable with parsed sections`() = runTest {
        val store = MutableStateFlow<Note?>(scratchpad)
        val vm = vm(store)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.triageable)
        assertEquals(2, state.sections.size)
        assertEquals("Call dentist", state.sections[0].title)
    }

    @Test
    fun `newNoteIn creates external note first then removes the section`() = runTest {
        val store = MutableStateFlow<Note?>(scratchpad)
        val vm = vm(store)
        advanceUntilIdle()

        vm.newNoteIn(vm.state.value.sections[0], kodeverket)
        advanceUntilIdle()

        // Ordering oracle: create precedes source update; a crash between
        // duplicates the section (safe direction) — never loses it.
        assertEquals(listOf("create:Move to Thursday.:vault:project:kodeverket", "update:note_scratch"), calls)
        val text = store.value!!.text.orEmpty()
        assertTrue(text.contains("agent UI split"))
        assertTrue(!text.contains("Call dentist"))
    }

    @Test
    fun `appendTo puts target first then source`() = runTest {
        val store = MutableStateFlow<Note?>(scratchpad)
        val journal = Note(
            id = "note_journal",
            text = "Existing entry.",
            checksum = "ck-j",
            createdAt = t0,
            updatedAt = t0,
        )
        val vm = vm(store, targets = mapOf(journal.id to journal))
        advanceUntilIdle()

        vm.appendTo(vm.state.value.sections[0], journal)
        advanceUntilIdle()

        assertEquals(listOf("update:note_journal", "update:note_scratch"), calls)
        assertTrue(!store.value!!.text.orEmpty().contains("Call dentist"))
    }

    @Test
    fun `target PUT failure leaves source untouched - duplicate not loss`() = runTest {
        val store = MutableStateFlow<Note?>(scratchpad)
        // Stored target has moved on since our fetch: getNote hands out a
        // stale checksum (concurrent write) => the target PUT conflicts.
        val stored = Note(id = "note_journal", text = "Journal", checksum = "ck-moved-on", createdAt = t0, updatedAt = t0)
        val repo = TestNoteRepository(store, targets = mutableMapOf(stored.id to stored))
        repo.staleFetchFor = stored.id
        val vm = NoteEditorViewModel(
            noteRepository = repo,
            taskRepository = TestTaskRepository(),
            organizationRepository = TestOrgRepository(listOf(kodeverket)),
            noteId = scratchpad.id,
            newRequestId = { "req" },
            now = { t0 },
        )
        advanceUntilIdle()

        vm.appendTo(vm.state.value.sections[0], repo.getNote(stored.id)!!)
        advanceUntilIdle()

        // Conflict on target: source must be untouched (no second update).
        assertEquals(listOf("conflict:note_journal"), calls)
        assertTrue(store.value!!.text.orEmpty().contains("Call dentist"))
        assertNotNull(vm.state.value.triageNotice)
    }

    @Test
    fun `discard removes the section from the source`() = runTest {
        val store = MutableStateFlow<Note?>(scratchpad)
        val vm = vm(store)
        advanceUntilIdle()

        vm.discard(vm.state.value.sections[0])
        advanceUntilIdle()

        assertEquals(listOf("update:note_scratch"), calls)
        val text = store.value!!.text.orEmpty()
        assertTrue(!text.contains("Call dentist"))
        assertEquals(1, NoteSections.parse(text).size)
    }

    @Test
    fun `createTaskFrom derives a task and keeps the section`() = runTest {
        val store = MutableStateFlow<Note?>(scratchpad)
        val tasks = TestTaskRepository()
        val vm = vm(store, tasks = tasks)
        advanceUntilIdle()

        vm.createTaskFrom(vm.state.value.sections[0])
        advanceUntilIdle()

        assertEquals(1, tasks.drafts.size)
        assertEquals("Call dentist", tasks.drafts[0].title)
        assertEquals(EntityKind.NOTE, tasks.drafts[0].sourceType)
        // Derive ≠ move: the section stays for explicit follow-up triage.
        assertTrue(calls.isEmpty())
        assertTrue(store.value!!.text.orEmpty().contains("Call dentist"))
    }

    @Test
    fun `conflict on source PUT reloads fresh text`() = runTest {
        val store = MutableStateFlow<Note?>(scratchpad)
        val vm = vm(store)
        advanceUntilIdle()

        // Server-side change after our load: bump checksum behind our back.
        store.value = store.value!!.copy(checksum = "ck-moved-on")

        vm.discard(vm.state.value.sections[0])
        advanceUntilIdle()

        assertEquals(listOf("conflict:note_scratch"), calls)
        assertTrue(vm.state.value.triageNotice?.contains("reloaded", ignoreCase = true) == true)
        assertEquals(2, vm.state.value.sections.size) // fresh parse after reload
    }
}
