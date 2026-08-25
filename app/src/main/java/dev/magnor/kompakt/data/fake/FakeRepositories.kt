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
import dev.magnor.kompakt.data.repository.TopicRepository
import dev.magnor.kompakt.data.repository.WorkspaceRepository
import dev.magnor.kompakt.domain.AgentBackendInfo
import dev.magnor.kompakt.domain.AgentCommand
import dev.magnor.kompakt.domain.AgentDispatchDraft
import dev.magnor.kompakt.domain.AgentEvent
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunKind
import dev.magnor.kompakt.domain.AgentRunResult
import dev.magnor.kompakt.domain.AgentRunState
import dev.magnor.kompakt.domain.AgentsSurface
import dev.magnor.kompakt.domain.RepositoryException
import dev.magnor.kompakt.domain.NoteConflictException
import dev.magnor.kompakt.domain.SteerOutcome
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.CaptureRejectedException
import dev.magnor.kompakt.domain.CaptureResult
import dev.magnor.kompakt.domain.CaptureType
import dev.magnor.kompakt.domain.CapabilitySet
import dev.magnor.kompakt.domain.ChangePage
import dev.magnor.kompakt.domain.ChatExchange
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.ChatTopic
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
import dev.magnor.kompakt.domain.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** T-022d: label lookup for scope changes (server computes labels). */
    private val topics: List<ChatTopic> = emptyList(),
    private val workspaces: List<Workspace> = emptyList(),
) : ChatRepository {

    private fun labelFor(type: String?, ref: String?): String? = when (type) {
        "topic" -> topics.firstOrNull { it.id == ref }?.label ?: ref
        "workspace" -> workspaces.firstOrNull { it.ref == ref }?.label ?: ref
        else -> null
    }

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
                    scopeType = draft.scopeType,
                    scopeRef = draft.scopeRef,
                    scopeLabel = labelFor(draft.scopeType, draft.scopeRef),
                ),
            )
        }

    override suspend fun sendMessage(chatId: EntityId, text: String, requestId: RequestId): ChatExchange =
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
            // Demo stand-in for the server-side LLM reply (real one arrives
            // via the coordinator's Hermes proxy in live mode).
            val reply = messages.create(
                Message(
                    id = nextId(),
                    chatId = chatId,
                    role = MessageRole.ASSISTANT,
                    content = "Demo reply — enroll and connect for real answers.",
                    createdAt = now(),
                    updatedAt = now(),
                ),
            )
            threads.get(chatId)?.let { thread ->
                threads.mutate(thread.id, thread.revision) {
                    it.copy(
                        lastMessagePreview = text.take(80),
                        revision = it.revision + 1,
                        updatedAt = now(),
                    )
                }
            }
            // T-022d: unscoped sends propose a topic for the chip — a demo of
            // the server's deterministic matcher (never auto-applied).
            val proposal = if (threads.get(chatId)?.scopeType == null) {
                topics.firstOrNull { t ->
                    text.contains(t.id, ignoreCase = true) ||
                        text.contains(t.label, ignoreCase = true)
                }
            } else {
                null
            }
            ChatExchange(user = sent, assistant = reply, proposedTopic = proposal)
        }

    override suspend fun truncate(chatId: EntityId, keepThrough: EntityId?, requestId: RequestId) =
        idempotency.once(requestId) {
            require(threads.get(chatId) != null) { "chat '$chatId' not found" }
            val ordered = messages.observeAll(compareBy { it.createdAt })
                .first()
                .filter { it.chatId == chatId }
            if (keepThrough == null) {
                ordered.forEach { messages.delete(it.id) }
            } else {
                val anchorIndex = ordered.indexOfFirst { it.id == keepThrough }
                require(anchorIndex >= 0) { "keep_through message not found" }
                ordered.drop(anchorIndex + 1).forEach { messages.delete(it.id) }
            }
            threads.get(chatId)?.let { thread ->
                threads.mutate(thread.id, thread.revision) {
                    it.copy(revision = it.revision + 1, updatedAt = now())
                }
            }
            Unit
        }

    override suspend fun setScope(
        chatId: EntityId,
        scopeType: String?,
        scopeRef: String?,
        requestId: RequestId,
    ): ChatThread = idempotency.once(requestId) {
        val thread = threads.get(chatId) ?: throw IllegalArgumentException("chat '$chatId' not found")
        val updated = threads.mutate(thread.id, thread.revision) {
            it.copy(
                scopeType = scopeType,
                scopeRef = scopeRef,
                scopeLabel = labelFor(scopeType, scopeRef),
                revision = it.revision + 1,
                updatedAt = now(),
            )
        }
        updated
    }
}

