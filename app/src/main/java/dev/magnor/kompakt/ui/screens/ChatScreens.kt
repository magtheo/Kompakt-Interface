package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.MessageRole
import dev.magnor.kompakt.domain.MessageStatus
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.relativeTo
import dev.magnor.kompakt.ui.viewmodels.ChatListViewModel
import dev.magnor.kompakt.ui.viewmodels.ChatSendState
import dev.magnor.kompakt.ui.viewmodels.ChatThreadViewModel

/** Chat list — conversations are distinct from agents and tasks (D003). */
@Composable
fun ChatListScreen(
    onOpenThread: (EntityId) -> Unit,
    viewModel: ChatListViewModel = containerViewModel {
        ChatListViewModel(it.chatRepository, it::nextRequestId, it.now())
    },
) {
    val threads by viewModel.threads.collectAsState()
    val created by viewModel.created.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(created) {
        created?.let { id ->
            viewModel.consumeCreated()
            onOpenThread(id)
        }
    }

    AppScreen(title = "Chats") {
        ListRow(title = "New chat", subtitle = "Start a conversation", onClick = viewModel::newChat)
        error?.let { ListRow(title = it, trailing = "!") }
        if (threads.isEmpty()) {
            ListRow(title = "No chats yet", subtitle = "Tap New chat above")
        } else {
            threads.forEach { thread ->
                ListRow(
                    title = thread.title,
                    subtitle = thread.lastMessagePreview,
                    trailing = thread.updatedAt.relativeTo(viewModel.now),
                    onClick = { onOpenThread(thread.id) },
                )
            }
        }
    }
}

/** Chat thread — renders the server-side conversation; replies are server-generated (D024). */
@Composable
fun ChatThreadScreen(
    threadId: EntityId,
    onBack: () -> Unit,
    viewModel: ChatThreadViewModel = containerViewModel(key = "chat-$threadId") {
        ChatThreadViewModel(it.chatRepository, threadId, it::nextRequestId, it.clock)
    },
) {
    val thread by viewModel.thread.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val sendState by viewModel.sendState.collectAsState()
    val draft by viewModel.draft.collectAsState()

    AppScreen(title = thread?.title ?: "Chat", onBack = onBack) {
        messages.forEach { message ->
            ListRow(
                title = if (message.role == MessageRole.USER) "You" else "Assistant",
                subtitle = message.content,
                trailing = when (message.status) {
                    MessageStatus.PENDING -> "…"
                    MessageStatus.FAILED -> "!"
                    else -> null
                },
            )
        }
        if (messages.isEmpty()) {
            ListRow(title = "No messages", subtitle = "Write below")
        }

        val sending = sendState is ChatSendState.Sending
        SectionLabel("Reply")
        OutlinedTextField(
            value = draft,
            onValueChange = viewModel::onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { TextMMD("Message") },
            enabled = !sending,
            singleLine = false,
            maxLines = 4,
        )
        if (sending) {
            ListRow(title = "Assistant is replying…", trailing = "…")
        }
        (sendState as? ChatSendState.Failed)?.let { failed ->
            ListRow(
                title = failed.reason ?: "Send failed",
                subtitle = "Message kept below — press Send to retry",
                trailing = "!",
                onClick = viewModel::dismissError,
            )
        }
        ButtonMMD(
            onClick = viewModel::send,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            enabled = draft.isNotBlank() && !sending,
        ) { TextMMD(if (sending) "Sending…" else "Send") }
    }
}
