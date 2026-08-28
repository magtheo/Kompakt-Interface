package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.repository.AgentRepository
import dev.magnor.kompakt.data.repository.InboxRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.OrganizationRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.domain.AgentDispatchDraft
import dev.magnor.kompakt.domain.AgentEvent
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunResult
import dev.magnor.kompakt.domain.AgentsSurface
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.RepositoryException
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.SteerOutcome
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskDraft
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.domain.TaskPatch
import dev.magnor.kompakt.domain.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-025: kind-aware item detail.
 *
 * The regression this file pins: task detail used to fan out into five
 * cold flows (tasks, notes, runs, inbox, projects) — 3 wasted wire
 * probes per open (getNote swallows 404s, so they are pure noise) and a
 * single failing source collapsed the whole screen (Aug 27). With
 * kind=task the VM observes exactly the task repo + the projects
 * display join, and the join degrades to an empty list instead of
 * failing the entity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemDetailViewModelTest {

    private val now = Instant.parse("2026-08-28T12:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Recording cold one-shot fakes (remote semantics) ────────────────

    private class TestTaskRepository(private val tasks: Map<EntityId, Task>) : TaskRepository {
        var observeByIdCalls = 0
        var listCalls = 0

        override fun observeTasks(filter: TaskFilter): Flow<List<Task>> {
            listCalls++
            return flow { emit(tasks.values.toList()) }
        }

        override fun observeTask(id: EntityId): Flow<Task?> {
            observeByIdCalls++
            return flow { emit(tasks[id]) }
        }

        override suspend fun getTask(id: EntityId): Task? = tasks[id]
        override suspend fun createTask(draft: TaskDraft, requestId: RequestId): Task =
            throw UnsupportedOperationException()

        override suspend fun completeTask(id: EntityId, expectedRevision: Long, requestId: RequestId): Task =
            throw UnsupportedOperationException()

        override suspend fun postponeTask(
            id: EntityId,
            expectedRevision: Long,
            newDueAt: Instant?,
            requestId: RequestId,
        ): Task = throw UnsupportedOperationException()

        override suspend fun updateTask(
            id: EntityId,
            expectedRevision: Long,
            patch: TaskPatch,
            requestId: RequestId,
        ): Task = throw UnsupportedOperationException()
    }

    /** Counts observeNote calls — must stay 0 on the typed leg. */
    private class TestNoteRepository : NoteRepository {
        var observeCalls = 0
        override fun observeNotes(projectId: EntityId?, areaId: EntityId?): Flow<List<Note>> =
            flow { emit(emptyList()) }

        override fun observeNote(id: EntityId): Flow<Note?> {
            observeCalls++
            return flow { emit(null) }
        }

        override suspend fun getNote(id: EntityId): Note? = null
        override suspend fun createNote(draft: NoteDraft, requestId: RequestId): Note =
            throw UnsupportedOperationException()

        override suspend fun updateNote(id: EntityId, text: String, expectedChecksum: String): Note =
            throw UnsupportedOperationException()
    }

    private class TestAgentRepository : AgentRepository {
        var observeRunCalls = 0
        override fun observeSurface(): Flow<AgentsSurface> = flow { emit(AgentsSurface()) }
        override fun observeRuns(): Flow<List<AgentRun>> = flow { emit(emptyList()) }
        override fun observeRun(id: String): Flow<AgentRun?> {
            observeRunCalls++
            return flow { emit(null) }
        }

        override suspend fun dispatch(draft: AgentDispatchDraft, requestId: RequestId): AgentRun =
            throw UnsupportedOperationException()

        override suspend fun send(runId: String, message: String, requestId: RequestId): AgentRun =
            throw UnsupportedOperationException()

        override suspend fun steer(runId: String, message: String, requestId: RequestId): SteerOutcome =
            throw UnsupportedOperationException()

        override suspend fun cancel(runId: String, requestId: RequestId) =
            throw UnsupportedOperationException()

        override suspend fun result(runId: String): AgentRunResult? = null
        override suspend fun events(runId: String, since: Long): List<AgentEvent> = emptyList()
        override suspend fun commands(backend: String) = emptyList<dev.magnor.kompakt.domain.AgentCommand>()
        override suspend fun runCommand(
            runId: String,
            command: String,
            arguments: String,
            requestId: RequestId,
        ): AgentRun = throw UnsupportedOperationException()
    }

    private class TestInboxRepository(private val items: List<InboxItem> = emptyList()) : InboxRepository {
        var observeCalls = 0
        override fun observeInbox(): Flow<List<InboxItem>> {
            observeCalls++
            return flow { emit(items) }
        }

        override suspend fun dismiss(id: EntityId, expectedRevision: Long, requestId: RequestId) =
            throw UnsupportedOperationException()
    }

    private class TestOrgRepository(
        private val projects: List<Project> = emptyList(),
        private val failProjects: Boolean = false,
    ) : OrganizationRepository {
        override fun observeProjects(): Flow<List<Project>> =
            if (failProjects) flow { throw RepositoryException("capability 'project.read' required") }
            else flow { emit(projects) }

        override fun observeProject(id: EntityId): Flow<Project?> = flow { emit(null) }
        override fun observeAreas(): Flow<List<Area>> = flow { emit(emptyList()) }
        override fun observeArea(id: EntityId): Flow<Area?> = flow { emit(null) }
    }

    private fun task() = Task(
        id = "vikunja:task:42",
        title = "Fix the sorting bug",
        status = TaskStatus.OPEN,
        projectId = "vault:project:kodeverket",
        revision = 7,
        updatedAt = now,
    )

    private fun project() = Project(id = "vault:project:kodeverket", name = "KodeVerket", updatedAt = now)

    private fun vm(
        taskRepo: TaskRepository,
        noteRepo: NoteRepository = TestNoteRepository(),
        agentRepo: AgentRepository = TestAgentRepository(),
        inboxRepo: InboxRepository = TestInboxRepository(),
        orgRepo: OrganizationRepository = TestOrgRepository(listOf(project())),
        itemId: EntityId = "vikunja:task:42",
        kind: EntityKind? = null,
    ) = ItemDetailViewModel(
        taskRepository = taskRepo,
        noteRepository = noteRepo,
        agentRepository = agentRepo,
        inboxRepository = inboxRepo,
        organizationRepository = orgRepo,
        itemId = itemId,
        kind = kind,
        now = now,
    )

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun `typed task leg renders without probing notes, runs or inbox`() = runTest(UnconfinedTestDispatcher()) {
        val taskRepo = TestTaskRepository(mapOf("vikunja:task:42" to task()))
        val noteRepo = TestNoteRepository()
        val agentRepo = TestAgentRepository()
        val inboxRepo = TestInboxRepository()

        val model = vm(taskRepo, noteRepo, agentRepo, inboxRepo, kind = EntityKind.TASK)
        val job = launch { model.state.collect {} }
        try {
            val state = model.state.value
            assertTrue("task renders", state.found)
            assertEquals("Task", state.kind)
            assertEquals("Fix the sorting bug", state.title)
            assertEquals("KodeVerket", state.project)
            assertEquals(7L, state.revision)
            assertEquals("typed leg must observe the task by id", 1, taskRepo.observeByIdCalls)
            assertEquals("typed leg must NOT list tasks", 0, taskRepo.listCalls)
            assertEquals("typed leg must NOT probe notes (404 noise)", 0, noteRepo.observeCalls)
            assertEquals("typed leg must NOT probe agent runs", 0, agentRepo.observeRunCalls)
            assertEquals("typed leg must NOT read the inbox", 0, inboxRepo.observeCalls)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `typed task leg survives a failing projects join`() = runTest(UnconfinedTestDispatcher()) {
        // Aug 27 incident shape: project.read missing → /v1/projects 403.
        // The task itself must still render; only the project name drops.
        val taskRepo = TestTaskRepository(mapOf("vikunja:task:42" to task()))
        val model = vm(taskRepo, orgRepo = TestOrgRepository(failProjects = true), kind = EntityKind.TASK)
        val job = launch { model.state.collect {} }
        try {
            val state = model.state.value
            assertTrue("entity survives join failure", state.found)
            assertEquals("Fix the sorting bug", state.title)
            assertNull("project name degrades to null", state.project)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `typed task leg reports not found for an unknown id`() = runTest(UnconfinedTestDispatcher()) {
        val model = vm(TestTaskRepository(emptyMap()), kind = EntityKind.TASK)
        val job = launch { model.state.collect {} }
        try {
            assertFalse(model.state.value.found)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `unknown kind keeps the fan-out fallback`() = runTest(UnconfinedTestDispatcher()) {
        // Inbox deep links arrive without a kind — the resolver leg must
        // still probe every source and resolve an inbox item.
        val inboxRepo = TestInboxRepository(
            listOf(
                InboxItem(
                    id = "inbox_1",
                    sourceType = EntityKind.NOTE,
                    sourceId = null,
                    title = "Capture landed",
                    summary = "from capture",
                    timestamp = now,
                ),
            ),
        )
        val model = vm(TestTaskRepository(emptyMap()), inboxRepo = inboxRepo, itemId = "inbox_1", kind = null)
        val job = launch { model.state.collect {} }
        try {
            val state = model.state.value
            assertTrue(state.found)
            assertEquals("Inbox", state.kind)
            assertEquals("Capture landed", state.title)
            assertTrue("fallback probes the inbox", inboxRepo.observeCalls >= 1)
        } finally {
            job.cancel()
        }
    }
}
