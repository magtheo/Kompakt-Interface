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
) {
    AppScreen(title = "More") {
        ListRow(title = "Organize", subtitle = "Projects · Areas · Tasks · Notes", onClick = onOpenOrganize)
        ListRow(title = "Inbox", subtitle = "Things from any subsystem that need you", onClick = onOpenInbox)
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
) {
    AppScreen(title = "Organize") {
        ListRow(title = "Projects", subtitle = "Outcome-bound work", onClick = onOpenProjects)
        ListRow(title = "Areas", subtitle = "Ongoing responsibilities", onClick = onOpenAreas)
        ListRow(title = "Tasks", subtitle = "Aggregated across adapters", onClick = onOpenTasks)
        ListRow(title = "Notes", subtitle = "Vault markdown", onClick = onOpenNotes)
    }
}

private fun projectGlyph(status: ProjectStatus): String = when (status) {
    ProjectStatus.ACTIVE -> "●"
    ProjectStatus.PAUSED -> "○"
    ProjectStatus.DONE -> "✓"
    ProjectStatus.UNKNOWN -> "?"
}

/** Projects — read-only PARA projection (v0.1, D004). */
@Composable
fun ProjectsScreen(
    onBack: () -> Unit,
    viewModel: ProjectsViewModel = containerViewModel { ProjectsViewModel(it.organizationRepository) },
) {
    val projects by viewModel.projects.collectAsState()

    AppScreen(title = "Projects", onBack = onBack) {
        projects.forEach { project ->
            ListRow(
                title = project.name,
                subtitle = project.currentGoal ?: project.nextAction,
                trailing = projectGlyph(project.status),
            )
        }
        if (projects.isEmpty()) {
            ListRow(title = "No projects — vault decides (D004)")
        }
    }
}

@Composable
fun AreasScreen(
    onBack: () -> Unit,
    viewModel: AreasViewModel = containerViewModel { AreasViewModel(it.organizationRepository) },
) {
    val areas by viewModel.areas.collectAsState()

    AppScreen(title = "Areas", onBack = onBack) {
        areas.forEach { area ->
            ListRow(title = area.name, subtitle = area.description)
        }
        if (areas.isEmpty()) {
            ListRow(title = "No areas yet")
        }
    }
}

@Composable
fun TasksScreen(
    onOpenItem: (EntityId) -> Unit,
    onBack: () -> Unit,
    viewModel: TasksViewModel = containerViewModel { TasksViewModel(it.taskRepository, it.now()) },
) {
    val groups by viewModel.groups.collectAsState()

    AppScreen(title = "Tasks", onBack = onBack) {
        SectionLabel("Today")
        groups.today.forEach { task ->
            ListRow(
                title = task.title,
                trailing = if (task.status == TaskStatus.COMPLETED) "✓" else "○",
                onClick = { onOpenItem(task.id) },
            )
        }
        if (groups.today.isEmpty()) ListRow(title = "Nothing due today")

        SectionLabel("Upcoming")
        groups.upcoming.forEach { task ->
            ListRow(
                title = task.title,
                subtitle = task.dueAt?.dayLabel(viewModel.now)?.let { "Due $it" },
                trailing = "○",
                onClick = { onOpenItem(task.id) },
            )
        }
        if (groups.upcoming.isEmpty()) ListRow(title = "No upcoming tasks")

        if (groups.completed.isNotEmpty()) {
            SectionLabel("Completed")
            groups.completed.forEach { task ->
                ListRow(title = task.title, trailing = "✓", onClick = { onOpenItem(task.id) })
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
    val notes by viewModel.notes.collectAsState()

    AppScreen(title = "Notes", onBack = onBack) {
        notes.forEach { note ->
            ListRow(
                title = note.preview,
                subtitle = note.updatedAt.relativeTo(viewModel.now),
                onClick = { onOpenItem(note.id) },
            )
        }
        if (notes.isEmpty()) {
            ListRow(title = "No notes — capture one via +")
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
    val items by viewModel.items.collectAsState()

    AppScreen(title = "Inbox", onBack = onBack) {
        items.forEach { item ->
            ListRow(
                title = item.title,
                subtitle = item.timestamp.relativeTo(viewModel.now),
                trailing = "●",
                onClick = { onOpenItem(item.sourceId ?: item.id) },
            )
        }
        if (items.isEmpty()) {
            ListRow(title = "Inbox empty — all clear")
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
            itemId = itemId,
        )
    },
) {
    val state by viewModel.state.collectAsState()

    AppScreen(title = state.kind, onBack = onBack) {
        if (!state.found) {
            ListRow(title = "Not found", subtitle = "May be deleted on the server")
            return@AppScreen
        }
        DetailRow(label = "Title", value = state.title)
        state.subtitle?.let { DetailRow(label = "Detail", value = it) }
        state.status?.let { DetailRow(label = "Status", value = it) }
        state.revision?.let { DetailRow(label = "Revision", value = it.toString()) }
        state.source?.let { DetailRow(label = "Source", value = it) }
        SectionLabel("Type-specific screens arrive with their phases")
    }
}
