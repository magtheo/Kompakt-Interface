package dev.magnor.kompakt.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.RowScope
import dev.magnor.kompakt.domain.TaskStatus
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.relativeTo
import dev.magnor.kompakt.ui.timeOfDay
import dev.magnor.kompakt.ui.viewmodels.TodayViewModel

/** Today — operational overview. A view, not a source of truth. */
@Composable
fun TodayScreen(
    onOpenInbox: () -> Unit,
    viewModel: TodayViewModel = containerViewModel { TodayViewModel(it.todayRepository, it.now()) },
) {
    val state by viewModel.state.collectAsState()
    val projection = state.projection

    AppScreen(
        title = "Today",
        actions = {
            IconButton(onClick = onOpenInbox) {
                Icon(Icons.Filled.Notifications, contentDescription = "Inbox")
            }
        },
    ) {
        SectionLabel("Next")
        if (projection?.events.isNullOrEmpty()) {
            ListRow(title = "No more events today")
        } else {
            projection?.events?.forEach { event ->
                ListRow(title = event.title, trailing = event.startAt.timeOfDay())
            }
        }

        SectionLabel("Tasks")
        if (projection?.tasks.isNullOrEmpty()) {
            ListRow(title = "Nothing due today")
        } else {
            projection?.tasks?.forEach { task ->
                ListRow(
                    title = task.title,
                    trailing = if (task.status == TaskStatus.COMPLETED) "✓" else "○",
                )
            }
        }

        SectionLabel("Needs attention")
        if (projection?.attention.isNullOrEmpty()) {
            ListRow(title = "All clear")
        } else {
            projection?.attention?.forEach { item ->
                ListRow(
                    title = item.title,
                    subtitle = item.summary,
                    trailing = "●",
                    onClick = onOpenInbox,
                )
            }
        }

        SectionLabel("Agents")
        if (projection?.agentActivity.isNullOrEmpty()) {
            ListRow(title = "No active agents")
        } else {
            projection?.agentActivity?.forEach { run ->
                ListRow(
                    title = run.title,
                    subtitle = run.resultSummary,
                    trailing = if (run.requiresInput) "!" else "●",
                )
            }
        }

        SectionLabel("Recent note")
        projection?.recentNote?.let { note ->
            ListRow(
                title = note.preview,
                subtitle = "Vault · ${note.updatedAt.relativeTo(viewModel.now)}",
            )
        } ?: ListRow(title = "No recent notes")
    }
}
