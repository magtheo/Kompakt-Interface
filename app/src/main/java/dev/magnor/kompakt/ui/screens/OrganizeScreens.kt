package dev.magnor.kompakt.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.ProjectStatus
import dev.magnor.kompakt.domain.TaskStatus
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.dayLabel
import dev.magnor.kompakt.ui.relativeTo
import dev.magnor.kompakt.ui.viewmodels.AreasViewModel
import dev.magnor.kompakt.ui.viewmodels.InboxViewModel
import dev.magnor.kompakt.ui.viewmodels.ItemDetailViewModel
import dev.magnor.kompakt.ui.viewmodels.NotesViewModel
import dev.magnor.kompakt.ui.viewmodels.ProjectDetailViewModel
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

/** Projects — read-only PARA projection (v0.1, D004). Rows open detail (T-022e). */
@Composable
fun ProjectsScreen(
    onBack: () -> Unit,
    onOpenProject: (EntityId) -> Unit = {},
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
                    onClick = { onOpenProject(row.project.id) },
                )
            }
        }
    }
}

/**
 * Project detail — the project's context (T-022e): workspace chats that bind
 * this project's repo + project notes; tasks one tap deeper (existing filter).
 */
@Composable
fun ProjectDetailScreen(
    projectId: EntityId,
    onBack: () -> Unit,
    onOpenChat: (EntityId) -> Unit,
    onOpenNote: (EntityId) -> Unit,
    onOpenTasks: () -> Unit,
    viewModel: ProjectDetailViewModel = containerViewModel(key = "project-$projectId") {
        ProjectDetailViewModel(
            organizationRepository = it.organizationRepository,
            noteRepository = it.noteRepository,
            chatRepository = it.chatRepository,
            projectId = projectId,
        )
    },
) {
    val state by viewModel.state.collectAsState()
    val project = state.project

    AppScreen(title = project?.name ?: "Project", onBack = onBack) {
        when {
            state.error != null -> ListRow(title = state.error ?: "Could not load")
            !state.loaded -> ListRow(title = "Loading…")
            project == null -> ListRow(title = "Project not found")
            else -> {
                ListRow(
                    title = "Tasks",
                    subtitle = listOfNotNull(
                        project.currentGoal ?: project.nextAction,
                    ).joinToString(" · ").ifEmpty { "Project-filtered task list" },
                    trailing = projectGlyph(project.status),
                    onClick = onOpenTasks,
                )

                state.chats.forEach { thread ->
                    ListRow(
                        title = thread.title,
                        subtitle = "Workspace chat",
                        onClick = { onOpenChat(thread.id) },
                    )
                }
                if (state.chats.isEmpty()) {
                    ListRow(title = "No workspace chats for this project")
                }

                state.notes.forEach { note ->
                    ListRow(
                        title = note.title ?: "Untitled",
                        subtitle = note.preview,
                        onClick = { onOpenNote(note.id) },
                    )
                }
                if (state.notes.isEmpty()) {
                    ListRow(
                        title = if (project.id.startsWith("machine:project:")) {
                            "Notes live in the vault — machine projects have none"
                        } else {
                            "No notes yet"
                        },
                    )
                }
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
    onOpenNote: (EntityId) -> Unit,
    onBack: () -> Unit,
    viewModel: NotesViewModel = containerViewModel { NotesViewModel(it.noteRepository, it.now()) },
) {
    val state by viewModel.state.collectAsState()

    AppScreen(title = "Notes", onBack = onBack) {
        when {
            state.error != null -> ListRow(title = state.error ?: "Could not load")
            !state.loaded -> ListRow(title = "Loading…")
            state.notes.isEmpty() -> {
                // Teaching empty state: the pipeline is the mental model.
                SectionLabel("Nothing here yet")
                ListRow(
                    title = "No notes — capture one with +",
                    subtitle = "Voice or text captures land in the scratchpad, sort themselves into 00 - Inbox, and you file them from there.",
                )
            }
            else -> {
                val scratchpad = state.scratchpad
                if (state.unprocessedCount > 0 && scratchpad != null) {
                    ListRow(
                        title = "Unprocessed (${state.unprocessedCount})",
                        subtitle = "New captures waiting in the scratchpad",
                        trailing = "•",
                        onClick = { onOpenNote(scratchpad.id) },
                    )
                }
                state.sections.forEach { section ->
                    SectionLabel(section.label)
                    section.notes.forEach { note ->
                        ListRow(
                            title = note.displayTitle,
                            subtitle = note.listPreview.takeIf { it.isNotBlank() && it != note.displayTitle },
                            trailing = note.updatedAt.relativeTo(viewModel.now),
                            onClick = { onOpenNote(note.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Inbox — aggregated attention; opening an item opens its source object. */
@Composable
fun InboxScreen(
    onOpenItem: (InboxItem) -> Unit,
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
                    onClick = {
                        viewModel.markOpened(item)
                        onOpenItem(item)
                    },
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
