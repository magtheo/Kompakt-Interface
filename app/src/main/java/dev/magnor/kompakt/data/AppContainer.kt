package dev.magnor.kompakt.data

import dev.magnor.kompakt.data.fake.FakeAgentRepository
import dev.magnor.kompakt.data.fake.FakeCaptureRepository
import dev.magnor.kompakt.data.fake.FakeChangeLog
import dev.magnor.kompakt.data.fake.FakeChatRepository
import dev.magnor.kompakt.data.fake.FakeData
import dev.magnor.kompakt.data.fake.FakeInboxRepository
import dev.magnor.kompakt.data.fake.FakeNoteRepository
import dev.magnor.kompakt.data.fake.FakeOrganizationRepository
import dev.magnor.kompakt.data.fake.FakeStore
import dev.magnor.kompakt.data.fake.FakeSyncRepository
import dev.magnor.kompakt.data.fake.FakeTaskRepository
import dev.magnor.kompakt.data.fake.FakeTodayRepository
import dev.magnor.kompakt.data.fake.IdempotencyRegistry
import dev.magnor.kompakt.data.remote.HttpApi
import dev.magnor.kompakt.data.remote.RemoteAgentRepository
import dev.magnor.kompakt.data.remote.RemoteCaptureRepository
import dev.magnor.kompakt.data.remote.RemoteChatRepository
import dev.magnor.kompakt.data.remote.RemoteInboxRepository
import dev.magnor.kompakt.data.remote.RemoteNoteRepository
import dev.magnor.kompakt.data.remote.RemoteOrganizationRepository
import dev.magnor.kompakt.data.remote.RemoteSyncRepository
import dev.magnor.kompakt.data.remote.RemoteTaskRepository
import dev.magnor.kompakt.data.remote.RemoteTodayRepository
import dev.magnor.kompakt.data.repository.AgentRepository
import dev.magnor.kompakt.data.repository.CaptureRepository
import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.data.repository.InboxRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.OrganizationRepository
import dev.magnor.kompakt.data.repository.QueueingCaptureRepository
import dev.magnor.kompakt.data.repository.SyncRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.data.repository.TodayRepository
import dev.magnor.kompakt.data.security.InMemorySecretVault
import dev.magnor.kompakt.data.security.SecretVault
import dev.magnor.kompakt.domain.ActionType
import dev.magnor.kompakt.domain.Agent
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.CapabilitySet
import dev.magnor.kompakt.domain.ChangePage
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.CaptureResult
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.ServerStatus
import dev.magnor.kompakt.domain.ServerUnavailableException
import dev.magnor.kompakt.domain.SyncCursorValue
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskDraft
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.domain.TaskPatch
import dev.magnor.kompakt.domain.TodayProjection
import dev.magnor.kompakt.domain.UnauthorizedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Manual dependency container — no DI framework, the APK stays easy to
 * audit (docs/technical-architecture.md). Fake mode ships demo data;
 * Remote mode talks to the coordinator's /v1/ contract. The switch is
 * explicit so the demo build never silently hits a network.
 *
 * T-006: the exposed repositories are STABLE switch instances that resolve
 * fake-vs-remote per call. ViewModels keep their repository references for
 * their whole (backstack-entry) lifetime; the enrollment flip used to pass
 * them by, leaving demo data on screen after enrolling. With the switch,
 * the next collection after a flip goes to the live stack.
 */
sealed interface ServerMode {
    /** In-memory demo data (Phase 2 fakes). */
    data object Fake : ServerMode

    /** Live coordinator: base URL (e.g. http://dev-server.example.ts.net:8650) + bearer token. */
    data class Remote(val baseUrl: String, val token: String) : ServerMode
}

