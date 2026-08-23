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
import dev.magnor.kompakt.domain.AgentRunState
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.relativeTo
import dev.magnor.kompakt.ui.timeOfDay
import dev.magnor.kompakt.ui.viewmodels.TodayViewModel

/**
 * Today — operational overview. A view, not a source of truth.
 * Sections whose server feature flag is off are not rendered (protocol §9).
 */
@Composable
fun TodayScreen(
    onOpenInbox: () -> Unit,
    showInboxAction: Boolean = true,
    showAgentsSection: Boolean = true,
    showRecentNote: Boolean = true,
    viewModel: TodayViewModel = containerViewModel { TodayViewModel(it.todayRepository, it.now()) },
) {
    val state by viewModel.state.collectAsState()
    val projection = state.projection

    AppScreen(
        title = "Today",
        actions = {
            if (showInboxAction) {
                IconButton(onClick = onOpenInbox) {
                    Icon(Icons.Filled.Notifications, contentDescription = "Inbox")
                }
            }
        },
    ) {
        when {
            // Static error line — E-Ink rule: no spinners, reopening refetches.
            projection == null && state.error != null ->
                ListRow(title = state.error ?: "Could not load")
            projection == null -> ListRow(title = "Loading…")
            else -> {
                SectionLabel("Next")
                if (projection.events.isEmpty()) {
                    ListRow(title = "No more events today")
                } else {
                    projection.events.forEach { event ->
                        ListRow(title = event.title, trailing = event.startAt.timeOfDay())
                    }
                }

                SectionLabel("Tasks")
                if (projection.tasks.isEmpty()) {
                    ListRow(title = "Nothing due today")
                } else {
                    projection.tasks.forEach { task ->
                        ListRow(
                            title = task.title,
                            trailing = if (task.status == TaskStatus.COMPLETED) "✓" else "○",
                        )
                    }
                }

                SectionLabel("Needs attention")
                if (projection.attention.isEmpty()) {
                    ListRow(title = "All clear")
                } else {
                    projection.attention.forEach { item ->
                        ListRow(
                            title = item.title,
                            subtitle = item.summary,
                            trailing = "●",
                            onClick = onOpenInbox,
                        )
                    }
                }

                if (showAgentsSection) {
                    SectionLabel("Agents")
                    if (projection.agentActivity.isEmpty()) {
                        ListRow(title = "No active agents")
                    } else {
                        projection.agentActivity.forEach { run ->
                            ListRow(
                                title = run.displayTitle,
                                subtitle = run.resultSummary,
                                trailing = if (run.state == AgentRunState.WAITING_FOR_INPUT) "!" else "●",
                            )
                        }
                    }
                }

                if (showRecentNote) {
                    SectionLabel("Recent note")
                    projection.recentNote?.let { note ->
                        ListRow(
                            title = note.preview,
                            subtitle = "Vault · ${note.updatedAt.relativeTo(viewModel.now)}",
                        )
                    } ?: ListRow(title = "No recent notes")
                }
            }
        }
    }
}
