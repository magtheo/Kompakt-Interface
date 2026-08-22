package dev.magnor.kompakt.data.fake

import dev.magnor.kompakt.data.repository.AgentRepository
import dev.magnor.kompakt.data.repository.CaptureRepository
import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.data.repository.InboxRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.OrganizationRepository
import dev.magnor.kompakt.data.repository.SyncRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.data.repository.TodayRepository
import dev.magnor.kompakt.domain.ActionType
import dev.magnor.kompakt.domain.Agent
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunStatus
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.CaptureRejectedException
import dev.magnor.kompakt.domain.CaptureResult
import dev.magnor.kompakt.domain.CaptureType
import dev.magnor.kompakt.domain.CapabilitySet
import dev.magnor.kompakt.domain.ChangePage
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.MessageRole
import dev.magnor.kompakt.domain.MessageStatus
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.ServerStatus
import dev.magnor.kompakt.domain.SyncCursorValue
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskDraft
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.domain.TaskPatch
import dev.magnor.kompakt.domain.TaskStatus
import dev.magnor.kompakt.domain.TodayProjection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * In-memory fakes implementing every repository interface. They honor the
 * sync contract (revisions, idempotency, tombstones via [FakeStore]) so the
 * Phase 3 HTTP implementations only swap the plumbing, not the semantics.
 */

class FakeChatRepository(
    private val threads: FakeStore<ChatThread>,
    private val messages: FakeStore<Message>,
    private val idempotency: IdempotencyRegistry,
    private val nextId: () -> EntityId,
    private val now: () -> Instant,
) : ChatRepository {

    override fun observeThreads(): Flow<List<ChatThread>> =
        threads.observeAll(compareByDescending { it.updatedAt })

    override fun observeThread(id: EntityId): Flow<ChatThread?> = threads.observe(id)

    override fun observeMessages(chatId: EntityId): Flow<List<Message>> =
        messages.observeAll(compareBy { it.createdAt }).map { list ->
            list.filter { it.chatId == chatId }
        }

    override suspend fun getThread(id: EntityId): ChatThread? = threads.get(id)

    override suspend fun createThread(draft: ChatThreadDraft, requestId: RequestId): ChatThread =
        idempotency.once(requestId) {
            threads.create(
                ChatThread(
                    id = nextId(),
                    title = draft.title,
                    createdAt = now(),
                    updatedAt = now(),
                    projectId = draft.projectId,
                    isTemporary = draft.isTemporary,
                ),
            )
        }

    override suspend fun sendMessage(chatId: EntityId, text: String, requestId: RequestId): Message =
        idempotency.once(requestId) {
            require(threads.get(chatId) != null) { "chat '$chatId' not found" }
            // Lifecycle: PENDING creation → SENT acknowledgement (protocol §17 shape).
            val pending = messages.create(
                Message(
                    id = nextId(),
                    chatId = chatId,
                    role = MessageRole.USER,
                    content = text,
                    createdAt = now(),
                    updatedAt = now(),
                    status = MessageStatus.PENDING,
                ),
            )
            val sent = messages.mutate(pending.id, pending.revision) {
                it.copy(
                    status = MessageStatus.SENT,
                    revision = it.revision + 1,
                    updatedAt = now(),
                )
            }
            threads.get(chatId)?.let { thread ->
                threads.mutate(thread.id, thread.revision) {
                    it.copy(
                        lastMessagePreview = text.take(80),
                        revision = it.revision + 1,
                        updatedAt = now(),
                    )
                }
            }
            sent
        }
}