class AppContainer(
    val mode: ServerMode = ServerMode.Fake,
    val clock: () -> Instant = { FakeData.NOW },
    secretVault: SecretVault? = null,
    captureQueueDir: java.io.File? = null,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val vault: SecretVault = secretVault ?: InMemorySecretVault()

    /**
     * Device enrollment (Phase 4). Owns identity + the per-device token;
     * when it reaches Active the container flips every repository from
     * fakes to the live remote stack — no restart needed.
     */
    val enrollment: EnrollmentManager = EnrollmentManager(vault)

    /** Live capability cache — the single source for protocol §9 gating. */
    val capabilityStore = CapabilityStore(scope)

    val now: () -> Instant = clock
    val changeLog = FakeChangeLog(now)
    val idempotency = IdempotencyRegistry()

    private val idCounter = AtomicLong(100)

    fun nextId(prefix: String): EntityId = "%s_%03d".format(prefix, idCounter.incrementAndGet())

    fun nextRequestId(): RequestId = "req-${UUID.randomUUID()}"

    // ---- stores (one per synchronizable entity kind) ----

    private val threadStore = FakeStore<ChatThread>(EntityKind.CHAT, changeLog, now)
    private val messageStore = FakeStore<Message>(EntityKind.MESSAGE, changeLog, now)
    private val agentStore = FakeStore<Agent>(EntityKind.AGENT, changeLog, now)
    private val runStore = FakeStore<AgentRun>(EntityKind.AGENT_RUN, changeLog, now)
    private val taskStore = FakeStore<Task>(EntityKind.TASK, changeLog, now)
    private val noteStore = FakeStore<Note>(EntityKind.NOTE, changeLog, now)
    private val projectStore = FakeStore<Project>(EntityKind.PROJECT, changeLog, now)
    private val areaStore = FakeStore<Area>(EntityKind.AREA, changeLog, now)
    private val inboxStore = FakeStore<InboxItem>(EntityKind.INBOX_ITEM, changeLog, now)

    // ---- fake stack (demo mode + fallback after revocation) ----

    private val fakeChats = FakeChatRepository(
        threads = threadStore, messages = messageStore,
        idempotency = idempotency, nextId = { nextId("thread") }, now = now,
    )
    private val fakeAgents = FakeAgentRepository(
        agents = agentStore, runs = runStore,
        idempotency = idempotency, nextId = { nextId("run") }, now = now,
    )
    private val fakeTasks = FakeTaskRepository(
        tasks = taskStore, idempotency = idempotency, nextId = { nextId("task") }, now = now,
    )
    private val fakeNotes = FakeNoteRepository(
        notes = noteStore, idempotency = idempotency, nextId = { nextId("note") }, now = now,
    )
    private val fakeOrganization = FakeOrganizationRepository(
        projects = projectStore, areas = areaStore,
    )
    private val fakeInbox = FakeInboxRepository(
        items = inboxStore, idempotency = idempotency,
    )
    private val fakeToday = FakeTodayRepository(
        events = FakeData.calendarEvents,
        tasks = fakeTasks,
        inbox = FakeInboxRepository(
            items = inboxStore, idempotency = idempotency,
        ),
        agents = fakeAgents,
        notes = fakeNotes,
        now = now,
    )
    private val fakeCapture = FakeCaptureRepository(
        tasks = fakeTasks,
        notes = fakeNotes,
        chats = fakeChats,
        agents = fakeAgents,
        idempotency = idempotency,
        now = now,
    )
    private val lastSyncState = MutableStateFlow<Instant?>(null)
    private val fakeSync = FakeSyncRepository(
        changeLog = changeLog, lastSync = lastSyncState, now = now,
    )

    // ---- remote plumbing (null while unenrolled / Fake mode) ----

    /** All remote repositories for one active HttpApi, built together. */
    private class RemoteStack(val api: HttpApi) {
        val sync = RemoteSyncRepository(api)
        val today = RemoteTodayRepository(api)
        val tasks = RemoteTaskRepository(api)
        val notes = RemoteNoteRepository(api)
        val organization = RemoteOrganizationRepository(api)
        val inbox = RemoteInboxRepository(api)
        val chats = RemoteChatRepository(api)
        val agents = RemoteAgentRepository(api)
        val capture = RemoteCaptureRepository(api)
    }

    @Volatile
    private var remoteStack: RemoteStack? = null

    /**
     * Offline capture queue — commits parked while offline, re-sent with
     * the same request id once a remote stack is active.
     *
     * MUST be declared before the init block below: init → activateRemote
     * launches flushPendingCaptures on Dispatchers.Default, which reads
     * this store — Kotlin initializes properties and init blocks in
     * declaration order, so a later declaration is a null-deref race on
     * every cold start with an active enrollment (crash loop).
     */
    val pendingCaptures = PendingCaptureStore(captureQueueDir)

    private fun activateRemote(api: HttpApi) {
        val stack = RemoteStack(api)
        remoteStack = stack
        capabilityStore.refresh(stack.sync)
        // Connectivity (or enrollment) just (re)appeared — drain parked captures.
        scope.launch { flushPendingCaptures() }
    }

    private fun deactivateRemote() {
        remoteStack = null
    }

    /** Manual capability re-fetch (Settings/Diagnostics retry hook). */
    fun refreshCapabilities() {
        remoteStack?.let { capabilityStore.refresh(it.sync) }
    }

    init {
        // Static remote mode (tests, live smoke): token fixed at construction.
        if (mode is ServerMode.Remote) {
            activateRemote(HttpApi(mode.baseUrl, mode.token))
        } else {
            // Enrolled mode: follow the enrollment state machine.
            scope.launch {
                enrollment.state.collect { state ->
                    if (state is EnrollmentManager.State.Active) {
                        activateRemote(HttpApi(state.baseUrl, enrollment.tokenProvider()))
                    } else {
                        deactivateRemote()
                    }
                }
            }
            // Restore an already-active enrollment at boot (vault read is sync).
            (enrollment.state.value as? EnrollmentManager.State.Active)?.let { active ->
                activateRemote(HttpApi(active.baseUrl, enrollment.tokenProvider()))
            }
        }
    }

    // ---- repositories: stable switch instances (fake ↔ remote per call) ----

    val chatRepository: ChatRepository = SwitchChat()
    val agentRepository: AgentRepository = SwitchAgent()
    val taskRepository: TaskRepository = SwitchTask()
    val noteRepository: NoteRepository = SwitchNote()
    val organizationRepository: OrganizationRepository = SwitchOrganization()
    val inboxRepository: InboxRepository = SwitchInbox()
    val todayRepository: TodayRepository = SwitchToday()

    val captureRepository: CaptureRepository =
        QueueingCaptureRepository(SwitchCapture(), pendingCaptures) { flushPendingCaptures() }
    val syncRepository: SyncRepository = SwitchSync()

    /**
     * Re-send queued captures through the ACTIVE stack. Returns how many
     * were delivered. Stops at the first transport-level failure (still
     * offline); permanently rejected captures are dropped — the server is
     * authoritative and re-queuing a malformed proposal is pointless.
     */
    suspend fun flushPendingCaptures(): Int {
        val stack = remoteStack ?: return 0
        var flushed = 0
        for (pending in pendingCaptures.all()) {
            try {
                stack.capture.commit(pending.proposal, pending.requestId)
                pendingCaptures.remove(pending.requestId)
                flushed++
            } catch (e: OfflineException) {
                break
            } catch (e: UnauthorizedException) {
                break // token revoked mid-flush — stop, re-enroll first
            } catch (e: ServerUnavailableException) {
                break // server broken — retry on next trigger
            } catch (e: Exception) {
                pendingCaptures.remove(pending.requestId) // rejected — drop
            }
        }
        return flushed
    }

    val remoteActive: Boolean get() = remoteStack != null

    /**
     * Connectivity-triggered flush entry point (Phase 9). No-op when the
     * queue is empty or no remote stack is active; rate limiting lives in
     * FlushPolicy at the call site.
     */
    fun tryFlushPendingCaptures() {
        if (pendingCaptures.count.value == 0) return
        scope.launch { flushPendingCaptures() }
    }

    /** Last-known capabilities (demo set until a remote stack refreshes it). */
    val capabilities: CapabilitySet get() = capabilityStore.capabilities.value

    private inner class SwitchChat : ChatRepository {
        private fun cur(): ChatRepository = remoteStack?.chats ?: fakeChats
        override fun observeThreads(): Flow<List<ChatThread>> = cur().observeThreads()
        override fun observeThread(id: EntityId): Flow<ChatThread?> = cur().observeThread(id)
        override fun observeMessages(chatId: EntityId): Flow<List<Message>> = cur().observeMessages(chatId)
        override suspend fun getThread(id: EntityId): ChatThread? = cur().getThread(id)
        override suspend fun createThread(draft: ChatThreadDraft, requestId: RequestId): ChatThread =
            cur().createThread(draft, requestId)
        override suspend fun sendMessage(chatId: EntityId, text: String, requestId: RequestId): Message =
            cur().sendMessage(chatId, text, requestId)
    }

    private inner class SwitchAgent : AgentRepository {
        private fun cur(): AgentRepository = remoteStack?.agents ?: fakeAgents
        override fun observeAgents(): Flow<List<Agent>> = cur().observeAgents()
        override fun observeAgent(id: EntityId): Flow<Agent?> = cur().observeAgent(id)
        override fun observeRuns(agentId: EntityId?): Flow<List<AgentRun>> = cur().observeRuns(agentId)
        override fun observeRun(id: EntityId): Flow<AgentRun?> = cur().observeRun(id)
        override suspend fun getAgent(id: EntityId): Agent? = cur().getAgent(id)
        override suspend fun getRun(id: EntityId): AgentRun? = cur().getRun(id)
        override suspend fun requestRun(agentId: EntityId, objective: String, requestId: RequestId): AgentRun =
            cur().requestRun(agentId, objective, requestId)
        override suspend fun actOnRun(runId: EntityId, action: ActionType, requestId: RequestId): AgentRun =
            cur().actOnRun(runId, action, requestId)
    }

    private inner class SwitchTask : TaskRepository {
        private fun cur(): TaskRepository = remoteStack?.tasks ?: fakeTasks
        override fun observeTasks(filter: TaskFilter): Flow<List<Task>> = cur().observeTasks(filter)
        override fun observeTask(id: EntityId): Flow<Task?> = cur().observeTask(id)
        override suspend fun getTask(id: EntityId): Task? = cur().getTask(id)
        override suspend fun createTask(draft: TaskDraft, requestId: RequestId): Task =
            cur().createTask(draft, requestId)
        override suspend fun completeTask(id: EntityId, expectedRevision: Long, requestId: RequestId): Task =
            cur().completeTask(id, expectedRevision, requestId)
        override suspend fun postponeTask(id: EntityId, expectedRevision: Long, newDueAt: Instant?, requestId: RequestId): Task =
            cur().postponeTask(id, expectedRevision, newDueAt, requestId)
        override suspend fun updateTask(id: EntityId, expectedRevision: Long, patch: TaskPatch, requestId: RequestId): Task =
            cur().updateTask(id, expectedRevision, patch, requestId)
    }

    private inner class SwitchNote : NoteRepository {
        private fun cur(): NoteRepository = remoteStack?.notes ?: fakeNotes
        override fun observeNotes(projectId: EntityId?, areaId: EntityId?): Flow<List<Note>> =
            cur().observeNotes(projectId, areaId)
        override fun observeNote(id: EntityId): Flow<Note?> = cur().observeNote(id)
        override suspend fun getNote(id: EntityId): Note? = cur().getNote(id)
        override suspend fun createNote(draft: NoteDraft, requestId: RequestId): Note =
            cur().createNote(draft, requestId)
    }

    private inner class SwitchOrganization : OrganizationRepository {
        private fun cur(): OrganizationRepository = remoteStack?.organization ?: fakeOrganization
        override fun observeProjects(): Flow<List<Project>> = cur().observeProjects()
        override fun observeProject(id: EntityId): Flow<Project?> = cur().observeProject(id)
        override fun observeAreas(): Flow<List<Area>> = cur().observeAreas()
        override fun observeArea(id: EntityId): Flow<Area?> = cur().observeArea(id)
    }

    private inner class SwitchInbox : InboxRepository {
        private fun cur(): InboxRepository = remoteStack?.inbox ?: fakeInbox
        override fun observeInbox(): Flow<List<InboxItem>> = cur().observeInbox()
        override suspend fun dismiss(id: EntityId, expectedRevision: Long, requestId: RequestId) =
            cur().dismiss(id, expectedRevision, requestId)
    }

    private inner class SwitchToday : TodayRepository {
        private fun cur(): TodayRepository = remoteStack?.today ?: fakeToday
        override suspend fun today(): TodayProjection = cur().today()
        override fun observeToday(): Flow<TodayProjection> = cur().observeToday()
    }

    private inner class SwitchCapture : CaptureRepository {
        private fun cur(): CaptureRepository = remoteStack?.capture ?: fakeCapture
        override suspend fun interpret(input: String): CaptureProposal = cur().interpret(input)
        override suspend fun commit(proposal: CaptureProposal, requestId: RequestId): CaptureResult =
            cur().commit(proposal, requestId)
    }

    private inner class SwitchSync : SyncRepository {
        private fun cur(): SyncRepository = remoteStack?.sync ?: fakeSync
        override suspend fun capabilities(): CapabilitySet = cur().capabilities()
        override suspend fun status(): ServerStatus = cur().status()
        override suspend fun changesSince(cursor: SyncCursorValue?): ChangePage = cur().changesSince(cursor)
        override fun observeLastSync(): Flow<Instant?> = cur().observeLastSync()
        override suspend fun markSynced(requestId: RequestId) = cur().markSynced(requestId)
    }

    init {
        threadStore.seed(FakeData.chatThreads)
        messageStore.seed(FakeData.chatMessages)
        agentStore.seed(FakeData.agents)
        runStore.seed(FakeData.agentRuns)
        taskStore.seed(FakeData.tasks)
        noteStore.seed(FakeData.notes)
        projectStore.seed(FakeData.projects)
        areaStore.seed(FakeData.areas)
        inboxStore.seed(FakeData.inboxItems)
    }
}
