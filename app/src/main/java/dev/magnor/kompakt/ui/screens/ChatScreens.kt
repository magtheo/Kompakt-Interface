package dev.magnor.kompakt.ui.screens

import androidx.compose.runtime.Composable
import dev.magnor.kompakt.data.MockData

/** Chat list — conversations are distinct from agents and tasks (D003). */
@Composable
fun ChatListScreen(onOpenThread: (String) -> Unit) {
    AppScreen(title = "Chats") {
        MockData.chatThreads.forEachIndexed { index, (title, snippet, time) ->
            ListRow(
                title = title,
                subtitle = snippet,
                trailing = time,
                onClick = { onOpenThread("thread-${index + 1}") },
            )
        }
    }
}

/** Chat thread — placeholder conversation with mocked messages. */
@Composable
fun ChatThreadScreen(onBack: () -> Unit) {
    AppScreen(title = "General", onBack = onBack) {
        MockData.chatMessages.forEach { (author, text) ->
            CardMMDRow(author = author, text = text)
        }
        SectionLabel("Reply")
        ListRow(title = "Text input arrives in Phase 7 (Chat)", subtitle = "Placeholder")
    }
}

@Composable
private fun CardMMDRow(author: String, text: String) {
    ListRow(title = author, subtitle = text)
}