class FakeAgentRepository(
    private val agents: FakeStore<Agent>,
    private val runs: FakeStore<AgentRun>,
    private val idempotency: IdempotencyRegistry,
    private val nextId: () -> EntityId,
    private val now: () -> Instant,
) : AgentRepository {

    override fun observeAgents(): Flow<List<Agent>> =
        agents.observeAll(compareByDescending { it.lastActivity ?: it.updatedAt })

    override fun observeAgent(id: EntityId): Flow<Agent?> = agents.observe(id)

    override fun observeRuns(agentId: EntityId?): Flow<List<AgentRun>> =
        runs.observeAll(compareByDescending { it.startedAt }).map { list ->
            if (agentId == null) list else list.filter { it.agentId == agentId }
        }

    override fun observeRun(id: EntityId): Flow<AgentRun?> = runs.observe(id)

    override suspend fun getAgent(id: EntityId): Agent? = agents.get(id)

    override suspend fun getRun(id: EntityId): AgentRun? = runs.get(id)

    override suspend fun requestRun(
        agentId: EntityId,
        objective: String,
        requestId: RequestId,
    ): AgentRun = idempotency.once(requestId) {
        require(agents.get(agentId) != null) { "agent '$agentId' not found" }
        runs.create(
            AgentRun(
                id = nextId(),
                agentId = agentId,
                title = objective.take(60),
                objective = objective,
                status = AgentRunStatus.QUEUED,
                startedAt = now(),
                updatedAt = now(),
            ),
        )
    }

    override suspend fun actOnRun(
        runId: EntityId,
        action: ActionType,
        requestId: RequestId,
    ): AgentRun = idempotency.once(requestId) {
        val current = runs.get(runId) ?: throw NoSuchElementException("run '$runId' not found")
        val nextStatus = when (action) {
            ActionType.APPROVE -> AgentRunStatus.RUNNING
            ActionType.REJECT, ActionType.STOP -> AgentRunStatus.STOPPED
            ActionType.RETRY -> AgentRunStatus.QUEUED
            ActionType.ARCHIVE -> current.status
            else -> throw CaptureRejectedException("action '${action.wire}' not valid on agent runs")
        }
        runs.mutate(runId, current.revision) {
            it.copy(
                status = nextStatus,
                requiresInput = false,
                archived = it.archived || action == ActionType.ARCHIVE,
                revision = it.revision + 1,
                updatedAt = now(),
            )
        }
    }
}

class FakeTaskRepository(
    private val tasks: FakeStore<Task>,
    private val idempotency: IdempotencyRegistry,
    private val nextId: () -> EntityId,
    private val now: () -> Instant,
) : TaskRepository {

    override fun observeTasks(filter: TaskFilter): Flow<List<Task>> =
        tasks.observeAll(compareBy { it.dueAt ?: Instant.DISTANT_FUTURE }).map { list ->
            when (filter) {
                is TaskFilter.All -> list
                is TaskFilter.Today -> list.filter {
                    it.status == TaskStatus.OPEN &&
                        it.dueAt != null &&
                        it.dueAt!! < endOfDay(filter.at)
                }
                is TaskFilter.ByProject -> list.filter { it.projectId == filter.projectId }
                is TaskFilter.ByArea -> list.filter { it.areaId == filter.areaId }
            }
        }

    override fun observeTask(id: EntityId): Flow<Task?> = tasks.observe(id)

    override suspend fun getTask(id: EntityId): Task? = tasks.get(id)

    override suspend fun createTask(draft: TaskDraft, requestId: RequestId): Task =
        idempotency.once(requestId) {
            tasks.create(
                Task(
                    id = nextId(),
                    title = draft.title,
                    dueAt = draft.dueAt,
                    projectId = draft.projectId,
                    areaId = draft.areaId,
                    sourceType = draft.sourceType,
                    sourceId = draft.sourceId,
                    updatedAt = now(),
                ),
            )
        }

    override suspend fun completeTask(
        id: EntityId,
        expectedRevision: Long,
        requestId: RequestId,
    ): Task = idempotency.once(requestId) {
        tasks.mutate(id, expectedRevision) {
            it.copy(
                status = TaskStatus.COMPLETED,
                revision = it.revision + 1,
                updatedAt = now(),
            )
        }
    }

    override suspend fun postponeTask(
        id: EntityId,
        expectedRevision: Long,
        newDueAt: Instant?,
        requestId: RequestId,
    ): Task = idempotency.once(requestId) {
        tasks.mutate(id, expectedRevision) {
            it.copy(dueAt = newDueAt, revision = it.revision + 1, updatedAt = now())
        }
    }

    override suspend fun updateTask(
        id: EntityId,
        expectedRevision: Long,
        patch: TaskPatch,
        requestId: RequestId,
    ): Task = idempotency.once(requestId) {
        tasks.mutate(id, expectedRevision) {
            it.copy(
                title = patch.title ?: it.title,
                dueAt = patch.dueAt ?: it.dueAt,
                status = patch.status ?: it.status,
                projectId = patch.projectId ?: it.projectId,
                revision = it.revision + 1,
                updatedAt = now(),
            )
        }
    }

    private fun endOfDay(at: Instant): Instant =
        LocalDate.fromEpochDays(at.toLocalDateTime(TimeZone.UTC).date.toEpochDays() + 1)
            .atStartOfDayIn(TimeZone.UTC)
}

