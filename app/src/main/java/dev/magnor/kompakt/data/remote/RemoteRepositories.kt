package dev.magnor.kompakt.data.remote

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
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.CapabilitySet
import dev.magnor.kompakt.domain.ChangePage
import dev.magnor.kompakt.domain.ChatExchange
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.CaptureResult
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.RepositoryException
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.ServerStatus
import dev.magnor.kompakt.domain.SyncCursorValue
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskDraft
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.domain.TaskPatch
import dev.magnor.kompakt.domain.TodayProjection
import dev.magnor.kompakt.domain.KompaktJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * HTTP-backed repositories against the /v1/ contract (T-004).
 *
 * Reads are live. observe* flows fetch once per collection — an e-ink,
 * single-user client refreshes on navigation, which the protocol treats
 * as acceptable while cursor sync (D011) is the correctness mechanism.
 *
 * Writes throw until the Phase 6 capture/write pipeline lands: the server
 * has no authorized mutation path yet, and pretending otherwise would
 * violate "security is server-enforced" (docs/security.md).
 */

private fun writesLandInPhase6(what: String): Nothing =
    throw RepositoryException("$what is not available yet — server writes land in Phase 6 (capture pipeline)")

class RemoteSyncRepository(private val api: HttpApi) : SyncRepository {
    override suspend fun capabilities(): CapabilitySet = api.decode("/v1/capabilities")
    override suspend fun status(): ServerStatus = api.decode("/v1/status")
    override suspend fun changesSince(cursor: SyncCursorValue?): ChangePage =
        api.decode("/v1/changes", query = mapOf("since" to cursor))
    private val lastSync = MutableStateFlow<Instant?>(null)
    override fun observeLastSync(): Flow<Instant?> = lastSync
    override suspend fun markSynced(requestId: RequestId) {
        // Client-side bookkeeping: the cursor itself is persisted with the
        // cache in Phase 5. Idempotent by nature (single slot).
        lastSync.value = api.decode<ServerStatus>("/v1/status").serverTime
    }
}

class RemoteTodayRepository(private val api: HttpApi) : TodayRepository {
    override suspend fun today(): TodayProjection = api.decode("/v1/today")
    override fun observeToday(): Flow<TodayProjection> = flow { emit(today()) }
}

class RemoteTaskRepository(private val api: HttpApi) : TaskRepository {
    private suspend fun tasks(filter: TaskFilter): List<Task> = when (filter) {
        is TaskFilter.All -> fetch()
        is TaskFilter.Today -> fetch().filter { it.dueAt != null && it.dueAt <= filter.at }
        is TaskFilter.ByProject -> api.decodeList(
            "/v1/tasks", "tasks", query = mapOf("project_id" to filter.projectId),
        )
        is TaskFilter.ByArea -> api.decodeList(
            "/v1/tasks", "tasks", query = mapOf("area_id" to filter.areaId),
        )
    }

    private suspend fun fetch(): List<Task> = api.decodeList("/v1/tasks", "tasks")

    override fun observeTasks(filter: TaskFilter): Flow<List<Task>> = flow { emit(tasks(filter)) }
    override fun observeTask(id: EntityId): Flow<Task?> = flow { emit(getTask(id)) }
    override suspend fun getTask(id: EntityId): Task? = fetch().firstOrNull { it.id == id }
    override suspend fun createTask(draft: TaskDraft, requestId: RequestId): Task =
        writesLandInPhase6("creating tasks")
    override suspend fun completeTask(id: EntityId, expectedRevision: Long, requestId: RequestId): Task =
        writesLandInPhase6("completing tasks")
    override suspend fun postponeTask(id: EntityId, expectedRevision: Long, newDueAt: Instant?, requestId: RequestId): Task =
        writesLandInPhase6("postponing tasks")
    override suspend fun updateTask(id: EntityId, expectedRevision: Long, patch: TaskPatch, requestId: RequestId): Task =
        writesLandInPhase6("editing tasks")
}

