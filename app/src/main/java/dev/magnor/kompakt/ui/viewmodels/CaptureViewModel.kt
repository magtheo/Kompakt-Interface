package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.CaptureRepository
import dev.magnor.kompakt.data.repository.CalendarRepository
import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.CaptureResult
import dev.magnor.kompakt.domain.CaptureType
import dev.magnor.kompakt.domain.EventDraft
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Universal capture — the server proposes structure, the user confirms or
 * changes the object type before commit. Explicit transitions only (D0xx).
 * The commit keeps one requestId per proposal so retries are idempotent.
 */
class CaptureViewModel(
    private val captureRepository: CaptureRepository,
    private val calendarRepository: CalendarRepository,
    private val newRequestId: () -> RequestId,
) : ViewModel() {

    data class CaptureUiState(
        val text: String = "",
        val proposal: CaptureProposal? = null,
        val interpreting: Boolean = false,
        val working: Boolean = false,
        val result: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private var commitRequestId: RequestId? = null

    fun onTextChange(value: String) {
        if (_state.value.result != null) return // committed — input frozen
        _state.update { it.copy(text = value, error = null) }
    }

    fun interpret() {
        val text = _state.value.text.trim()
        if (text.isEmpty() || _state.value.interpreting) return
        _state.update { it.copy(interpreting = true, error = null) }
        viewModelScope.launch {
            runCatching { captureRepository.interpret(text) }
                .onSuccess { proposal ->
                    commitRequestId = newRequestId()
                    _state.update {
                        it.copy(interpreting = false, proposal = proposal, result = null)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(interpreting = false, error = e.message) }
                }
        }
    }

    /** Cycle the proposed type — the user has the final word, not the server. */
    fun changeType() {
        val current = _state.value.proposal ?: return
        val next = when (current.proposedType) {
            CaptureType.TASK -> CaptureType.NOTE
            CaptureType.NOTE -> CaptureType.CHAT
            CaptureType.CHAT -> CaptureType.AGENT_REQUEST
            CaptureType.AGENT_REQUEST -> CaptureType.EVENT
            CaptureType.EVENT, CaptureType.UNKNOWN -> CaptureType.TASK
        }
        _state.update { it.copy(proposal = current.copy(proposedType = next)) }
    }

    fun commit() {
        val proposal = _state.value.proposal ?: return
        val requestId = commitRequestId ?: return
        if (_state.value.working) return
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            runCatching {
                if (proposal.proposedType == CaptureType.EVENT) {
                    commitEvent(proposal, requestId)
                } else {
                    captureRepository.commit(proposal, requestId)
                }
            }
                .onSuccess { result ->
                    _state.update { it.copy(working = false, result = describe(result)) }
                }
                .onFailure { e ->
                    _state.update { it.copy(working = false, error = e.message) }
                }
        }
    }

    /**
     * Events bypass /v1/capture/commit (server 501s on kind=event) — POST
     * /v1/events directly, same request id for idempotent replay (T-023).
     */
    private suspend fun commitEvent(proposal: CaptureProposal, requestId: RequestId): CaptureResult {
        val start = proposal.startAt
            ?: throw IllegalArgumentException("Event needs a start time")
        val calendar = calendarRepository.observeCalendars().first()
            .let { regs ->
                proposal.calendarId
                    ?.let { id -> regs.firstOrNull { it.id == id } }
                    ?: regs.firstOrNull { it.writable }
            }
            ?: throw IllegalArgumentException("No writable calendar")
        val created = calendarRepository.createEvent(
            requestId,
            EventDraft(
                calendarId = calendar.id,
                title = proposal.title,
                startAt = start.toString(),
                endAt = proposal.endAt?.toString(),
                allDay = proposal.allDay ?: false,
                description = proposal.text,
            ),
        )
        val event = calendarRepository.fetchEvent(created.id)
        return CaptureResult.EventCreated(
            event ?: CalendarEvent(
                id = created.id,
                title = proposal.title,
                startAt = start,
                endAt = proposal.endAt,
                description = proposal.text,
                allDay = proposal.allDay ?: false,
                symbol = calendar.symbol,
                calendarId = calendar.id,
            ),
        )
    }

    private fun describe(result: CaptureResult): String = when (result) {
        is CaptureResult.TaskCreated -> "Task created: ${result.task.title}"
        is CaptureResult.NoteCreated -> "Saved to scratchpad: ${result.note.displayTitle}"
        is CaptureResult.ChatCreated -> "Chat created: ${result.thread.title}"
        is CaptureResult.AgentRequested -> "Agent run queued: ${result.run.title}"
        is CaptureResult.EventCreated -> "Event created: ${result.event.title}"
        CaptureResult.QueuedOffline -> "Saved offline — will send when the server is reachable"
    }
}
