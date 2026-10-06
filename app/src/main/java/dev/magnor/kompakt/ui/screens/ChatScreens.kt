package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Instant
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.cards.CardMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.data.remote.TransportStatus
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.MessageRole
import dev.magnor.kompakt.domain.MessageStatus
import dev.magnor.kompakt.sync.TunnelController
import dev.magnor.kompakt.ui.LocalAppContainer
import dev.magnor.kompakt.ui.MarkdownText
import dev.magnor.kompakt.voice.MicButton
import dev.magnor.kompakt.voice.VoiceStatusText
import dev.magnor.kompakt.voice.appendTranscript
import dev.magnor.kompakt.voice.rememberVoiceInput
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.mdPreview
import dev.magnor.kompakt.ui.relativeTo
import dev.magnor.kompakt.ui.timeOfDay
import dev.magnor.kompakt.ui.viewmodels.ChatComposerMode
import dev.magnor.kompakt.ui.viewmodels.ChatListViewModel
import dev.magnor.kompakt.ui.viewmodels.NewChatViewModel
import dev.magnor.kompakt.ui.viewmodels.NewChatState
import dev.magnor.kompakt.ui.viewmodels.ChatSendState
import dev.magnor.kompakt.ui.viewmodels.ChatThreadViewModel
import kotlinx.coroutines.launch

/** Chat list — conversations are distinct from agents and tasks (D003). */
@Composable
fun ChatListScreen(
    onOpenThread: (EntityId) -> Unit,
    /** Opens the unsaved new-chat screen; null scope = General. */
    onNewChat: (scopeType: String?, scopeRef: String?, label: String?) -> Unit,
    viewModel: ChatListViewModel = containerViewModel {
        ChatListViewModel(it.chatRepository, it.topicRepository, it.workspaceRepository, it.now())
    },
) {
    val state by viewModel.state.collectAsState()
    val topics = state.topics
    val workspaces = state.workspaces

    // T-051: transport health from the app container (established
    // composition-local route — see VoiceUi) distinguishes Offline from
    // Loading while the list fetches are in flight.
    val transport by LocalAppContainer.current.transportStatus.collectAsState()
    // T-051 (W2): tunnel dial phase — the loading subtitle tells the user
    // whether the wait is the (cold) dial or the fetch itself.
    val tunnel by LocalAppContainer.current.tunnelState.collectAsState()
    var newChatExpanded by remember { mutableStateOf(false) }

    AppScreen(title = "Chats") {
        // T-022d: chat scope picker — General (no context), vault topics, or
        // workspaces (OpenCode sessions). Choice at creation only; the
        // thread header can re-scope later.
        // General is the everyday chat (D034): one tap straight into it.
        // Scoped chats (topic / workspace) live behind the secondary row.
        ListRow(
            title = "New chat",
            subtitle = "General — no topic context",
            trailing = "+",
            onClick = {
                newChatExpanded = false
                onNewChat(null, null, null)
            },
        )
        if (topics.isNotEmpty() || workspaces.isNotEmpty()) {
            ListRow(
                title = "New chat in a topic or workspace…",
                trailing = if (newChatExpanded) "▾" else "▸",
                onClick = { newChatExpanded = !newChatExpanded },
            )
        }
        if (newChatExpanded) {
            if (topics.isNotEmpty()) {
                SectionLabel("Topics")
                state.topics.forEach { topic ->
                    ListRow(title = topic.label) {
                        newChatExpanded = false
                        onNewChat("topic", topic.id, topic.label)
                    }
                }
            }
            if (state.workspaces.isNotEmpty()) {
                SectionLabel("Workspaces")
                state.workspaces.forEach { workspace ->
                    ListRow(title = workspace.label, subtitle = workspace.ref) {
                        newChatExpanded = false
                        onNewChat("workspace", workspace.ref, workspace.label)
                    }
                }
            }
        }
        // T-051 tri-state: Loading / Offline / genuine "No chats yet" — never
        // a false empty while the fetches are in flight or the tunnel is down.
        // D037: degradeTransport swallows offline failures into empty
        // emissions, so loaded+empty+Degraded may be a fake empty —
        // honest-first shows Offline instead of an empty list we can't confirm.
        when {
            state.threads.isNotEmpty() -> state.threads.forEach { thread ->
                ListRow(
                    title = thread.title,
                    subtitle = listOfNotNull(thread.scopeLabel, thread.lastMessagePreview)
                        .joinToString(" · "),
                    trailing = thread.updatedAt.relativeTo(viewModel.now),
                    onClick = { onOpenThread(thread.id) },
                )
            }
            !state.loaded && transport is TransportStatus.Degraded ->
                ListRow(
                    title = "Offline — server unreachable",
                    subtitle = "Will load when connection returns",
                )
            !state.loaded -> ListRow(
                title = "Loading chats…",
                subtitle = tunnelPhaseSubtitle(tunnel),
            )
            transport is TransportStatus.Degraded ->
                ListRow(
                    title = "Offline — server unreachable",
                    subtitle = "Can't confirm empty while offline",
                )
            else -> EmptyState("No chats yet", "Tap New chat to start")
        }
    }
}

