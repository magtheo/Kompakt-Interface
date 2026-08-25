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
import dev.magnor.kompakt.data.repository.TopicRepository
import dev.magnor.kompakt.data.repository.WorkspaceRepository
import dev.magnor.kompakt.domain.AgentCommand
import dev.magnor.kompakt.domain.AgentDispatchDraft
import dev.magnor.kompakt.domain.AgentEvent
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunResult
import dev.magnor.kompakt.domain.AgentsSurface
import dev.magnor.kompakt.domain.SteerOutcome
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.CapabilitySet
import dev.magnor.kompakt.domain.ChangePage
import dev.magnor.kompakt.domain.ChatExchange
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.ChatTopic
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
import dev.magnor.kompakt.domain.Workspace
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

    @Serializable
    private data class NoteEnvelope(val note: Note? = null)

    @Serializable
    private data class CreateBody(
        @SerialName("request_id") val requestId: RequestId,
        val title: String? = null,
        val text: String,
        @SerialName("source_type") val sourceType: String? = null,
        @SerialName("source_id") val sourceId: String? = null,
    )

    @Serializable
    private data class UpdateBody(
        val text: String,
        @SerialName("expected_checksum") val expectedChecksum: String,
    )

    override fun observeNotes(projectId: EntityId?, areaId: EntityId?): Flow<List<Note>> = flow {
        val query = buildMap {
            if (projectId != null) put("project_id", projectId)
            if (areaId != null) put("area_id", areaId)
        }
        emit(api.decodeList<Note>("/v1/notes", "notes", query))
    }

    override fun observeNote(id: EntityId): Flow<Note?> = flow { emit(getNote(id)) }

    override suspend fun getNote(id: EntityId): Note? = try {
        api.decode<NoteEnvelope>("/v1/notes/$id").note
    } catch (_: RepositoryException) {
        null // 404 and other rejects → absent (contract: repository returns null)
    }

    override suspend fun createNote(draft: NoteDraft, requestId: RequestId): Note {
        // Title: first line of the text (server derives when blank/omitted).
        val firstLine = draft.text.lineSequence().firstOrNull { it.isNotBlank() }
        val body = CreateBody(
            requestId = requestId,
            title = firstLine,
            text = draft.text,
            sourceType = draft.sourceType?.wire,
            sourceId = draft.sourceId,
        )
        val envelope = KompaktJson.decodeFromString<NoteEnvelope>(
            api.post(
                "/v1/notes",
                KompaktJson.encodeToString(body),
                requestId,
            ),
        )
        return envelope.note ?: throw RepositoryException("server created note without body")
    }

    override suspend fun updateNote(id: EntityId, text: String, expectedChecksum: String): Note {
        val body = UpdateBody(text = text, expectedChecksum = expectedChecksum)
        val envelope = KompaktJson.decodeFromString<NoteEnvelope>(
            api.put("/v1/notes/$id", KompaktJson.encodeToString(body)),
        )
        return envelope.note ?: throw RepositoryException("server updated note without body")
    }
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
    override suspend fun dismiss(id: EntityId, expectedRevision: Long, requestId: RequestId) {
        // V-057 read endpoint; derived alerts 404 → caller treats as no-op.
        api.post("/v1/inbox/$id/read", "{}", requestId)
    }
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
            // T-022d: both null = general chat (fields omitted — encodeDefaults=false).
            @SerialName("scope_type") val scopeType: String? = null,
            @SerialName("scope_ref") val scopeRef: String? = null,
        )
        val body = CreateBody(
            requestId, draft.title, draft.projectId, draft.isTemporary,
            draft.scopeType, draft.scopeRef,
        )
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
        return ChatExchange(
            user = envelope.message,
            assistant = envelope.assistantMessage,
            proposedTopic = envelope.proposedTopic,
            workspaceState = envelope.workspace?.state,
        )
    }

    override suspend fun truncate(chatId: EntityId, keepThrough: EntityId?, requestId: RequestId) {
        @Serializable
        data class TruncateBody(
            @SerialName("request_id") val requestId: RequestId,
            @SerialName("keep_through") val keepThrough: EntityId? = null,
        )
        // kept/deleted counts are informational; errors surface as exceptions.
        api.post(
            "/v1/chats/$chatId/truncate",
            KompaktJson.encodeToString(TruncateBody(requestId, keepThrough)),
            requestId,
        )
    }

    override suspend fun setScope(
        chatId: EntityId,
        scopeType: String?,
        scopeRef: String?,
        requestId: RequestId,
    ): ChatThread {
        @Serializable
        data class ScopeBody(
            @SerialName("request_id") val requestId: RequestId,
            @SerialName("scope_type") val scopeType: String? = null,
            @SerialName("scope_ref") val scopeRef: String? = null,
        )
        return api.post(
            "/v1/chats/$chatId/scope",
            KompaktJson.encodeToString(ScopeBody(requestId, scopeType, scopeRef)),
            requestId,
        ).let { KompaktJson.decodeFromString<ChatEnvelope>(it).chat }
    }

    @Serializable
    private data class ChatEnvelope(val chat: ChatThread)

    @Serializable
    private data class SendEnvelope(
        val message: Message,
        @SerialName("assistant_message") val assistantMessage: Message? = null,
        // T-022d: proposal for the chip — unscoped threads only, never applied server-side.
        @SerialName("proposed_topic") val proposedTopic: ChatTopic? = null,
        // Workspace tier outcome; execution_id/committed stay undecoded (UI needs neither).
        val workspace: WorkspaceOutcomeWire? = null,
    )

    @Serializable
    private data class WorkspaceOutcomeWire(val state: String)
}