class RemoteNoteRepository(private val api: HttpApi) : NoteRepository {
    override fun observeNotes(projectId: EntityId?, areaId: EntityId?): Flow<List<Note>> = flow {
        emit(api.decodeList("/v1/notes", "notes"))
    }
    override fun observeNote(id: EntityId): Flow<Note?> = flow { emit(getNote(id)) }
    override suspend fun getNote(id: EntityId): Note? = null // empty feed in v0.1
    override suspend fun createNote(draft: NoteDraft, requestId: RequestId): Note =
        writesLandInPhase6("creating notes")
}

class RemoteOrganizationRepository(private val api: HttpApi) : OrganizationRepository {
    override fun observeProjects(): Flow<List<Project>> = flow {
        emit(api.decodeList("/v1/projects", "projects"))
    }
    override fun observeProject(id: EntityId): Flow<Project?> =
        observeProjects().map { list -> list.firstOrNull { it.id == id } }
    override fun observeAreas(): Flow<List<Area>> = flow {
        emit(api.decodeList("/v1/areas", "areas"))
    }
    override fun observeArea(id: EntityId): Flow<Area?> =
        observeAreas().map { list -> list.firstOrNull { it.id == id } }
}

class RemoteInboxRepository(private val api: HttpApi) : InboxRepository {
    override fun observeInbox(): Flow<List<InboxItem>> = flow {
        emit(api.decodeList("/v1/inbox", "inbox_items"))
    }
    override suspend fun dismiss(id: EntityId, expectedRevision: Long, requestId: RequestId) =
        writesLandInPhase6("dismissing inbox items")
}

class RemoteChatRepository(private val api: HttpApi) : ChatRepository {
    override fun observeThreads(): Flow<List<ChatThread>> = flow {
        emit(api.decodeList("/v1/chats", "chats"))
    }
    override fun observeThread(id: EntityId): Flow<ChatThread?> =
        observeThreads().map { list -> list.firstOrNull { it.id == id } }
    override fun observeMessages(chatId: EntityId): Flow<List<Message>> = flow {
        emit(api.decodeList("/v1/chats/$chatId/messages", "messages"))
    }

    override suspend fun getThread(id: EntityId): ChatThread? = try {
        api.decode<ChatEnvelope>("/v1/chats/$id").chat
    } catch (_: RepositoryException) {
        null // 404 and other rejects → absent (contract: repository returns null)
    }

    override suspend fun createThread(draft: ChatThreadDraft, requestId: RequestId): ChatThread {
        @Serializable
        data class CreateBody(
            @SerialName("request_id") val requestId: RequestId,
            val title: String,
            @SerialName("project_id") val projectId: EntityId? = null,
            @SerialName("is_temporary") val isTemporary: Boolean = false,
        )
        val body = CreateBody(requestId, draft.title, draft.projectId, draft.isTemporary)
        return api.post(
            "/v1/chats",
            KompaktJson.encodeToString(body),
            requestId,
        ).let { KompaktJson.decodeFromString<ChatEnvelope>(it).chat }
    }

    override suspend fun sendMessage(chatId: EntityId, text: String, requestId: RequestId): ChatExchange {
        @Serializable
        data class SendBody(
            @SerialName("request_id") val requestId: RequestId,
            val text: String,
        )
        val body = SendBody(requestId, text)
        // Server-side LLM generation may take tens of seconds.
        val envelope = api.post(
            "/v1/chats/$chatId/messages",
            KompaktJson.encodeToString(body),
            requestId,
            timeoutSeconds = 120,
        ).let { KompaktJson.decodeFromString<SendEnvelope>(it) }
        return ChatExchange(user = envelope.message, assistant = envelope.assistantMessage)
    }

    @Serializable
    private data class ChatEnvelope(val chat: ChatThread)

