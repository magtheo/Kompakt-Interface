package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.MessageRole
import dev.magnor.kompakt.domain.MessageStatus
import dev.magnor.kompakt.domain.RequestId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Chat list — conversation contexts, intentionally separate from agents (D003).
 * Remote observe flows are one-shot fetches, so the list is simply re-collected
 * on each entry; threads are few and static enough for e-ink.
 */
class ChatListViewModel(
    private val chatRepository: ChatRepository,
    private val newRequestId: () -> RequestId,
    val now: Instant,
) : ViewModel() {
    val threads: StateFlow<List<ChatThread>> = chatRepository.observeThreads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _created = MutableStateFlow<EntityId?>(null)

    /** New-thread id — the screen consumes it for navigation, then nulls it. */
    val created: StateFlow<EntityId?> = _created.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var creating = false

    fun newChat() {
        if (creating) return
        creating = true
        _error.value = null
        viewModelScope.launch {
            runCatching {
                chatRepository.createThread(ChatThreadDraft(title = "New chat"), newRequestId())
            }.onSuccess { thread ->
                _created.value = thread.id
            }.onFailure { e ->
                _error.value = e.message ?: "could not create chat"
            }
            creating = false
        }
    }

    fun consumeCreated() {
        _created.value = null
    }
}

/** Delivery state of the message currently being sent (static indicators only — e-ink). */
sealed interface ChatSendState {
    data object Idle : ChatSendState
    data object Sending : ChatSendState

    /** Text is kept for retry; [reason] is the transport failure cause. */
    data class Failed(val text: String, val reason: String?) : ChatSendState
}

/**
 * One conversation thread. Remote observe flows are cold one-shot fetches,
 * so messages/thread are re-collected on a refresh tick (raised after a
 * send). Acknowledged messages are overlaid until the refetched snapshot
 * contains their ids — this keeps the optimistic row, the ack, and the
 * refetch from ever rendering duplicates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadViewModel(
    private val chatRepository: ChatRepository,
    private val threadId: EntityId,
    private val newRequestId: () -> RequestId,
    private val now: () -> Instant,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    /** Acknowledged messages not yet present in the observed snapshot. */
    private val overlay = MutableStateFlow<List<Message>>(emptyList())

    val thread: StateFlow<ChatThread?> = refreshTick
        .flatMapLatest { chatRepository.observeThread(threadId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<Message>> = combine(
        refreshTick.flatMapLatest { chatRepository.observeMessages(threadId) },
        overlay,
    ) { fetched, extra ->
        val fetchedIds = fetched.mapTo(HashSet()) { it.id }
        fetched + extra.filter { it.id !in fetchedIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sendState = MutableStateFlow<ChatSendState>(ChatSendState.Idle)
    val sendState: StateFlow<ChatSendState> = _sendState.asStateFlow()

    val draft = MutableStateFlow("")

    /** Logical-send idempotency key — reused across retries of the SAME text
     * (a lost response after a landed request must not double-send, §11). */
    private var retryRequestId: RequestId? = null

    fun onDraftChange(value: String) {
        draft.value = value
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty() || _sendState.value is ChatSendState.Sending) return
        val previous = _sendState.value
        val requestId = if (previous is ChatSendState.Failed && previous.text == text) {
            retryRequestId ?: newRequestId().also { retryRequestId = it }
        } else {
            newRequestId().also { retryRequestId = it }
        }
        _sendState.value = ChatSendState.Sending
        draft.value = ""
        overlay.value = overlay.value.filter { it.id != LOCAL_PENDING_ID } + Message(
            id = LOCAL_PENDING_ID,
            chatId = threadId,
            role = MessageRole.USER,
            content = text,
            createdAt = now(),
            updatedAt = now(),
            status = MessageStatus.PENDING,
        )
        viewModelScope.launch {
            runCatching { chatRepository.sendMessage(threadId, text, requestId) }
                .onSuccess { exchange ->
                    overlay.value = overlay.value.filter { it.id != LOCAL_PENDING_ID } +
                        listOfNotNull(exchange.user, exchange.assistant)
                    retryRequestId = null
                    refreshTick.value++ // re-snapshot; overlay ids dedupe out
                    _sendState.value = ChatSendState.Idle
                }
                .onFailure { e ->
                    overlay.value = overlay.value.filter { it.id != LOCAL_PENDING_ID }
                    draft.value = text // restored for retry
                    _sendState.value = ChatSendState.Failed(text, e.message)
                }
        }
    }

    fun dismissError() {
        if (_sendState.value is ChatSendState.Failed) _sendState.value = ChatSendState.Idle
    }

    private companion object {
        val LOCAL_PENDING_ID: EntityId = "local:chat:pending"
    }
}
