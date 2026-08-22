package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.AgentRepository
import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.domain.ActionType
import dev.magnor.kompakt.domain.Agent
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.TaskDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/** Agents list — a process manager, not a chat history. */
class AgentsListViewModel(
    agentRepository: AgentRepository,
    val now: Instant,
) : ViewModel() {
    val agents: StateFlow<List<Agent>> = agentRepository.observeAgents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val runs: StateFlow<List<AgentRun>> = agentRepository.observeRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** One persistent worker with its recent runs. */
class AgentDetailViewModel(
    agentRepository: AgentRepository,
    agentId: EntityId,
) : ViewModel() {
    val agent: StateFlow<Agent?> = agentRepository.observeAgent(agentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val runs: StateFlow<List<AgentRun>> = agentRepository.observeRuns(agentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * One agent execution. The D003 transitions (Discuss / Create task / Save
 * note / Archive) are explicit user actions routed through the owning
 * repositories — never silent conversions.
 */
class AgentRunDetailViewModel(
    private val agentRepository: AgentRepository,
    private val taskRepository: TaskRepository,
    private val noteRepository: NoteRepository,
    private val chatRepository: ChatRepository,
    private val runId: EntityId,
    private val newRequestId: () -> RequestId,
) : ViewModel() {

    val run: StateFlow<AgentRun?> = agentRepository.observeRun(runId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    fun createTask() = transition { run ->
        val task = taskRepository.createTask(
            TaskDraft(
                title = "Follow up: ${run.title}",
                sourceType = EntityKind.AGENT_RUN,
                sourceId = run.id,
            ),
            newRequestId(),
        )
        "Task created: ${task.title}"
    }

    fun saveNote() = transition { run ->
        noteRepository.createNote(
            NoteDraft(
                text = buildString {
                    appendLine(run.title)
                    appendLine("Status: ${run.status.wire}")
                    run.resultSummary?.let { appendLine(it) }
                }.trim(),
                sourceType = EntityKind.AGENT_RUN,
                sourceId = run.id,
            ),
            newRequestId(),
        )
        "Note saved"
    }

    fun discussInChat() = transition { run ->
        chatRepository.createThread(ChatThreadDraft(title = run.title), newRequestId())
        "Chat created: ${run.title}"
    }

    fun archive() = transition { run ->
        agentRepository.actOnRun(run.id, ActionType.ARCHIVE, newRequestId())
        "Archived"
    }

    private fun transition(label: suspend (AgentRun) -> String) {
        viewModelScope.launch {
            val run = run.value ?: return@launch
            runCatching { label(run) }
                .onSuccess { _feedback.value = it }
                .onFailure { _feedback.value = "Failed: ${it.message}" }
        }
    }
}