    @Serializable
    private data class SendEnvelope(
        val message: Message,
        @SerialName("assistant_message") val assistantMessage: Message? = null,
    )
}

class RemoteAgentRepository(private val api: HttpApi) : AgentRepository {
    override fun observeAgents(): Flow<List<Agent>> = flow {
        emit(api.decodeList("/v1/agents", "agents"))
    }
    override fun observeAgent(id: EntityId): Flow<Agent?> =
        observeAgents().map { list -> list.firstOrNull { it.id == id } }
    override fun observeRuns(agentId: EntityId?): Flow<List<AgentRun>> = flow {
        emit(api.decodeList("/v1/agent-runs", "agent_runs"))
    }
    override fun observeRun(id: EntityId): Flow<AgentRun?> =
        observeRuns().map { list -> list.firstOrNull { it.id == id } }
    override suspend fun getAgent(id: EntityId): Agent? = null
    override suspend fun getRun(id: EntityId): AgentRun? = null
    override suspend fun requestRun(agentId: EntityId, objective: String, requestId: RequestId): AgentRun =
        writesLandInPhase6("starting agent runs")
    override suspend fun actOnRun(runId: EntityId, action: ActionType, requestId: RequestId): AgentRun =
        writesLandInPhase6("agent actions")
}

// ─── Capture pipeline (Phase 6: interpret → confirm → commit) ──────────

@Serializable
private data class InterpretBody(val text: String)

@Serializable
private data class CaptureCommitBody(
    @SerialName("request_id") val requestId: RequestId,
    @SerialName("proposed_type") val proposedType: String,
    val title: String,
    val text: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("project_id") val projectId: EntityId? = null,
    @SerialName("area_id") val areaId: EntityId? = null,
)

/** Server commit envelope. `kind` discriminates; payload fields are
 * optional so unknown future kinds decode without crashing (protocol §9). */
@Serializable
private data class CaptureCommitResponse(
    val replayed: Boolean = false,
    val kind: String,
    val task: Task? = null,
    val note: NoteCreatedDto? = null,
)

/** Note payloads are minimal until the Notes read phase fixes their wire
 * shape — decode narrowly and map, instead of forcing the full Note DTO. */
@Serializable
private data class NoteCreatedDto(
    val id: EntityId,
    val title: String,
    val revision: Long = 1,
    @SerialName("updated_at") val updatedAt: String,
)

class RemoteCaptureRepository(private val api: HttpApi) : CaptureRepository {
    override suspend fun interpret(input: String): CaptureProposal {
        val body = KompaktJson.encodeToString(InterpretBody(input))
        return KompaktJson.decodeFromString(api.post("/v1/capture/interpret", body))
    }

    override suspend fun commit(proposal: CaptureProposal, requestId: RequestId): CaptureResult {
        val body = CaptureCommitBody(
            requestId = requestId,
            proposedType = proposal.proposedType.wire,
            title = proposal.title,
            text = proposal.text,
            dueAt = proposal.dueAt?.toString(),
            projectId = proposal.projectId,
            areaId = proposal.areaId,
        )
        val response: CaptureCommitResponse = KompaktJson.decodeFromString(
            api.post("/v1/capture/commit", KompaktJson.encodeToString(body), requestId),
        )
        return when (response.kind) {
            "task_created" -> response.task
                ?.let { CaptureResult.TaskCreated(it) }
                ?: throw RepositoryException("server confirmed a task without a task payload")
            "note_created" -> response.note?.let {
                val at = runCatching { Instant.parse(it.updatedAt) }
                    .getOrDefault(Instant.fromEpochSeconds(0))
                CaptureResult.NoteCreated(
                    Note(id = it.id, text = it.title, createdAt = at, updatedAt = at),
                )
            } ?: throw RepositoryException("server confirmed a note without a note payload")
            else -> throw RepositoryException("unknown capture result: ${response.kind}")
        }
    }
}
