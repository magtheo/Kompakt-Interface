package dev.magnor.kompakt.ui.viewmodels

import dev.magnor.kompakt.data.repository.AgentRepository
import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.data.repository.WorkspaceRepository
import dev.magnor.kompakt.domain.AgentBackendInfo
import dev.magnor.kompakt.domain.AgentCommand
import dev.magnor.kompakt.domain.AgentDispatchDraft
import dev.magnor.kompakt.domain.AgentEvent
import dev.magnor.kompakt.domain.AgentRole
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunKind
import dev.magnor.kompakt.domain.AgentRunResult
import dev.magnor.kompakt.domain.AgentRunState
import dev.magnor.kompakt.domain.AgentsSurface
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.SteerOutcome
import dev.magnor.kompakt.domain.TaskDraft
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.domain.TaskPatch
import dev.magnor.kompakt.domain.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * An agent repository whose observe flows are COLD ONE-SHOT fetches over a
 * mutable snapshot — exactly the RemoteAgentRepository semantics. The hot
 * FakeAgentRepository auto-updates and therefore HIDES refresh bugs: the
 * T-011 phone E2E showed the role screen's Runs list stale until
 * back+re-enter (fixed in T-012 via the refresh tick).
 */
private class ColdAgentRepository(
    var surfaceSnapshot: AgentsSurface,
    val runsSnapshot: MutableList<AgentRun>,
) : AgentRepository {

    var dispatchError: Exception? = null
    var dispatched = 0
    var lastDraft: AgentDispatchDraft? = null

    /** Cold-fetch counters — the poll loop's observable footprint (T-017). */
    var observeRunCalls = 0
    var resultCalls = 0

    override fun observeSurface(): Flow<AgentsSurface> = flow { emit(surfaceSnapshot) }
    override fun observeRuns(): Flow<List<AgentRun>> = flow { emit(runsSnapshot.toList()) }
    override fun observeRun(id: String): Flow<AgentRun?> = flow {
        observeRunCalls++
        emit(runsSnapshot.firstOrNull { it.id == id })
    }

    override suspend fun dispatch(draft: AgentDispatchDraft, requestId: RequestId): AgentRun {
        dispatched++
        lastDraft = draft
        dispatchError?.let { throw it }
        val run = AgentRun(
            id = "ses_new_${runsSnapshot.size + 1}",
            backend = draft.backend ?: surfaceSnapshot.defaultBackend ?: "opencode",
            kind = AgentRunKind.SESSION,
            agent = draft.agent ?: "",
            state = AgentRunState.QUEUED,
            title = draft.prompt.take(40),
            prompt = draft.prompt,
        )
        runsSnapshot.add(run) // the server projects dispatches into the list immediately
        return run
    }

    override suspend fun send(runId: String, message: String, requestId: RequestId): AgentRun =
        mutate(runId) { it.copy(state = AgentRunState.RUNNING) }

    override suspend fun steer(runId: String, message: String, requestId: RequestId): SteerOutcome =
        SteerOutcome.UNSUPPORTED

    override suspend fun cancel(runId: String, requestId: RequestId) {
        mutate(runId) { it.copy(state = AgentRunState.CANCELLED) }
    }

    override suspend fun result(runId: String): AgentRunResult? {
        resultCalls++
        val run = runsSnapshot.firstOrNull { it.id == runId } ?: return null
        // The server yields a result only once the turn has settled.
        return if (run.state.isTerminal || run.state == AgentRunState.IDLE) {
            AgentRunResult(outcome = run.state, summary = "READY")
        } else null
    }
    override suspend fun events(runId: String, since: Long): List<AgentEvent> = emptyList()
    override suspend fun commands(backend: String): List<AgentCommand> =
        listOf(AgentCommand(name = "test", description = "Run the test suite"))

    override suspend fun runCommand(runId: String, command: String, arguments: String, requestId: RequestId): AgentRun =
        mutate(runId) { it }

    private fun mutate(runId: String, transform: (AgentRun) -> AgentRun): AgentRun {
        val index = runsSnapshot.indexOfFirst { it.id == runId }
        val updated = transform(runsSnapshot[index])
        runsSnapshot[index] = updated
        return updated
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentDetailViewModelTest {

    /** T-022c: picker source — cold one-shot, same semantics as the remote repo. */
    private class TestWorkspaceRepository(
        private val workspaces: List<Workspace> = emptyList(),
    ) : WorkspaceRepository {
        override fun observeWorkspaces(): Flow<List<Workspace>> = flow { emit(workspaces) }
    }

    private val t0 = Instant.parse("2026-08-23T12:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun surface() = AgentsSurface(
        backends = mapOf(
            "opencode" to AgentBackendInfo(name = "opencode", resumable = true, commands = true),
        ),
        agents = listOf(AgentRole(name = "build", description = "Build role", backend = "opencode")),
        defaultBackend = "opencode",
    )

    private fun earlierRun(id: String) = AgentRun(
        id = id, backend = "opencode", kind = AgentRunKind.SESSION, agent = "build",
        state = AgentRunState.SUCCEEDED, title = "Earlier run", createdAt = t0, updatedAt = t0,
    )

    @Test
    fun `dispatch refreshes the runs list without re-entering the screen`() = runTest {
        val repo = ColdAgentRepository(surface(), mutableListOf(earlierRun("ses_1")))
        val vm = AgentDetailViewModel(repo, TestWorkspaceRepository(), "opencode", "build") { "req-1" }
        val roleCollector = launch(UnconfinedTestDispatcher()) { vm.role.collect { } }
        val runsCollector = launch(UnconfinedTestDispatcher()) { vm.runs.collect { } }
        assertEquals(1, vm.runs.value.size)

        vm.dispatch("Reply with the single word READY and stop.", null)

        // Without the refresh tick the cold one-shot flow never re-collects
        // and this stays at 1 (the T-011 phone-E2E regression).
        assertEquals(2, vm.runs.value.size)
        val fresh = vm.runs.value.first { it.id != "ses_1" }
        assertEquals(AgentRunState.QUEUED, fresh.state)
        assertEquals("build", fresh.agent)
        runsCollector.cancel()
        roleCollector.cancel()
    }

    @Test
    fun `dispatch exposes the run for the tappable open row`() = runTest {
        val repo = ColdAgentRepository(surface(), mutableListOf())
        val vm = AgentDetailViewModel(repo, TestWorkspaceRepository(), "opencode", "build") { "req-1" }
        val roleCollector = launch(UnconfinedTestDispatcher()) { vm.role.collect { } }
        val runsCollector = launch(UnconfinedTestDispatcher()) { vm.runs.collect { } }

        vm.dispatch("Readiness check", null)

        assertEquals("ses_new_1", vm.lastDispatched.value?.id)
        assertEquals("Readiness check", vm.lastDispatched.value?.prompt)
        assertNull(vm.feedback.value) // success is the row, not a text note
        runsCollector.cancel()
        roleCollector.cancel()
    }

    @Test
    fun `dispatch failure keeps feedback and does not expose a run`() = runTest {
        val repo = ColdAgentRepository(surface(), mutableListOf())
        repo.dispatchError = RuntimeException("connection refused")
        val vm = AgentDetailViewModel(repo, TestWorkspaceRepository(), "opencode", "build") { "req-1" }
        val roleCollector = launch(UnconfinedTestDispatcher()) { vm.role.collect { } }

        vm.dispatch("will fail", null)

        assertTrue(vm.feedback.value?.startsWith("Dispatch failed") == true)
        assertNull(vm.lastDispatched.value)
        assertTrue(vm.runs.value.isEmpty())
        roleCollector.cancel()
    }

    @Test
    fun `workspaces flow feeds the picker`() = runTest {
        val repo = ColdAgentRepository(surface(), mutableListOf())
        val vm = AgentDetailViewModel(
            repo,
            TestWorkspaceRepository(listOf(Workspace(ref = "evershift", label = "Evershift"))),
            "opencode", "build",
        ) { "req-1" }
        val collector = launch(UnconfinedTestDispatcher()) { vm.workspaces.collect { } }

        assertEquals(1, vm.workspaces.value.size)
        assertEquals("evershift", vm.workspaces.value.first().ref)
        collector.cancel()
    }

    @Test
    fun `dispatch carries the picked workspace ref into the draft`() = runTest {
        val repo = ColdAgentRepository(surface(), mutableListOf())
        val vm = AgentDetailViewModel(repo, TestWorkspaceRepository(), "opencode", "build") { "req-1" }
        val roleCollector = launch(UnconfinedTestDispatcher()) { vm.role.collect { } }
        val runsCollector = launch(UnconfinedTestDispatcher()) { vm.runs.collect { } }

        vm.dispatch("Check the build", "evershift")

        assertEquals("evershift", repo.lastDraft?.projectRef)
        runsCollector.cancel()
        roleCollector.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunDetailViewModelTest {

    private val t0 = Instant.parse("2026-08-23T12:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // Note: no explicit VM clearing needed. The ticker is parked via the
        // shared chain's onCompletion (WhileSubscribed grace) which runTest's
        // teardown idling exercises — a test that ends with an active run
        // still quiesces.
        Dispatchers.resetMain()
    }

    private fun surface() = AgentsSurface(
        backends = mapOf(
            "opencode" to AgentBackendInfo(name = "opencode", resumable = true, commands = true),
        ),
        defaultBackend = "opencode",
    )

    private fun session(state: AgentRunState) = AgentRun(
        id = "ses_1", backend = "opencode", kind = AgentRunKind.SESSION, agent = "build",
        state = state, title = "Readiness check", prompt = "Reply READY", createdAt = t0, updatedAt = t0,
    )

    private fun vm(repo: ColdAgentRepository): AgentRunDetailViewModel =
        AgentRunDetailViewModel(
            agentRepository = repo,
            taskRepository = unusedTaskRepo,
            noteRepository = unusedNoteRepo,
            chatRepository = unusedChatRepo,
            runId = "ses_1",
            newRequestId = { "req-1" },
        )

    // Transitions (create task / save note / discuss) are not under test here —
    // stubs throw if ever reached.
    private val unusedTaskRepo = object : TaskRepository {
        override fun observeTasks(filter: TaskFilter) = throw UnsupportedOperationException()
        override fun observeTask(id: EntityId) = throw UnsupportedOperationException()
        override suspend fun getTask(id: EntityId) = throw UnsupportedOperationException()
        override suspend fun createTask(draft: TaskDraft, requestId: RequestId) = throw UnsupportedOperationException()
        override suspend fun completeTask(id: EntityId, expectedRevision: Long, requestId: RequestId) =
            throw UnsupportedOperationException()
        override suspend fun postponeTask(id: EntityId, expectedRevision: Long, newDueAt: Instant?, requestId: RequestId) =
            throw UnsupportedOperationException()
        override suspend fun updateTask(id: EntityId, expectedRevision: Long, patch: TaskPatch, requestId: RequestId) =
            throw UnsupportedOperationException()
    }
    private val unusedNoteRepo = object : NoteRepository {
        override fun observeNotes(projectId: EntityId?, areaId: EntityId?) = throw UnsupportedOperationException()
        override fun observeNote(id: EntityId) = throw UnsupportedOperationException()
        override suspend fun getNote(id: EntityId) = throw UnsupportedOperationException()
        override suspend fun createNote(draft: NoteDraft, requestId: RequestId) = throw UnsupportedOperationException()
        override suspend fun updateNote(id: EntityId, text: String, expectedChecksum: String) =
            throw UnsupportedOperationException()
    }
    private val unusedChatRepo = object : ChatRepository {
        override fun observeThreads() = throw UnsupportedOperationException()
        override fun observeThread(id: EntityId) = throw UnsupportedOperationException()
        override fun observeMessages(chatId: EntityId) = throw UnsupportedOperationException()
        override suspend fun getThread(id: EntityId) = throw UnsupportedOperationException()
        override suspend fun createThread(draft: ChatThreadDraft, requestId: RequestId) =
            throw UnsupportedOperationException()
        override suspend fun sendMessage(chatId: EntityId, text: String, requestId: RequestId) =
            throw UnsupportedOperationException()
        override suspend fun truncate(chatId: EntityId, keepThrough: EntityId?, requestId: RequestId) =
            throw UnsupportedOperationException()
        override suspend fun setScope(chatId: EntityId, scopeType: String?, scopeRef: String?, requestId: RequestId) =
            throw UnsupportedOperationException()
    }

    @Test
    fun `send refreshes the run state without re-entering the screen`() = runTest {
        val repo = ColdAgentRepository(surface(), mutableListOf(session(AgentRunState.QUEUED)))
        val vm = vm(repo)
        val collector = launch(UnconfinedTestDispatcher()) { vm.run.collect { } }
        assertEquals(AgentRunState.QUEUED, vm.run.value?.state)

        vm.send("next instruction")

        // Without the refresh tick the status row stays QUEUED (cold one-shot).
        assertEquals(AgentRunState.RUNNING, vm.run.value?.state)
        collector.cancel()
    }

    @Test
    fun `cancel refreshes the run state without re-entering the screen`() = runTest {
        val repo = ColdAgentRepository(surface(), mutableListOf(session(AgentRunState.RUNNING)))
        val vm = vm(repo)
        val collector = launch(UnconfinedTestDispatcher()) { vm.run.collect { } }
        assertEquals(AgentRunState.RUNNING, vm.run.value?.state)

        vm.cancel()

        assertEquals(AgentRunState.CANCELLED, vm.run.value?.state)
        assertEquals("Cancelled", vm.feedback.value)
        collector.cancel()
    }

    // ---- T-017: poll while a turn runs, auto-settle when it lands ----
    // These tests re-pin Main to the test scheduler so advanceTimeBy drives
    // the ViewModel's poll delays; the eager dispatcher keeps the immediate
    // semantics the vm() helper above was written against.

    @Test
    fun `running run settles via polling and pulls fresh evidence once`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = ColdAgentRepository(surface(), mutableListOf(session(AgentRunState.RUNNING)))
        val vm = vm(repo)
        val collector = launch(UnconfinedTestDispatcher()) { vm.run.collect { } }

        assertEquals(AgentRunState.RUNNING, vm.run.value?.state)
        assertEquals(1, repo.observeRunCalls) // initial fetch, no poll yet

        // The server settles the turn at t=12s — after the 10s poll ran.
        launch { delay(12_000); repo.runsSnapshot[0] = repo.runsSnapshot[0].copy(state = AgentRunState.IDLE) }

        advanceTimeBy(20_000) // polls at 5s,10s see RUNNING; the 15s fetch sees IDLE

        assertEquals(AgentRunState.IDLE, vm.run.value?.state)
        assertEquals("READY", vm.result.value?.summary) // evidence pulled on settle
        assertEquals(2, repo.resultCalls) // init + settle — no per-poll result spam

        val fetchesAtSettle = repo.observeRunCalls
        advanceTimeBy(60_000) // polling must have stopped with the turn
        assertEquals(fetchesAtSettle, repo.observeRunCalls)
        collector.cancel()
    }

    @Test
    fun `send on an idle session restarts polling until the resumed turn settles`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = ColdAgentRepository(surface(), mutableListOf(session(AgentRunState.IDLE)))
        val vm = vm(repo)
        val collector = launch(UnconfinedTestDispatcher()) { vm.run.collect { } }

        advanceTimeBy(30_000) // idle on open: polling must not start
        assertEquals(1, repo.observeRunCalls)

        // The resumed turn settles at t=42s (12s after the send at t≈30s).
        launch { delay(12_000); repo.runsSnapshot[0] = repo.runsSnapshot[0].copy(state = AgentRunState.IDLE) }
        vm.send("next instruction")

        advanceTimeBy(20_000)

        assertEquals(AgentRunState.IDLE, vm.run.value?.state)
        assertEquals("READY", vm.result.value?.summary)

        val fetchesAtSettle = repo.observeRunCalls
        advanceTimeBy(60_000)
        assertEquals(fetchesAtSettle, repo.observeRunCalls)
        collector.cancel()
    }

    @Test
    fun `terminal run on open never polls`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = ColdAgentRepository(surface(), mutableListOf(session(AgentRunState.SUCCEEDED)))
        val vm = vm(repo)
        val collector = launch(UnconfinedTestDispatcher()) { vm.run.collect { } }

        advanceTimeBy(60_000)

        assertEquals(1, repo.observeRunCalls) // the one initial fetch
        assertEquals(1, repo.resultCalls) // init evidence only
        collector.cancel()
    }

    @Test
    fun `canSend gates the composer on turn activity`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = ColdAgentRepository(surface(), mutableListOf(session(AgentRunState.RUNNING)))
        val vm = vm(repo)
        // Collecting canSend opens the shared chain's gate — same lifecycle
        // as the screen collecting run + canSend together.
        val collector = launch(UnconfinedTestDispatcher()) { vm.canSend.collect { } }

        assertFalse("busy turn must gate the composer", vm.canSend.value)

        // The server settles the turn at t=12s; the poll lands it at t=15s.
        launch { delay(12_000); repo.runsSnapshot[0] = repo.runsSnapshot[0].copy(state = AgentRunState.IDLE) }
        advanceTimeBy(20_000)

        assertTrue("settled session must be sendable", vm.canSend.value)
        collector.cancel()
    }
}
