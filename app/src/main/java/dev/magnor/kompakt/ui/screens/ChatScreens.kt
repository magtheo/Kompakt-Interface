package dev.magnor.kompakt.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.MessageRole
import dev.magnor.kompakt.domain.MessageStatus
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.relativeTo
import dev.magnor.kompakt.ui.viewmodels.ChatListViewModel
import dev.magnor.kompakt.ui.viewmodels.ChatThreadViewModel

/** Chat list — conversations are distinct from agents and tasks (D003). */
@Composable
fun ChatListScreen(
    onOpenThread: (EntityId) -> Unit,
    viewModel: ChatListViewModel = containerViewModel { ChatListViewModel(it.chatRepository, it.now()) },
) {
    val threads by viewModel.threads.collectAsState()

    AppScreen(title = "Chats") {
        if (threads.isEmpty()) {
            ListRow(title = "No chats yet", subtitle = "Create one via Capture")
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

/** Chat thread — renders the server-side conversation for this device. */
@Composable
fun ChatThreadScreen(
    threadId: EntityId,
    onBack: () -> Unit,
    viewModel: ChatThreadViewModel = containerViewModel(key = "chat-$threadId") {
        ChatThreadViewModel(it.chatRepository, threadId)
    },
) {
    val thread by viewModel.thread.collectAsState()
    val messages by viewModel.messages.collectAsState()

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
            ListRow(title = "No messages", subtitle = "Say something in Phase 7")
        }
        SectionLabel("Reply")
        ListRow(title = "Text input arrives in Phase 7 (Chat)", subtitle = "Placeholder")
    }
}
