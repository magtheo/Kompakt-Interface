package dev.magnor.kompakt.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.ProjectStatus
import dev.magnor.kompakt.domain.TaskStatus
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.dayLabel
import dev.magnor.kompakt.ui.relativeTo
import dev.magnor.kompakt.ui.viewmodels.AreasViewModel
import dev.magnor.kompakt.ui.viewmodels.InboxViewModel
import dev.magnor.kompakt.ui.viewmodels.ItemDetailViewModel
import dev.magnor.kompakt.ui.viewmodels.NotesViewModel
import dev.magnor.kompakt.ui.viewmodels.ProjectsViewModel
import dev.magnor.kompakt.ui.viewmodels.TasksViewModel

/** More — overflow menu for the fourth tab. */
@Composable
fun MoreScreen(
    onOpenOrganize: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenSettings: () -> Unit,
    showInbox: Boolean = true,
) {
    AppScreen(title = "More") {
        ListRow(title = "Organize", subtitle = "Projects · Areas · Tasks · Notes", onClick = onOpenOrganize)
        if (showInbox) {
            ListRow(title = "Inbox", subtitle = "Things from any subsystem that need you", onClick = onOpenInbox)
        }
        ListRow(title = "Settings", subtitle = "Enrollment · Sync · About", onClick = onOpenSettings)
    }
}

/** Organize — browse axes mirror the vault entity model (D004). */
@Composable
fun OrganizeScreen(
    onOpenProjects: () -> Unit,
    onOpenAreas: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenNotes: () -> Unit,
    showNotes: Boolean = true,
) {
    AppScreen(title = "Organize") {
        ListRow(title = "Projects", subtitle = "Outcome-bound work", onClick = onOpenProjects)
        ListRow(title = "Areas", subtitle = "Ongoing responsibilities", onClick = onOpenAreas)
        ListRow(title = "Tasks", subtitle = "Aggregated across adapters", onClick = onOpenTasks)
        if (showNotes) {
            ListRow(title = "Notes", subtitle = "Vault markdown", onClick = onOpenNotes)
        }
    }
}

private fun projectGlyph(status: ProjectStatus): String = when (status) {
    ProjectStatus.ACTIVE -> "●"
    ProjectStatus.PAUSED -> "○"
    ProjectStatus.DONE -> "✓"
    ProjectStatus.UNKNOWN -> "?"
}

/** Projects — read-only PARA projection (v0.1, D004). Rows drill into tasks. */
@Composable
fun ProjectsScreen(
    onBack: () -> Unit,
    onOpenProjectTasks: (EntityId) -> Unit = {},
    viewModel: ProjectsViewModel = containerViewModel {
        ProjectsViewModel(it.organizationRepository, it.taskRepository)
    },
) {
    val state by viewModel.state.collectAsState()

    AppScreen(title = "Projects", onBack = onBack) {
        when {
            state.error != null -> ListRow(title = state.error ?: "Could not load")
            !state.loaded -> ListRow(title = "Loading…")
            state.rows.isEmpty() -> ListRow(title = "No projects — vault decides (D004)")
            else -> state.rows.forEach { row ->
                val count = when (row.openTasks) {
                    0 -> "no open tasks"
                    1 -> "1 open task"
                    else -> "${row.openTasks} open tasks"
                }
                ListRow(
                    title = row.project.name,
                    subtitle = listOfNotNull(row.project.currentGoal ?: row.project.nextAction, count)
                        .joinToString(" · "),
                    trailing = projectGlyph(row.project.status),
                    onClick = { onOpenProjectTasks(row.project.id) },
                )
            }
        }
    }
}

/** Areas — read-only vault projection. Rows drill into area-filtered tasks. */
@Composable
fun AreasScreen(
    onBack: () -> Unit,
    onOpenAreaTasks: (EntityId) -> Unit = {},
    viewModel: AreasViewModel = containerViewModel { AreasViewModel(it.organizationRepository) },
) {
    val state by viewModel.state.collectAsState()

    AppScreen(title = "Areas", onBack = onBack) {
        when {
            state.error != null -> ListRow(title = state.error ?: "Could not load")
            !state.loaded -> ListRow(title = "Loading…")
            state.areas.isEmpty() -> ListRow(title = "No areas yet")
            else -> state.areas.forEach { area ->
                ListRow(
                    title = area.name,
                    subtitle = area.description.ifEmpty { null },
                    onClick = { onOpenAreaTasks(area.id) },
                )
            }
        }
    }
}

/**
 * Tasks — grouped (Today/Upcoming/Completed) for the unfiltered list, or a
 * flat open/completed list when scoped to a project/area (T-006).
 */