class RemoteAgentRepository(private val api: HttpApi) : AgentRepository {
    override fun observeSurface(): Flow<AgentsSurface> = flow {
        emit(api.decode("/v1/agents"))
    }

    override fun observeRuns(): Flow<List<AgentRun>> = flow {
        emit(api.decodeList("/v1/agent-runs", "agent_runs"))
    }

    override fun observeRun(id: String): Flow<AgentRun?> = observeRuns().map { list ->
        list.firstOrNull { it.id == id }
    }

    override suspend fun dispatch(draft: AgentDispatchDraft, requestId: RequestId): AgentRun {
        @Serializable
        data class DispatchBody(
            val prompt: String,
            val backend: String? = null,
            val agent: String? = null,
            @SerialName("project_ref") val projectRef: String? = null,
        )
        val body = DispatchBody(draft.prompt, draft.backend, draft.agent, draft.projectRef)
        return api.post("/v1/agents/dispatch", KompaktJson.encodeToString(body))
            .let { KompaktJson.decodeFromString<RunEnvelope>(it).agentRun }
    }

    override suspend fun send(runId: String, message: String, requestId: RequestId): AgentRun {
        @Serializable
        data class SendBody(val message: String)
        val body = SendBody(message)
        return api.post("/v1/agent-runs/$runId/send", KompaktJson.encodeToString(body))
            .let { KompaktJson.decodeFromString<RunEnvelope>(it).agentRun }
    }

    override suspend fun steer(runId: String, message: String, requestId: RequestId): SteerOutcome {
        @Serializable
        data class SteerBody(val message: String)
        @Serializable
        data class SteerEnvelope(val outcome: SteerOutcome)
        return api.post("/v1/agent-runs/$runId/steer", KompaktJson.encodeToString(SteerBody(message)))
            .let { KompaktJson.decodeFromString<SteerEnvelope>(it).outcome }
    }

    override suspend fun cancel(runId: String, requestId: RequestId) {
        @Serializable
        data class CancelBody(val reason: String? = null)
        api.post("/v1/agent-runs/$runId/cancel", KompaktJson.encodeToString(CancelBody()))
    }

    override suspend fun result(runId: String): AgentRunResult? = try {
        @Serializable
        data class ResultEnvelope(val result: AgentRunResult)
        api.decode<ResultEnvelope>("/v1/agent-runs/$runId/result").result
    } catch (_: RepositoryException) {
        null // no result yet / 404 → absent
    }

    override suspend fun events(runId: String, since: Long): List<AgentEvent> {
        @Serializable
        data class EventDto(val seq: Long, val kind: String, val payload: JsonObject) {
            fun toDomain(): AgentEvent = AgentEvent(
                seq = seq,
                kind = kind,
                // Wire truth: both adapters emit kind="message" with the
                // sender in payload.role (opencode today; warren can add it
                // without a wire change). Kind stays as-is for non-message
                // rows (state_change/error).
                role = payload["role"]?.jsonPrimitive?.contentOrNull,
                text = listOf("text", "message", "summary")
                    .firstNotNullOfOrNull { payload[it]?.jsonPrimitive?.contentOrNull }
                    ?.takeIf { it.isNotBlank() },
            )
        }
        @Serializable
        data class EventsEnvelope(val events: List<EventDto>)
        return api.decode<EventsEnvelope>("/v1/agent-runs/$runId/events", query = mapOf("since" to since.toString()))
            .events.map { it.toDomain() }
    }

    override suspend fun commands(backend: String): List<AgentCommand> = try {
        @Serializable
        data class CommandsEnvelope(val commands: List<AgentCommand>)
        api.decode<CommandsEnvelope>("/v1/agents/commands", query = mapOf("backend" to backend)).commands
    } catch (_: RepositoryException) {
        emptyList() // commandless backend → 400 → none
    }

    override suspend fun runCommand(runId: String, command: String, arguments: String, requestId: RequestId): AgentRun {
        @Serializable
        data class CommandBody(val command: String, val arguments: String)
        val body = CommandBody(command, arguments)
        return api.post("/v1/agent-runs/$runId/command", KompaktJson.encodeToString(body))
            .let { KompaktJson.decodeFromString<RunEnvelope>(it).agentRun }
    }

    @Serializable
    private data class RunEnvelope(@SerialName("agent_run") val agentRun: AgentRun)
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

/** T-022c: workspace registry read — reference data, {ref, label} only. */
class RemoteWorkspaceRepository(private val api: HttpApi) : WorkspaceRepository {
    override fun observeWorkspaces(): Flow<List<Workspace>> = flow {
        emit(api.decodeList("/v1/workspaces", "workspaces"))
    }
}

/** T-022d: topic registry read — the notes sorter's buckets, {id, label} only. */
class RemoteTopicRepository(private val api: HttpApi) : TopicRepository {
    override fun observeTopics(): Flow<List<ChatTopic>> = flow {
        emit(api.decodeList("/v1/chat/topics", "topics"))
    }
}
