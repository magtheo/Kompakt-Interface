package dev.magnor.kompakt.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.RowScope
import dev.magnor.kompakt.data.MockData

/** Today — operational overview. A view, not a source of truth. */
@Composable
fun TodayScreen(onOpenInbox: () -> Unit) {
    AppScreen(
        title = "Today",
        actions = {
            IconButton(onClick = onOpenInbox) {
                Icon(Icons.Filled.Notifications, contentDescription = "Inbox")
            }
        },
    ) {
        SectionLabel("Next")
        MockData.todayEvents.forEach { (time, title) ->
            ListRow(title = title, trailing = time)
        }

        SectionLabel("Tasks")
        MockData.todayTasks.forEach { (title, done) ->
            ListRow(title = title, trailing = if (done) "✓" else "○")
        }

        SectionLabel("Needs attention")
        MockData.attention.forEach { item ->
            ListRow(title = item, trailing = "●", onClick = onOpenInbox)
        }

        SectionLabel("Recent note")
        ListRow(title = MockData.recentNote, subtitle = "Vault · 2h ago")
    }
}
