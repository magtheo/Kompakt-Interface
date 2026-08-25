package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.TopicRepository
import dev.magnor.kompakt.data.repository.WorkspaceRepository
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.ChatThreadDraft
import dev.magnor.kompakt.domain.ChatTopic
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.MessageRole
import dev.magnor.kompakt.domain.MessageStatus
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.Workspace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/** V-063: workspace pending-reply poll cadence (15 s — e-ink/battery friendly). */
private const val PENDING_POLL_MS = 15_000L

/**
 * Chat list — conversation contexts, intentionally separate from agents (D003).
 * Remote observe flows are one-shot fetches, so the list is simply re-collected
 * on each entry; threads are few and static enough for e-ink.
 */
class ChatListViewModel(
    private val chatRepository: ChatRepository,
    private val topicRepository: TopicRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val newRequestId: () -> RequestId,
    val now: Instant,
) : ViewModel() {
    val threads: StateFlow<List<ChatThread>> = chatRepository.observeThreads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** T-022d: reference data for the new-chat scope picker. */
    val topics: StateFlow<List<ChatTopic>> = topicRepository.observeTopics()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val workspaces: StateFlow<List<Workspace>> = workspaceRepository.observeWorkspaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _created = MutableStateFlow<EntityId?>(null)

    /** New-thread id — the screen consumes it for navigation, then nulls it. */
    val created: StateFlow<EntityId?> = _created.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var creating = false

    /** T-022d: null scope = general chat; the picker passes its selection. */
    fun newChat(scopeType: String? = null, scopeRef: String? = null) {
        if (creating) return
        creating = true
        _error.value = null
        viewModelScope.launch {
            runCatching {
                chatRepository.createThread(
                    ChatThreadDraft(title = "New chat", scopeType = scopeType, scopeRef = scopeRef),
                    newRequestId(),
                )
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
/**
 * Delivery state of the message currently being sent (static indicators only — e-ink). */
sealed interface ChatSendState {
    data object Idle : ChatSendState
    data object Sending : ChatSendState

    /** Text is kept for retry; [reason] is the transport failure cause. */
    data class Failed(val text: String, val reason: String?) : ChatSendState
}

/**
 * What the composer is doing with the draft — plain send, or rewriting the
 * conversation from an earlier message (V-054 truncate + send composition).
 */
sealed interface ChatComposerMode {
    data object Plain : ChatComposerMode
    data class EditFrom(val message: Message) : ChatComposerMode
}

/**
 * One conversation thread. Remote observe flows are cold one-shot fetches,
 * so messages/thread are re-collected on a refresh tick (raised after a
 * send). Acknowledged messages are overlaid until the refetched snapshot
 * contains their ids — this keeps the optimistic row, the ack, and the
 * refetch from ever rendering duplicates.
 *
 * History operations (V-054): revertTo / editFrom / regenerate compose the
 * server's destructive truncate primitive with a fresh send. No branch is
 * kept — the overwritten history is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadViewModel(
    private val chatRepository: ChatRepository,
    topicRepository: TopicRepository,
    workspaceRepository: WorkspaceRepository,
    private val noteRepository: NoteRepository,
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

    /** T-022d: reference data for the header scope picker. */
    val topics: StateFlow<List<ChatTopic>> = topicRepository.observeTopics()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val workspaces: StateFlow<List<Workspace>> = workspaceRepository.observeWorkspaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * T-022d: the server's topic suggestion from the last unscoped send.
     * A suggestion only — never applied until [applyProposal].
     */
    private val _proposedTopic = MutableStateFlow<ChatTopic?>(null)
    val proposedTopic: StateFlow<ChatTopic?> = _proposedTopic.asStateFlow()

    private val _sendState = MutableStateFlow<ChatSendState>(ChatSendState.Idle)
    val sendState: StateFlow<ChatSendState> = _sendState.asStateFlow()

    val draft = MutableStateFlow("")

    private val _composerMode = MutableStateFlow<ChatComposerMode>(ChatComposerMode.Plain)
    val composerMode: StateFlow<ChatComposerMode> = _composerMode.asStateFlow()

    /** One-line feedback for non-send operations (revert failures etc.). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Logical-send idempotency key — reused across retries of the SAME text
     * (a lost response after a landed request must not double-send, §11). */
    private var retryRequestId: RequestId? = null

    init {
        // V-063 pending-reply poll: while a workspace turn is unsettled the
        // thread wire carries pending_reply — each messages GET lets the
        // server catch up a turn that settled after its budget. Re-tick on a
        // slow cadence until the flag clears (e-ink/battery friendly).
        viewModelScope.launch {
            thread.collect { t ->
                if (t?.pendingReply == true) {
                    // A still-pending refetch re-emits an equal snapshot,
                    // which the StateFlow dedupes — collect would never
                    // re-fire and the poll would stall after one tick.
                    // Drive the cadence from a re-reading loop instead.
                    while (true) {
                        delay(PENDING_POLL_MS)
                        if (thread.value?.pendingReply != true) break
                        refreshTick.value++
                    }
                }
            }
        }
    }

    fun onDraftChange(value: String) {
        draft.value = value
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /**
     * T-022b: save an assistant reply as an inbox note — an explicit
     * transition, never automatic. Text is the content snapshot (precision
     * loss zero); the thread id rides source_id for provenance.
     */
    fun saveNote(message: Message) {
        if (message.role != MessageRole.ASSISTANT) return
        viewModelScope.launch {
            runCatching {
                noteRepository.createNote(
                    NoteDraft(
                        text = message.content,
                        sourceType = EntityKind.CHAT,
                        sourceId = threadId,
                    ),
                    newRequestId(),
                )
            }.onSuccess { _notice.value = "Note saved: ${it.displayTitle}" }
                .onFailure { e -> _notice.value = "Note save failed: ${e.message}" }
        }
    }

    /** T-022d: apply the proposed topic — the chip's explicit Move action. */
    fun applyProposal() {
        val topic = _proposedTopic.value ?: return
        setScope("topic", topic.id)
    }

    /** T-022d: dismiss the chip — keeps the chat general, no server call. */
    fun dismissProposal() {
        _proposedTopic.value = null
    }

    /** T-022d: re-scope (or clear, nulls) — the header picker's action. */
    fun setScope(scopeType: String?, scopeRef: String?) {
        if (_sendState.value is ChatSendState.Sending) return
        _notice.value = null
        viewModelScope.launch {
            runCatching { chatRepository.setScope(threadId, scopeType, scopeRef, newRequestId()) }
                .onSuccess {
                    _proposedTopic.value = null
                    refreshTick.value++
                }
                .onFailure { e -> _notice.value = "Scope change failed: ${e.message}" }
        }
    }

    /** Enter edit mode: the draft is seeded and Send rewrites from [message]. */
    fun beginEdit(message: Message) {
        if (message.role != MessageRole.USER) return
        if (_sendState.value is ChatSendState.Sending) return
        _composerMode.value = ChatComposerMode.EditFrom(message)
        draft.value = message.content
        _notice.value = null
    }

    fun cancelEdit() {
        if (_sendState.value is ChatSendState.Sending) return
        _composerMode.value = ChatComposerMode.Plain
        draft.value = ""
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty() || _sendState.value is ChatSendState.Sending) return
        when (val mode = _composerMode.value) {
            is ChatComposerMode.EditFrom -> launchEditSend(mode.message, text)
            ChatComposerMode.Plain -> launchPlainSend(text)
        }
    }

    /**
     * Revert: keep history through [message], drop everything after it.
     * Destructive — the dropped messages are not recoverable.
     */
    fun revertTo(message: Message) {
        if (_sendState.value is ChatSendState.Sending) return
        _notice.value = null
        viewModelScope.launch {
            runCatching { chatRepository.truncate(threadId, message.id, newRequestId()) }
                .onSuccess {
                    overlay.value = emptyList() // ghosts of dropped messages
                    refreshTick.value++
                }
                .onFailure { e -> _notice.value = "Revert failed: ${e.message}" }
        }
    }

    /**
     * Regenerate: drop the trailing user+assistant pair and re-ask the same
     * text. Destructive toward the old reply.
     */
    fun regenerate() {
        if (_sendState.value is ChatSendState.Sending) return
        val list = messages.value
        val lastUser = list.indexOfLast { it.role == MessageRole.USER }
        if (lastUser < 0) {
            _notice.value = "Nothing to regenerate"
            return
        }
        val text = list[lastUser].content
        val keepThrough = list.getOrNull(lastUser - 1)?.id // message before the pair
        _notice.value = null
        _sendState.value = ChatSendState.Sending
        draft.value = ""
        viewModelScope.launch {
            runCatching { chatRepository.truncate(threadId, keepThrough, newRequestId()) }
                .onFailure { e ->
                    _sendState.value = ChatSendState.Idle
                    draft.value = text
                    _notice.value = "Regenerate failed: ${e.message}"
                }
                .onSuccess {
                    overlay.value = emptyList()
                    refreshTick.value++
                    launchSendInternal(text, freshRequestId = true)
                }
        }
    }

    fun dismissError() {
        if (_sendState.value is ChatSendState.Failed) _sendState.value = ChatSendState.Idle
    }

    // ── internals ─────────────────────────────────────────────────────

    private fun launchPlainSend(text: String) {
        val previous = _sendState.value
        val requestId = if (previous is ChatSendState.Failed && previous.text == text) {
            retryRequestId ?: newRequestId().also { retryRequestId = it }
        } else {
            newRequestId().also { retryRequestId = it }
        }
        performSend(text, requestId)
    }

    /** Edit = truncate before the target message, then a fresh send of the new text. */
    private fun launchEditSend(target: Message, text: String) {
        val keepThrough = messages.value
            .filter { it.id != LOCAL_PENDING_ID }
            .indexOfFirst { it.id == target.id }
            .let { index -> if (index <= 0) null else messages.value[index - 1].id }
        _composerMode.value = ChatComposerMode.Plain
        _sendState.value = ChatSendState.Sending
        draft.value = ""
        viewModelScope.launch {
            runCatching { chatRepository.truncate(threadId, keepThrough, newRequestId()) }
                .onFailure { e ->
                    _sendState.value = ChatSendState.Idle
                    draft.value = text
                    _notice.value = "Edit failed: ${e.message}"
                }
                .onSuccess {
                    overlay.value = emptyList()
                    refreshTick.value++
                    launchSendInternal(text, freshRequestId = true)
                }
        }
    }

    /** Optimistic send with overlay dedupe — the shared tail of every send path. */
    private fun launchSendInternal(text: String, freshRequestId: Boolean) {
        val requestId = if (freshRequestId) {
            newRequestId().also { retryRequestId = it }
        } else {
            retryRequestId ?: newRequestId().also { retryRequestId = it }
        }
        performSend(text, requestId)
    }

    private fun performSend(text: String, requestId: RequestId) {
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
                    // T-022d: surface the server's topic suggestion — a
                    // suggestion only; the chip applies it explicitly.
                    _proposedTopic.value = exchange.proposedTopic
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

    private companion object {
        val LOCAL_PENDING_ID: EntityId = "local:chat:pending"
    }
}

