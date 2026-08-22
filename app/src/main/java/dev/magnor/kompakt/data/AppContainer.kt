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
import dev.magnor.kompakt.data.repository.AgentRepository
import dev.magnor.kompakt.data.repository.CaptureRepository
import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.data.repository.InboxRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.OrganizationRepository
import dev.magnor.kompakt.data.repository.SyncRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.data.repository.TodayRepository
import dev.magnor.kompakt.domain.Agent
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Manual dependency container — no DI framework, the APK stays easy to
 * audit (docs/technical-architecture.md). Phase 2 wires fake repositories;
 * Phase 3+ swaps them for HTTP-backed implementations behind the same
 * interfaces. One instance lives for the app process lifetime.
 */
class AppContainer(
    val clock: () -> Instant = { FakeData.NOW },
) {

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

    // ---- repositories (interfaces are the contract; fakes are the Phase 2 impl) ----

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

    val chatRepository: ChatRepository = fakeChats
    val agentRepository: AgentRepository = fakeAgents
    val taskRepository: TaskRepository = fakeTasks
    val noteRepository: NoteRepository = fakeNotes
    val organizationRepository: OrganizationRepository = FakeOrganizationRepository(
        projects = projectStore, areas = areaStore,
    )
    val inboxRepository: InboxRepository = FakeInboxRepository(
        items = inboxStore, idempotency = idempotency,
    )
    val todayRepository: TodayRepository = FakeTodayRepository(
        events = FakeData.calendarEvents,
        tasks = fakeTasks,
        inbox = inboxRepository,
        agents = fakeAgents,
        notes = fakeNotes,
        now = now,
    )
    val captureRepository: CaptureRepository = FakeCaptureRepository(
        tasks = fakeTasks,
        notes = fakeNotes,
        chats = fakeChats,
        agents = fakeAgents,
        idempotency = idempotency,
        now = now,
    )

    private val lastSyncState = MutableStateFlow<Instant?>(null)
    val syncRepository: SyncRepository = FakeSyncRepository(
        changeLog = changeLog, lastSync = lastSyncState, now = now,
    )

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