class FakeAgentRepository(
    private val surface: MutableStateFlow<AgentsSurface>,
    private val runs: MutableStateFlow<List<AgentRun>>,
    private val idempotency: IdempotencyRegistry,
    private val nextId: (String) -> EntityId,
    private val now: () -> Instant,
) : AgentRepository {

    /**
     * Runs are LIVE VIEWS over backends (V-052), not sync entities: no
     * FakeStore, no revisions, no change-log — ids are backend-native
     * (`run_…` atomic / `ses_…` session), matching the wire contract.
     */
    private val events = mutableMapOf<String, MutableList<AgentEvent>>()

    override fun observeSurface(): Flow<AgentsSurface> = surface

    override fun observeRuns(): Flow<List<AgentRun>> = runs.map { list ->
        list.sortedByDescending { it.updatedAt ?: Instant.DISTANT_PAST }
    }

    override fun observeRun(id: String): Flow<AgentRun?> = runs.map { list ->
        list.firstOrNull { it.id == id }
    }

    override suspend fun dispatch(draft: AgentDispatchDraft, requestId: RequestId): AgentRun =
        idempotency.once(requestId) {
            val backend = draft.backend ?: surface.value.defaultBackend
                ?: throw RepositoryException("no backend configured")
            val info = surface.value.backends[backend]
                ?: throw RepositoryException("unknown backend '$backend'")
            if (info.projectRegistration && draft.projectRef.isNullOrBlank()) {
                throw RepositoryException("backend '$backend' requires a project ref")
            }
            val kind = if (info.resumable) AgentRunKind.SESSION else AgentRunKind.RUN
            val run = AgentRun(
                id = nextId(if (kind == AgentRunKind.SESSION) "ses" else "run"),
                backend = backend,
                kind = kind,
                agent = draft.agent ?: "",
                state = AgentRunState.QUEUED,
                projectRef = draft.projectRef,
                title = draft.prompt.take(60),
                prompt = draft.prompt,
                createdAt = now(),
                updatedAt = now(),
            )
            appendEvent(run.id, "dispatched", draft.prompt)
            runs.value = runs.value + run
            run
        }

    override suspend fun send(runId: String, message: String, requestId: RequestId): AgentRun =
        idempotency.once(requestId) {
            val run = find(runId)
            if (run.kind != AgentRunKind.SESSION) {
                throw RepositoryException("'$runId' is not a session — cannot send")
            }
            if (run.state.isTerminal) {
                throw RepositoryException("'$runId' already ${run.state.wire}")
            }
            appendEvent(runId, "message", message)
            mutate(run) { it.copy(state = AgentRunState.RUNNING, updatedAt = now()) }
        }

    override suspend fun steer(runId: String, message: String, requestId: RequestId): SteerOutcome =
        idempotency.once(requestId) {
            val run = find(runId)
            // Capability-honest: mirror the real backends — steering is only
            // "delivered" when the backend advertises live steering.
            if (surface.value.backends[run.backend]?.liveSteering == true) {
                appendEvent(runId, "steer", message)
                SteerOutcome.DELIVERED
            } else {
                SteerOutcome.UNSUPPORTED
            }
        }

    override suspend fun cancel(runId: String, requestId: RequestId) {
        idempotency.once(requestId) {
            val run = find(runId)
            if (!run.state.isTerminal) {
                appendEvent(runId, "cancelled", null)
                mutate(run) { it.copy(state = AgentRunState.CANCELLED, updatedAt = now()) }
            }
        }
    }

    override suspend fun result(runId: String): AgentRunResult? {
        val run = runs.value.firstOrNull { it.id == runId } ?: return null
        if (run.state != AgentRunState.SUCCEEDED) return null
        return AgentRunResult(
            outcome = run.state,
            summary = run.resultSummary,
            commitRefs = if (run.backend == "warren") listOf("abc1234") else emptyList(),
            tokensIn = run.tokensIn,
            tokensOut = run.tokensOut,
        )
    }

    override suspend fun events(runId: String, since: Long): List<AgentEvent> =
        events[runId].orEmpty().filter { it.seq > since }.sortedBy { it.seq }

    override suspend fun commands(backend: String): List<AgentCommand> =
        if (surface.value.backends[backend]?.commands == true) {
            listOf(
                AgentCommand(name = "test", description = "Run the test suite"),
                AgentCommand(name = "compact", description = "Summarize and compact context"),
            )
        } else {
            emptyList()
        }

    override suspend fun runCommand(runId: String, command: String, arguments: String, requestId: RequestId): AgentRun =
        idempotency.once(requestId) {
            val run = find(runId)
            if (run.kind != AgentRunKind.SESSION) {
                throw RepositoryException("commands run inside sessions only")
            }
            if (surface.value.backends[run.backend]?.commands != true) {
                throw RepositoryException("backend '${run.backend}' has no commands")
            }
            appendEvent(runId, "command", "/$command $arguments".trim())
            mutate(run) { it.copy(updatedAt = now()) }
        }

    private fun find(runId: String): AgentRun = runs.value.firstOrNull { it.id == runId }
        ?: throw NoSuchElementException("run '$runId' not found")

    private fun mutate(run: AgentRun, transform: (AgentRun) -> AgentRun): AgentRun {
        val next = transform(run)
        runs.value = runs.value.map { if (it.id == run.id) next else it }
        return next
    }

    private fun appendEvent(runId: String, kind: String, text: String?) {
        val list = events.getOrPut(runId) { mutableListOf() }
        list += AgentEvent(seq = list.size.toLong(), kind = kind, text = text)
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
                    title = draft.text.lineSequence().firstOrNull { it.isNotBlank() },
                    role = Note.ROLE_INBOX,
                    checksum = "fake-ck-${nextId()}",
                    createdAt = now(),
                    updatedAt = now(),
                    projectId = draft.projectId,
                    areaId = draft.areaId,
                    sourceType = draft.sourceType,
                    sourceId = draft.sourceId,
                ),
            )
        }

    override suspend fun updateNote(id: EntityId, text: String, expectedChecksum: String): Note {
        val current = notes.get(id)
            ?: throw RepositoryException("note not found: $id")
        val currentChecksum = current.checksum
        if (currentChecksum != null && currentChecksum != expectedChecksum) {
            throw NoteConflictException(fresh = current)
        }
        return notes.mutate(id, current.revision) {
            it.copy(
                text = text,
                checksum = "fake-ck-${nextId()}",
                updatedAt = now(),
                revision = it.revision + 1,
            )
        }
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
                .filter { it.state == AgentRunState.RUNNING ||
                    it.state == AgentRunState.WAITING_FOR_INPUT }
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
                    agents.dispatch(
                        AgentDispatchDraft(
                            prompt = proposal.text ?: proposal.title,
                            backend = agents.observeSurface().first().defaultBackend,
                        ),
                        "$requestId#run",
                    ),
                )
                CaptureType.UNKNOWN -> throw CaptureRejectedException("unknown capture type")
            }
        }

    companion object {
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

/** T-022c: demo workspaces come straight from FakeData — a read-only surface. */
class FakeWorkspaceRepository(
    private val workspaces: StateFlow<List<Workspace>>,
) : WorkspaceRepository {
    override fun observeWorkspaces(): Flow<List<Workspace>> = workspaces
}

/** T-022d: demo topics come straight from FakeData — a read-only surface. */
class FakeTopicRepository(
    private val topics: StateFlow<List<ChatTopic>>,
) : TopicRepository {
    override fun observeTopics(): Flow<List<ChatTopic>> = topics
}