class FakeNoteRepository(
    private val notes: FakeStore<Note>,
    private val idempotency: IdempotencyRegistry,
    private val nextId: () -> EntityId,
    private val now: () -> Instant,
) : NoteRepository {

    override fun observeNotes(projectId: EntityId?, areaId: EntityId?): Flow<List<Note>> =
        notes.observeAll(compareByDescending { it.updatedAt }).map { list ->
            list.filter { (projectId == null || it.projectId == projectId) &&
                (areaId == null || it.areaId == areaId) }
        }

    override fun observeNote(id: EntityId): Flow<Note?> = notes.observe(id)

    override suspend fun getNote(id: EntityId): Note? = notes.get(id)

    override suspend fun createNote(draft: NoteDraft, requestId: RequestId): Note =
        idempotency.once(requestId) {
            notes.create(
                Note(
                    id = nextId(),
                    text = draft.text,
                    createdAt = now(),
                    updatedAt = now(),
                    projectId = draft.projectId,
                    areaId = draft.areaId,
                    sourceType = draft.sourceType,
                    sourceId = draft.sourceId,
                ),
            )
        }
}

class FakeOrganizationRepository(
    projects: FakeStore<Project>,
    areas: FakeStore<Area>,
) : OrganizationRepository {

    private val projectStore = projects
    private val areaStore = areas

    override fun observeProjects(): Flow<List<Project>> =
        projectStore.observeAll(compareBy { it.name })

    override fun observeProject(id: EntityId): Flow<Project?> = projectStore.observe(id)

    override fun observeAreas(): Flow<List<Area>> =
        areaStore.observeAll(compareBy { it.name })

    override fun observeArea(id: EntityId): Flow<Area?> = areaStore.observe(id)
}

class FakeInboxRepository(
    private val items: FakeStore<InboxItem>,
    private val idempotency: IdempotencyRegistry,
) : InboxRepository {

    override fun observeInbox(): Flow<List<InboxItem>> =
        items.observeAll(compareByDescending { it.timestamp })

    override suspend fun dismiss(id: EntityId, expectedRevision: Long, requestId: RequestId) {
        idempotency.once(requestId) { items.delete(id) }
    }
}

class FakeTodayRepository(
    private val events: List<dev.magnor.kompakt.domain.CalendarEvent>,
    private val tasks: TaskRepository,
    private val inbox: InboxRepository,
    private val agents: AgentRepository,
    private val notes: NoteRepository,
    private val now: () -> Instant,
) : TodayRepository {

    override fun observeToday(): Flow<TodayProjection> = combine(
        tasks.observeTasks(),
        inbox.observeInbox(),
        agents.observeRuns(),
        notes.observeNotes(),
    ) { taskList, inboxItems, runs, noteList ->
        val instant = now()
        val today = instant.toLocalDateTime(TimeZone.UTC).date
        val endOfDay = LocalDate.fromEpochDays(today.toEpochDays() + 1).atStartOfDayIn(TimeZone.UTC)

        TodayProjection(
            date = today,
            events = events
                .filter { it.startAt.toLocalDateTime(TimeZone.UTC).date == today }
                .sortedBy { it.startAt },
            tasks = taskList
                .filter { it.status == TaskStatus.OPEN && it.dueAt != null && it.dueAt!! < endOfDay }
                .sortedBy { it.dueAt },
            attention = inboxItems
                .filter { it.priority != dev.magnor.kompakt.domain.InboxPriority.LOW }
                .sortedByDescending { it.timestamp },
            agentActivity = runs
                .filter { it.status == AgentRunStatus.RUNNING ||
                    it.status == AgentRunStatus.WAITING_FOR_INPUT }
                .sortedByDescending { it.updatedAt },
            recentNote = noteList.maxByOrNull { it.updatedAt },
        )
    }

    override suspend fun today(): TodayProjection = observeToday().first()
}