/**
 * Unsaved new chat: an empty composer, nothing created server-side until
 * the first send (see [NewChatViewModel]). The keyboard opens straight
 * away — the only thing to do here is type.
 */
@Composable
fun NewChatScreen(
    scopeType: String?,
    scopeRef: String?,
    label: String?,
    onBack: () -> Unit,
    onCreated: (EntityId) -> Unit,
    viewModel: NewChatViewModel = containerViewModel(key = "chatnew-$scopeType-$scopeRef") {
        NewChatViewModel(it.chatRepository, it.pendingFirstMessages, scopeType, scopeRef, it::nextRequestId)
    },
) {
    val draft by viewModel.draft.collectAsState()
    val state by viewModel.state.collectAsState()
    val created by viewModel.created.collectAsState()
    val creating = state is NewChatState.Creating
    val focus = remember { FocusRequester() }

    LaunchedEffect(created) {
        created?.let { id ->
            viewModel.consumeCreated()
            onCreated(id)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ChatScaffold(
        title = "New chat · " + when (scopeType) {
            null -> "General"
            else -> label ?: scopeRef ?: scopeType
        },
        onBack = onBack,
        transcript = {
            (state as? NewChatState.Failed)?.let { failed ->
                item(key = "failed") {
                    Column {
                        ListRow(
                            title = failed.reason,
                            subtitle = "Draft kept in the composer — press Send to retry",
                            trailing = "!",
                        )
                        ButtonMMD(
                            onClick = viewModel::dismissError,
                            modifier = Modifier.fillMaxWidth(),
                        ) { TextMMD("Dismiss") }
                    }
                }
            }
        },
        composer = {
            val voice = rememberVoiceInput { transcript ->
                viewModel.onDraftChange(appendTranscript(draft, transcript))
            }
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = viewModel::onDraftChange,
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        placeholder = { TextMMD("Message") },
                        singleLine = false,
                        maxLines = 6,
                        trailingIcon = { MicButton(voice) },
                    )
                    IconButton(
                        onClick = viewModel::send,
                        enabled = draft.isNotBlank() && !creating,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
                if (creating) TextMMD("Starting chat…", fontSize = 12.sp)
                VoiceStatusText(voice)
            }
        },
    )
}

/**
 * Chat thread (T-013): transcript-first layout — top bar, scrolling
 * conversation, fixed bottom composer. Sender identity is carried by
 * alignment and weight (monochrome e-ink): user messages sit in a
 * right-shifted card with semi-bold text, assistant replies run full-width
 * as plain text. Tap a message for history actions (edit / revert /
 * regenerate — all destructive, V-054); "Jump" opens an inline index.
 */
@Composable
fun ChatThreadScreen(
    threadId: EntityId,
    onBack: () -> Unit,
    viewModel: ChatThreadViewModel = containerViewModel(key = "chat-$threadId") {
        ChatThreadViewModel(it.chatRepository, it.topicRepository, it.workspaceRepository, it.noteRepository, threadId, it::nextRequestId, it.clock, it.pendingFirstMessages.take(threadId))
    },
) {
    val thread by viewModel.thread.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val sendState by viewModel.sendState.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val composerMode by viewModel.composerMode.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val proposedTopic by viewModel.proposedTopic.collectAsState()
    val topics by viewModel.topics.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    var infoOpen by remember { mutableStateOf(false) }

    // T-051: transport health from the app container (same route as the
    // chat list) distinguishes Offline from Loading while this thread's
    // history fetch is in flight.
    val loaded by viewModel.loaded.collectAsState()
    val transport by LocalAppContainer.current.transportStatus.collectAsState()
    // T-051 (W2): same dial-vs-fetch subtitle as the chat list.
    val tunnel by LocalAppContainer.current.tunnelState.collectAsState()
    var scopeExpanded by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var jumpOpen by remember { mutableStateOf(false) }
    var openMessageId by remember { mutableStateOf<EntityId?>(null) }

    val sending = sendState is ChatSendState.Sending
    // Items: messages 0..n-1, then at most one trailing status item.
    val lastItem = if (messages.isEmpty()) 0 else messages.size - 1 +
        (if (sending || sendState is ChatSendState.Failed) 1 else 0)
    LaunchedEffect(messages.size, sending, sendState) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            runCatching { listState.scrollToItem(lastItem.coerceAtMost(total - 1)) }
        }
    }

    ChatScaffold(
        title = thread?.title ?: "Chat",
        onBack = onBack,
        listState = listState,
        // Thread info (scope, re-scope, jump) lives behind one top-bar
        // button; the dot marks a pending topic suggestion.
        actions = {
            IconButton(onClick = { infoOpen = !infoOpen }) {
                Icon(Icons.Filled.Info, contentDescription = "Chat info")
            }
            if (proposedTopic != null) TextMMD("●", modifier = Modifier.padding(end = 8.dp))
        },
        header = {
            if (infoOpen) {
                // Bounded + scrollable: a long jump index must not push the
                // transcript and composer off screen.
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    // T-022d: scope — the thread's context (general/topic/
                    // workspace); expands to re-scope. Always explicit.
                    ListRow(
                        title = "Scope: ${thread?.scopeLabel ?: "General"}",
                        subtitle = when (thread?.scopeType) {
                            null -> "Tap to add topic or workspace context"
                            "topic" -> "Topic"
                            "workspace" -> "Workspace · auto-commits each turn"
                            else -> thread?.scopeType
                        },
                        trailing = if (scopeExpanded) "▾" else "▸",
                        onClick = { scopeExpanded = !scopeExpanded },
                    )
                    if (scopeExpanded) {
                        ListRow(title = "General", subtitle = "No topic context") {
                            scopeExpanded = false
                            viewModel.setScope(null, null)
                        }
                        if (topics.isNotEmpty()) {
                            SectionLabel("Topics")
                            topics.forEach { topic ->
                                ListRow(
                                    title = topic.label,
                                    trailing = if (thread?.scopeType == "topic" && thread?.scopeRef == topic.id) "●" else null,
                                ) {
                                    scopeExpanded = false
                                    viewModel.setScope("topic", topic.id)
                                }
                            }
                        }
                        if (workspaces.isNotEmpty()) {
                            SectionLabel("Workspaces")
                            workspaces.forEach { workspace ->
                                ListRow(
                                    title = workspace.label,
                                    subtitle = workspace.ref,
                                    trailing = if (thread?.scopeType == "workspace" && thread?.scopeRef == workspace.ref) "●" else null,
                                ) {
                                    scopeExpanded = false
                                    viewModel.setScope("workspace", workspace.ref)
                                }
                            }
                        }
                    }
                    if (messages.size > 1) {
                        ListRow(
                            title = "Jump to message",
                            trailing = if (jumpOpen) "▾" else "▸",
                            onClick = { jumpOpen = !jumpOpen },
                        )
                        if (jumpOpen) {
                            messages.forEachIndexed { index, message ->
                                ListRow(
                                    title = "#${index + 1} · ${mdPreview(message.content, 42)}",
                                    subtitle = "${if (message.role == MessageRole.USER) "You" else "Assistant"} · ${message.createdAt.timeOfDay()}",
                                    onClick = {
                                        jumpOpen = false
                                        infoOpen = false
                                        scope.launch { listState.scrollToItem(index) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (thread?.pendingReply == true) {
                TextMMD(
                    text = "Workspace turn running… checking every 15 s",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            proposedTopic?.let { topic ->
                Column {
                    ListRow(
                        title = "Topic match: ${topic.label}",
                        subtitle = "Move this chat into the topic?",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ButtonMMD(
                            onClick = viewModel::applyProposal,
                            modifier = Modifier.weight(1f),
                        ) { TextMMD("Move") }
                        ButtonMMD(
                            onClick = viewModel::dismissProposal,
                            modifier = Modifier.weight(1f),
                        ) { TextMMD("Not now") }
                    }
                }
            }
            notice?.let { text ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = viewModel::dismissNotice)
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextMMD(text = text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextMMD(text = "✕", fontSize = 13.sp)
                }
            }
        },
        transcript = {
            if (messages.isEmpty()) {
                // T-051 tri-state: Loading / Offline / genuine "No messages" —
                // never a false empty while the history fetch is in flight or
                // the tunnel is down. Only renders when the transcript is
                // empty, so existing conversations are untouched (sticky
                // loaded also prevents a post-send refetch flash). Unlike the
                // chat LIST there is no Degraded-empty amendment here: a
                // zero-message thread is rare and the composer still renders,
                // transport honesty is covered on the next open (b).
                item(key = "empty") {
                    when {
                        !loaded && transport is TransportStatus.Degraded ->
                            ListRow(
                                title = "Offline — server unreachable",
                                subtitle = "Will load when connection returns",
                            )
                        !loaded -> ListRow(
                            title = "Loading messages…",
                            subtitle = tunnelPhaseSubtitle(tunnel),
                        )
                        else -> ListRow(title = "No messages", subtitle = "Write below")
                    }
                }
            }
            itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                val isLast = index == messages.lastIndex
                ChatMessageRow(
                    message = message,
                    previousAt = messages.getOrNull(index - 1)?.createdAt,
                    messagesAfter = messages.size - 1 - index,
                    expanded = openMessageId == message.id,
                    canRegenerate = isLast && message.role == MessageRole.ASSISTANT,
                    onClick = { openMessageId = if (openMessageId == message.id) null else message.id },
                    onEdit = { viewModel.beginEdit(message) },
                    onRevert = { viewModel.revertTo(message) },
                    onRegenerate = viewModel::regenerate,
                    onSaveNote = { viewModel.saveNote(message) },
                )
            }
            if (sending) {
                item(key = "sending") { TextMMD("Assistant is replying…", fontSize = 13.sp) }
            }
            (sendState as? ChatSendState.Failed)?.let { failed ->
                item(key = "failed") {
                    Column {
                        ListRow(
                            title = failed.reason ?: "Send failed",
                            subtitle = "Draft kept in the composer — press Send to retry",
                            trailing = "!",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ButtonMMD(
                                onClick = viewModel::send,
                                modifier = Modifier.weight(1f),
                            ) { TextMMD("Retry") }
                            ButtonMMD(
                                onClick = viewModel::dismissError,
                                modifier = Modifier.weight(1f),
                            ) { TextMMD("Dismiss") }
                        }
                    }
                }
            }
        },
        composer = {
            val editing = composerMode as? ChatComposerMode.EditFrom
            // T-021: mic lives in the composer slot — leaving the thread
            // disposes it, cancelling any live recording.
            val voice = rememberVoiceInput { transcript ->
                viewModel.onDraftChange(appendTranscript(draft, transcript))
            }
            Column {
                if (editing != null) {
                    ListRow(
                        title = "Editing message — Send rewrites the conversation from here",
                        subtitle = "Everything after it is dropped",
                        trailing = "✕",
                        onClick = viewModel::cancelEdit,
                    )
                }
                // T-015: single-row composer — field + compact send beside it
                // (bottom-aligned so it tracks the last line as the draft grows).
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = viewModel::onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { TextMMD(if (editing != null) "Edited message" else "Message") },
                        singleLine = false,
                        maxLines = 6,
                        trailingIcon = { MicButton(voice) },
                    )
                    IconButton(
                        onClick = viewModel::send,
                        enabled = draft.isNotBlank() && !sending,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (editing != null) "Send edit" else "Send",
                        )
                    }
                }
                VoiceStatusText(voice)
            }
        },
    )
}

/** Show a message's time only after a pause this long (or on the first one). */
private const val TIME_GAP_SECONDS = 10 * 60L

/**
 * T-051 (W2): phase-aware subtitle for loading rows — distinguishes the
 * 1–10 s cold tunnel dial ("Connecting") from the fetch itself. Tunnel
 * reads Down while an up() is in progress, so "not Up" means the dial is
 * still pending; Error is never assigned today but maps to Connecting
 * too (still no path). Null = no tunnel configured (fake/plain-remote
 * mode): no dial can happen, so nothing is claimed. Static text only
 * (e-ink).
 */
private fun tunnelPhaseSubtitle(tunnel: TunnelController.State?): String? =
    when (tunnel) {
        null -> null
        TunnelController.State.Up -> "Fetching"
        else -> "Connecting — secure tunnel"
    }

/**
 * One message. Monochrome sender coding: user = right-shifted bordered card,
 * semi-bold; assistant = plain full-width text (no card — a long thread
 * should not read as a stack of boxes). The sender/time meta line is quiet
 * by default: shown on the first message, after a pause of [TIME_GAP_SECONDS],
 * while pending/failed (the glyphs carry delivery state), or when tapped.
 *
 * Tapping a message reveals its meta plus one "Actions" row; the history
 * actions (edit / save / regenerate / revert) sit behind it, and the two
 * that drop messages (revert, regenerate) ask for a confirm first — a
 * stray tap must not silently lose conversation.
 */
@Composable
private fun ChatMessageRow(
    message: Message,
    previousAt: Instant?,
    messagesAfter: Int,
    expanded: Boolean,
    canRegenerate: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRevert: () -> Unit,
    onRegenerate: () -> Unit,
    onSaveNote: () -> Unit,
) {
    val delivered = message.status != MessageStatus.PENDING && message.status != MessageStatus.FAILED
    val afterPause = previousAt == null ||
        (message.createdAt - previousAt).inWholeSeconds >= TIME_GAP_SECONDS
    val showMeta = expanded || !delivered || afterPause
    val meta = buildString {
        append(if (message.role == MessageRole.USER) "You" else "Assistant")
        append(" · ")
        append(message.createdAt.timeOfDay())
        when (message.status) {
            MessageStatus.PENDING -> append(" · …")
            MessageStatus.FAILED -> append(" · !")
            else -> Unit
        }
    }
    var actionsOpen by remember(message.id) { mutableStateOf(false) }
    var confirming by remember(message.id) { mutableStateOf<String?>(null) }
    // Collapse the action state whenever the message itself collapses.
    LaunchedEffect(expanded) {
        if (!expanded) {
            actionsOpen = false
            confirming = null
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (message.role == MessageRole.USER) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                CardMMD(onClick = onClick, modifier = Modifier.fillMaxWidth(0.85f)) {
                    Column(Modifier.padding(12.dp)) {
                        MarkdownText(raw = message.content, baseFontWeight = FontWeight.SemiBold)
                        if (showMeta) {
                            TextMMD(text = meta, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(vertical = 4.dp),
            ) {
                MarkdownText(raw = message.content)
                if (showMeta) {
                    TextMMD(text = meta, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (expanded) {
            ListRow(
                title = "Actions",
                trailing = if (actionsOpen) "▾" else "▸",
                onClick = {
                    actionsOpen = !actionsOpen
                    confirming = null
                },
            )
            if (actionsOpen) {
                when (confirming) {
                    "revert" -> ConfirmRow(
                        text = if (messagesAfter == 0) "Nothing after this message to drop."
                        else "Drop the $messagesAfter message${if (messagesAfter == 1) "" else "s"} after this one? This can't be undone.",
                        confirmLabel = "Revert",
                        onConfirm = { confirming = null; onRevert() },
                        onCancel = { confirming = null },
                    )
                    "regenerate" -> ConfirmRow(
                        text = "Drop this reply and ask again? This can't be undone.",
                        confirmLabel = "Regenerate",
                        onConfirm = { confirming = null; onRegenerate() },
                        onCancel = { confirming = null },
                    )
                    else -> {
                        if (message.role == MessageRole.USER) {
                            // Edit only fills the composer; nothing is dropped until Send.
                            ListRow(title = "Edit") { onEdit() }
                        } else {
                            // T-022b: explicit transition — the reply text becomes
                            // an inbox note verbatim (source: this thread).
                            ListRow(title = "Save as note") { onSaveNote() }
                        }
                        if (canRegenerate) {
                            ListRow(title = "Regenerate", subtitle = "Drops this reply") { confirming = "regenerate" }
                        }
                        ListRow(title = "Revert to here", subtitle = "Drops everything after") { confirming = "revert" }
                    }
                }
            }
        }
    }
}

/** Inline confirm for a destructive history action: text + Cancel / confirm. */
@Composable
private fun ConfirmRow(
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ListRow(title = text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonMMD(onClick = onCancel, modifier = Modifier.weight(1f)) { TextMMD("Cancel") }
            ButtonMMD(onClick = onConfirm, modifier = Modifier.weight(1f)) { TextMMD(confirmLabel) }
        }
    }
}
