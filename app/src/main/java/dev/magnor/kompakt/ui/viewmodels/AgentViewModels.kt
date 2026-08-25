package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.AgentRepository
import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.OrganizationRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.data.repository.WorkspaceRepository
import dev.magnor.kompakt.domain.AgentBackendInfo
import dev.magnor.kompakt.domain.AgentCommand
import dev.magnor.kompakt.domain.AgentDispatchDraft
import dev.magnor.kompakt.domain.AgentEvent
import dev.magnor.kompakt.domain.AgentRole
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunResult
import dev.magnor.kompakt.domain.AgentRunState
import dev.magnor.kompakt.domain.AgentsSurface
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.SteerOutcome
import dev.magnor.kompakt.domain.TaskDraft
import dev.magnor.kompakt.domain.Workspace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Phase 8 (V-052): agents are a process manager over swappable backends.
 * The surface (backends + roles + capabilities) drives what the UI offers —
 * the app never assumes support a backend doesn't advertise.
 */
class AgentsListViewModel(
    agentRepository: AgentRepository,
    val now: Instant,
) : ViewModel() {
    val surface: StateFlow<AgentsSurface> = agentRepository.observeSurface()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentsSurface())

    val runs: StateFlow<List<AgentRun>> = agentRepository.observeRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * One worker role on one backend — a live view, not a stored entity.
 * Dispatch is the only write; the backend owns the resulting run.
 *
 * Remote observe flows are cold one-shot fetches, so the runs list is
 * re-collected on a refresh tick raised after each dispatch (T-012: the
 * phone E2E showed the list stale until back+re-enter). Same pattern as
 * ChatThreadViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentDetailViewModel(
    private val agentRepository: AgentRepository,
    workspaceRepository: WorkspaceRepository,
    backend: String,
    agentName: String,
    private val newRequestId: () -> RequestId,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    /**
     * T-022c: git checkouts dispatch can target. Feeds the workspace
     * picker; only rendered for backends advertising workspace_selection.
     */
    val workspaces: StateFlow<List<Workspace>> = workspaceRepository.observeWorkspaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val role: StateFlow<AgentRole?> = agentRepository.observeSurface()
        .map { surface -> surface.agents.firstOrNull { it.backend == backend && it.name == agentName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val backendInfo: StateFlow<AgentBackendInfo?> = agentRepository.observeSurface()
        .map { surface -> surface.backends[backend] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val runs: StateFlow<List<AgentRun>> = refreshTick
        .flatMapLatest { agentRepository.observeRuns() }
        .map { list -> list.filter { it.backend == backend && it.agent == agentName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    /**
     * The most recent successful dispatch — the screen renders it as a
     * tappable "open run" row instead of a dead-end text note (T-012).
     */
    private val _lastDispatched = MutableStateFlow<AgentRun?>(null)
    val lastDispatched: StateFlow<AgentRun?> = _lastDispatched.asStateFlow()

    fun dispatch(prompt: String, projectRef: String?) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            runCatching {
                agentRepository.dispatch(
                    AgentDispatchDraft(
                        prompt = prompt.trim(),
                        backend = role.value?.backend,
                        agent = role.value?.name,
                        projectRef = projectRef?.trim()?.takeIf { it.isNotBlank() },
                    ),
                    newRequestId(),
                )
            }.onSuccess { run ->
                _lastDispatched.value = run
                _feedback.value = null
                refreshTick.value++ // re-collect the one-shot runs fetch
            }.onFailure { e ->
                _feedback.value = "Dispatch failed: ${e.message}"
            }
        }
    }
}

/**
 * One execution: process-manager controls (send/steer/cancel/command,
 * capability-gated) plus the explicit D003 transitions routed through the
 * owning repositories — never silent conversions.
 *
 * Remote observe flows are cold one-shot fetches, so run/surface are
 * re-collected on a refresh tick raised after every control action —
 * the status row then reflects server-side changes without re-entering
 * the screen (T-012).
 *
 * T-017: a dispatched or resumed turn runs for seconds-to-minutes
 * server-side. While the shared run chain is subscribed (screen present,
 * via the WhileSubscribed gate's onStart/onCompletion hooks) a ticker
 * re-raises the refresh tick every [POLL_INTERVAL_MS] as long as the
 * observed run is active; a settled run costs zero fetches, the ticker
 * parks entirely when the screen leaves, and a resumed turn restarts
 * polling through the next observed emission. When an active run is
 * observed settling, the durable evidence (result + events) is pulled once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunDetailViewModel(
    private val agentRepository: AgentRepository,
    private val taskRepository: TaskRepository,
    private val noteRepository: NoteRepository,
    private val chatRepository: ChatRepository,
    private val organizationRepository: OrganizationRepository,
    private val runId: String,
    private val newRequestId: () -> RequestId,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    private val tickedRun = refreshTick.flatMapLatest { agentRepository.observeRun(runId) }
    private val tickedSurface = refreshTick.flatMapLatest { agentRepository.observeSurface() }

    /** True while an active run has been observed since the last settle. */
    private var observedActive = false

    val run: StateFlow<AgentRun?> = tickedRun
        .onEach { run ->
            when {
                run?.isActive == true -> observedActive = true
                observedActive -> {
                    // The turn just settled — pull the durable evidence once.
                    observedActive = false
                    refreshEvidence()
                }
            }
        }
        .onStart {
            // T-017: the screen is present — run the poll-while-active ticker.
            // This fires when the WhileSubscribed gate opens (with its grace),
            // so the ticker's lifecycle is pinned to the shared chain rather
            // than tracked by hand.
            ensurePolling()
        }
        .onCompletion {
            // Screen gone past the 5s grace — park the ticker.
            stopPolling()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * T-017: a busy turn answers send with 409 (SessionBusyError) — the
     * composer is gated on turn activity instead of surfacing that as an
     * error. Null run stays sendable; the composer hides itself until a
     * run exists.
     */
    val canSend: StateFlow<Boolean> = run
        .map { it?.isActive != true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val backendInfo: StateFlow<AgentBackendInfo?> = combine(
        tickedRun,
        tickedSurface,
    ) { run, surface -> run?.backend?.let { surface.backends[it] } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Slash-commands when this run's backend advertises them. */
    val commands: StateFlow<List<AgentCommand>> = combine(
        tickedRun,
        tickedSurface,
    ) { run, surface -> run?.backend?.takeIf { surface.backends[it]?.commands == true } }
        .distinctUntilChanged()
        .map { backend ->
            backend?.let { runCatching { agentRepository.commands(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _result = MutableStateFlow<AgentRunResult?>(null)
    val result: StateFlow<AgentRunResult?> = _result.asStateFlow()

    private val _events = MutableStateFlow<List<AgentEvent>>(emptyList())
    val events: StateFlow<List<AgentEvent>> = _events.asStateFlow()

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    init {
        refreshEvidence()
    }

    private var pollJob: Job? = null

    /**
     * T-017 ticker: while the shared run chain is subscribed (screen
     * present) and an active run has been observed, re-raise the refresh
     * tick every [POLL_INTERVAL_MS]. The loop itself is nearly free while
     * the run is settled — it only skips re-ticking — and [stopPolling]
     * (from the chain's onCompletion) parks it entirely when the screen
     * leaves. A settled run therefore costs zero fetches; a resumed turn
     * restarts polling through observedActive on the next emission.
     */
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (observedActive) refreshTick.value++
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Pull the durable evidence (result + event stream) once, on demand. */
    fun refreshEvidence() {
        viewModelScope.launch {
            runCatching { agentRepository.result(runId) }
                .onSuccess { _result.value = it }
                .onFailure { _result.value = null }
            runCatching { agentRepository.events(runId) }
                .onSuccess { _events.value = it }
        }
    }

    fun send(message: String) = action("Sent — session running") {
        agentRepository.send(runId, message, newRequestId())
    }

    fun steer(message: String) {
        if (message.isBlank()) return
        viewModelScope.launch {
            runCatching { agentRepository.steer(runId, message.trim(), newRequestId()) }
                .onSuccess { outcome ->
                    refreshTick.value++
                    _feedback.value = when (outcome) {
                        SteerOutcome.DELIVERED -> "Steer delivered"
                        SteerOutcome.QUEUED -> "Steer queued for next turn"
                        SteerOutcome.UNSUPPORTED -> "Steering not supported by this backend"
                        SteerOutcome.UNKNOWN -> "Steer outcome unknown"
                    }
                }
                .onFailure { e -> _feedback.value = "Steer failed: ${e.message}" }
        }
    }

    fun cancel() = action("Cancelled") {
        agentRepository.cancel(runId, newRequestId())
    }

    fun runCommand(command: AgentCommand) = action("Command sent: /${command.name}") {
        agentRepository.runCommand(runId, command.name, "", newRequestId())
    }

    // ---- D003: explicit transitions (routed through owning repos) ----

    fun createTask() = transition { run ->
        val task = taskRepository.createTask(
            TaskDraft(
                title = "Follow up: ${run.displayTitle}",
                sourceType = EntityKind.AGENT_RUN,
                sourceId = run.id,
            ),
            newRequestId(),
        )
        "Task created: ${task.title}"
    }

    fun saveNote(project: Project? = null) = transition { run ->
        noteRepository.createNote(
            NoteDraft(
                text = buildString {
                    appendLine(run.displayTitle)
                    appendLine("Status: ${run.state.wire} (${run.backend}/${run.agent})")
                    run.resultSummary?.let { appendLine(it) }
                }.trim(),
                projectId = project?.id,
                sourceType = EntityKind.AGENT_RUN,
                sourceId = run.id,
            ),
            newRequestId(),
        )
        if (project == null) "Note saved" else "Note saved to ${project.name}"
    }

    /**
     * T-022e: vault projects eligible as save targets — the server (V-064)
     * only accepts `vault:project:{slug}` ids; machine projects have no
     * vault folder and 422, so they never appear in the picker.
     */
    val saveTargets: StateFlow<List<Project>> = organizationRepository.observeProjects()
        .map { projects -> projects.filter { it.id.startsWith("vault:project:") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun discussInChat() = transition { run ->
        chatRepository.createThread(ChatThreadDraft(title = run.displayTitle), newRequestId())
        "Chat created: ${run.displayTitle}"
    }

    // ---- plumbing ----

    private fun action(label: String, block: suspend () -> Any?) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess {
                    _feedback.value = label
                    refreshTick.value++ // run state may have moved server-side
                    refreshEvidence()
                }
                .onFailure { e -> _feedback.value = "Failed: ${e.message}" }
        }
    }

    private fun transition(label: suspend (AgentRun) -> String) {
        viewModelScope.launch {
            val run = run.value ?: return@launch
            runCatching { label(run) }
                .onSuccess { _feedback.value = it }
                .onFailure { e -> _feedback.value = "Failed: ${e.message}" }
        }
    }

    companion object {
        /** Poll cadence while a turn runs; virtual-time-friendly in tests. */
        const val POLL_INTERVAL_MS = 5_000L
    }
}

/** A run worth polling: mid-turn states only (T-017). */
private val AgentRun?.isActive: Boolean
    get() = this?.state == AgentRunState.QUEUED || this?.state == AgentRunState.RUNNING