class FakeCaptureRepository(
    private val tasks: FakeTaskRepository,
    private val notes: FakeNoteRepository,
    private val chats: FakeChatRepository,
    private val agents: FakeAgentRepository,
    private val idempotency: IdempotencyRegistry,
    private val now: () -> Instant,
) : CaptureRepository {

    /**
     * Placeholder server-side interpretation: verb-heuristic for tasks,
     * "tomorrow" → due date. The real interpretation is server work in
     * Phase 6; the semantic contract (propose, user confirms) is fixed here.
     */
    override suspend fun interpret(input: String): CaptureProposal {
        val text = input.trim().trimEnd('.')
        val lower = text.lowercase()
        val isTask = TASK_HINTS.any { lower.startsWith(it) || lower.contains(" $it") }
        val tomorrow = lower.contains("tomorrow")
        return CaptureProposal(
            proposedType = if (isTask) CaptureType.TASK else CaptureType.NOTE,
            title = text.take(80),
            text = text,
            dueAt = if (isTask && tomorrow) {
                Instant.fromEpochSeconds(now().epochSeconds + 86_400)
            } else {
                null
            },
        )
    }

    override suspend fun commit(proposal: CaptureProposal, requestId: RequestId): CaptureResult =
        idempotency.once(requestId) {
            when (proposal.proposedType) {
                CaptureType.TASK -> CaptureResult.TaskCreated(
                    tasks.createTask(
                        TaskDraft(
                            title = proposal.title,
                            dueAt = proposal.dueAt,
                            projectId = proposal.projectId,
                            areaId = proposal.areaId,
                        ),
                        requestId,
                    ),
                )
                CaptureType.NOTE -> CaptureResult.NoteCreated(
                    notes.createNote(
                        NoteDraft(
                            text = proposal.text ?: proposal.title,
                            projectId = proposal.projectId,
                            areaId = proposal.areaId,
                        ),
                        requestId,
                    ),
                )
                CaptureType.CHAT -> CaptureResult.ChatCreated(
                    chats.createThread(ChatThreadDraft(title = proposal.title), requestId),
                )
                CaptureType.AGENT_REQUEST -> CaptureResult.AgentRequested(
                    agents.requestRun(ADHOC_AGENT_ID, proposal.title, requestId),
                )
                CaptureType.UNKNOWN -> throw CaptureRejectedException("unknown capture type")
            }
        }

    companion object {
        /** Job Search agent hosts ad-hoc requests in the fake world. */
        const val ADHOC_AGENT_ID = "agent_002"

        private val TASK_HINTS = listOf(
            "call ", "buy ", "send ", "review ", "fix ", "renew ", "book ",
            "submit ", "remind", "check ", "pay ", "email ", "write ", "order ",
        )
    }
}

class FakeSyncRepository(
    private val changeLog: FakeChangeLog,
    private val lastSync: MutableStateFlow<Instant?>,
    private val now: () -> Instant,
) : SyncRepository {

    override suspend fun capabilities(): CapabilitySet = CapabilitySet(
        serverProtocol = 1,
        minimumClientProtocol = 1,
        features = mapOf(
            "today" to true,
            "chat" to true,
            "agents" to true,
            "projects" to true,
            "areas" to true,
            "tasks" to true,
            "notes" to true,
            "inbox" to true,
            "offline_capture" to true,
            "voice_capture" to false,
        ),
    )

    override suspend fun status(): ServerStatus =
        ServerStatus(healthy = true, serverTime = now(), version = "fake-0.1")

    override suspend fun changesSince(cursor: SyncCursorValue?): ChangePage =
        changeLog.page(cursor)

    override fun observeLastSync(): Flow<Instant?> = lastSync

    override suspend fun markSynced(requestId: RequestId) {
        lastSync.value = now()
    }
}
