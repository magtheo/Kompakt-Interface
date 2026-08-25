package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.cards.CardMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.MessageRole
import dev.magnor.kompakt.domain.MessageStatus
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
import dev.magnor.kompakt.ui.viewmodels.ChatSendState
import dev.magnor.kompakt.ui.viewmodels.ChatThreadViewModel
import kotlinx.coroutines.launch

/** Chat list — conversations are distinct from agents and tasks (D003). */
@Composable
fun ChatListScreen(
    onOpenThread: (EntityId) -> Unit,
    viewModel: ChatListViewModel = containerViewModel {
        ChatListViewModel(it.chatRepository, it.topicRepository, it.workspaceRepository, it::nextRequestId, it.now())
    },
) {
    val threads by viewModel.threads.collectAsState()
    val created by viewModel.created.collectAsState()
    val error by viewModel.error.collectAsState()
    val topics by viewModel.topics.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    var newChatExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(created) {
        created?.let { id ->
            viewModel.consumeCreated()
            onOpenThread(id)
        }
    }

    AppScreen(title = "Chats") {
        // T-022d: chat scope picker — General (no context), vault topics, or
        // workspaces (OpenCode sessions). Choice at creation only; the
        // thread header can re-scope later.
        ListRow(
            title = "New chat",
            subtitle = if (newChatExpanded) null else "Start a conversation",
            trailing = if (newChatExpanded) "▾" else "▸",
            onClick = { newChatExpanded = !newChatExpanded },
        )
        if (newChatExpanded) {
            ListRow(title = "General", subtitle = "No topic context") {
                newChatExpanded = false
                viewModel.newChat()
            }
            if (topics.isNotEmpty()) {
                SectionLabel("Topics")
                topics.forEach { topic ->
                    ListRow(title = topic.label) {
                        newChatExpanded = false
                        viewModel.newChat("topic", topic.id)
                    }
                }
            }
            if (workspaces.isNotEmpty()) {
                SectionLabel("Workspaces")
                workspaces.forEach { workspace ->
                    ListRow(title = workspace.label, subtitle = workspace.ref) {
                        newChatExpanded = false
                        viewModel.newChat("workspace", workspace.ref)
                    }
                }
            }
        }
        error?.let { ListRow(title = it, trailing = "!") }
        if (threads.isEmpty()) {
            ListRow(title = "No chats yet", subtitle = "Tap New chat above")
        } else {
            threads.forEach { thread ->
                ListRow(
                    title = thread.title,
                    subtitle = listOfNotNull(thread.scopeLabel, thread.lastMessagePreview)
                        .joinToString(" · "),
                    trailing = thread.updatedAt.relativeTo(viewModel.now),
                    onClick = { onOpenThread(thread.id) },
                )
            }
        }
    }
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
        ChatThreadViewModel(it.chatRepository, it.topicRepository, it.workspaceRepository, it.noteRepository, threadId, it::nextRequestId, it.clock)
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
    var scopeExpanded by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var jumpOpen by remember { mutableStateOf(false) }
    var openMessageId by remember { mutableStateOf<EntityId?>(null) }

    val sending = sendState is ChatSendState.Sending
    // Item 0 = jump header; messages follow; one trailing status item.
    val lastItem = if (messages.isEmpty()) 0 else messages.size - 1 + 1 +
        (if (sending || sendState is ChatSendState.Failed) 1 else 0)
    LaunchedEffect(messages.size, sending, sendState) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            runCatching { listState.scrollToItem(lastItem) }
        }
    }

    ChatScaffold(
        title = thread?.title ?: "Chat",
        onBack = onBack,
        listState = listState,
        header = {
            // T-022d: scope row — shows the thread's context (general/topic/
            // workspace); expands to re-scope. The propose chip only ever
            // appears on unscoped threads and applies on explicit Move.
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
            if (thread?.pendingReply == true) {
                ListRow(
                    title = "Workspace turn still running",
                    subtitle = "Checking every 15 s — reply lands here",
                    trailing = "…",
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
            if (notice != null) {
                ListRow(
                    title = notice!!,
                    trailing = "✕",
                    onClick = viewModel::dismissNotice,
                )
            }
        },
        transcript = {
            if (messages.size > 1) {
                item(key = "jump") {
                    Column {
                        ButtonMMD(
                            onClick = { jumpOpen = !jumpOpen },
                            modifier = Modifier.fillMaxWidth(),
                        ) { TextMMD(if (jumpOpen) "Jump ▴" else "Jump to message ▾") }
                        if (jumpOpen) {
                            messages.forEachIndexed { index, message ->
                                ListRow(
                                    title = "#${index + 1} · ${mdPreview(message.content, 42)}",
                                    subtitle = "${if (message.role == MessageRole.USER) "You" else "Assistant"} · ${message.createdAt.timeOfDay()}",
                                    onClick = {
                                        jumpOpen = false
                                        scope.launch { listState.scrollToItem(index + 1) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (messages.isEmpty()) {
                item(key = "empty") { ListRow(title = "No messages", subtitle = "Write below") }
            }
            itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                val isLast = index == messages.lastIndex
                ChatMessageRow(
                    message = message,
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
                item(key = "sending") { ListRow(title = "Assistant is replying…", trailing = "…") }
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
                        enabled = !sending,
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

/**
 * One message. Monochrome sender coding: user = right-shifted bordered card,
 * semi-bold; assistant = full-width plain text. Timestamp sits in the meta
 * line; pending/failed glyphs carry delivery state.
 */
@Composable
private fun ChatMessageRow(
    message: Message,
    expanded: Boolean,
    canRegenerate: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRevert: () -> Unit,
    onRegenerate: () -> Unit,
    onSaveNote: () -> Unit,
) {
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
    Column(Modifier.fillMaxWidth()) {
        if (message.role == MessageRole.USER) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                CardMMD(onClick = onClick, modifier = Modifier.fillMaxWidth(0.85f)) {
                    Column(Modifier.padding(12.dp)) {
                        MarkdownText(raw = message.content, baseFontWeight = FontWeight.SemiBold)
                        TextMMD(text = meta, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        } else {
            CardMMD(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    MarkdownText(raw = message.content)
                    TextMMD(text = meta, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message.role == MessageRole.USER) {
                    ButtonMMD(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        TextMMD("Edit")
                    }
                } else {
                    // T-022b: explicit transition — the reply text becomes
                    // an inbox note verbatim (source: this thread).
                    ButtonMMD(onClick = onSaveNote, modifier = Modifier.weight(1f)) {
                        TextMMD("Save as note")
                    }
                }
                if (canRegenerate) {
                    ButtonMMD(onClick = onRegenerate, modifier = Modifier.weight(1f)) {
                        TextMMD("Regenerate")
                    }
                }
                ButtonMMD(onClick = onRevert, modifier = Modifier.weight(1f)) {
                    TextMMD("Revert to here")
                }
            }
        }
    }
}