@Composable
fun TasksScreen(
    onOpenItem: (EntityId) -> Unit,
    onBack: () -> Unit,
    viewModel: TasksViewModel = containerViewModel {
        TasksViewModel(it.taskRepository, it.organizationRepository, it.now())
    },
) {
    val state by viewModel.state.collectAsState()
    val title = state.filterTitle?.let { "Tasks · $it" } ?: "Tasks"

    AppScreen(title = title, onBack = onBack) {
        when {
            state.error != null -> ListRow(title = state.error ?: "Could not load")
            !state.loaded -> ListRow(title = "Loading…")
            state.filtered -> {
                SectionLabel("Open")
                if (state.open.isEmpty()) {
                    ListRow(title = "No open tasks here")
                } else {
                    state.open.forEach { task ->
                        ListRow(
                            title = task.title,
                            subtitle = task.dueAt?.dayLabel(viewModel.now)?.let { "Due $it" },
                            trailing = "○",
                            onClick = { onOpenItem(task.id) },
                        )
                    }
                }
                if (state.completed.isNotEmpty()) {
                    SectionLabel("Completed")
                    state.completed.forEach { task ->
                        ListRow(title = task.title, trailing = "✓", onClick = { onOpenItem(task.id) })
                    }
                }
            }
            else -> {
                SectionLabel("Today")
                if (state.today.isEmpty()) {
                    ListRow(title = "Nothing due today")
                } else {
                    state.today.forEach { task ->
                        ListRow(
                            title = task.title,
                            trailing = if (task.status == TaskStatus.COMPLETED) "✓" else "○",
                            onClick = { onOpenItem(task.id) },
                        )
                    }
                }

                SectionLabel("Upcoming")
                if (state.upcoming.isEmpty()) {
                    ListRow(title = "No upcoming tasks")
                } else {
                    state.upcoming.forEach { task ->
                        ListRow(
                            title = task.title,
                            subtitle = task.dueAt?.dayLabel(viewModel.now)?.let { "Due $it" },
                            trailing = "○",
                            onClick = { onOpenItem(task.id) },
                        )
                    }
                }

                if (state.completed.isNotEmpty()) {
                    SectionLabel("Completed")
                    state.completed.forEach { task ->
                        ListRow(title = task.title, trailing = "✓", onClick = { onOpenItem(task.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun NotesScreen(
    onOpenItem: (EntityId) -> Unit,
    onBack: () -> Unit,
    viewModel: NotesViewModel = containerViewModel { NotesViewModel(it.noteRepository, it.now()) },
) {
    val state by viewModel.state.collectAsState()

    AppScreen(title = "Notes", onBack = onBack) {
        when {
            state.error != null -> ListRow(title = state.error ?: "Could not load")
            !state.loaded -> ListRow(title = "Loading…")
            state.notes.isEmpty() -> ListRow(title = "No notes — capture one via +")
            else -> state.notes.forEach { note ->
                ListRow(
                    title = note.preview,
                    subtitle = note.updatedAt.relativeTo(viewModel.now),
                    onClick = { onOpenItem(note.id) },
                )
            }
        }
    }
}

/** Inbox — aggregated attention; opening an item opens its source object. */
@Composable
fun InboxScreen(
    onOpenItem: (EntityId) -> Unit,
    onBack: () -> Unit,
    viewModel: InboxViewModel = containerViewModel { InboxViewModel(it.inboxRepository, it.now()) },
) {
    val state by viewModel.state.collectAsState()

    AppScreen(title = "Inbox", onBack = onBack) {
        when {
            state.error != null -> ListRow(title = state.error ?: "Could not load")
            !state.loaded -> ListRow(title = "Loading…")
            state.items.isEmpty() -> ListRow(title = "Inbox empty — all clear")
            else -> state.items.forEach { item ->
                ListRow(
                    title = item.title,
                    subtitle = item.timestamp.relativeTo(viewModel.now),
                    trailing = "●",
                    onClick = { onOpenItem(item.sourceId ?: item.id) },
                )
            }
        }
    }
}

/** Generic item detail — resolves ids across repositories until per-type screens land. */
@Composable
fun ItemDetailScreen(
    itemId: EntityId,
    onBack: () -> Unit,
    viewModel: ItemDetailViewModel = containerViewModel(key = "item-$itemId") {
        ItemDetailViewModel(
            taskRepository = it.taskRepository,
            noteRepository = it.noteRepository,
            agentRepository = it.agentRepository,
            inboxRepository = it.inboxRepository,
            organizationRepository = it.organizationRepository,
            itemId = itemId,
            now = it.now(),
        )
    },
) {
    val state by viewModel.state.collectAsState()

    AppScreen(title = state.kind, onBack = onBack) {
        if (!state.found) {
            ListRow(title = "Not found", subtitle = state.title.takeIf { it != "—" })
            return@AppScreen
        }
        DetailRow(label = "Title", value = state.title)
        state.subtitle?.let { DetailRow(label = "Detail", value = it) }
        state.status?.let { DetailRow(label = "Status", value = it) }
        state.due?.let { DetailRow(label = "Due", value = it) }
        state.project?.let { DetailRow(label = "Project", value = it) }
        state.revision?.let { DetailRow(label = "Revision", value = it.toString()) }
        state.source?.let { DetailRow(label = "Source", value = it) }
    }
}
