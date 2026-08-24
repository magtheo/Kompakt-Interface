package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.AgentRepository
import dev.magnor.kompakt.data.repository.InboxRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.OrganizationRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunState
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.domain.TaskStatus
import dev.magnor.kompakt.ui.dayLabel
import dev.magnor.kompakt.ui.timeOfDay
import dev.magnor.kompakt.ui.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Organize surface — vault PARA projection, read-only in v0.1 (D004). */

class ProjectsViewModel(
    organizationRepository: OrganizationRepository,
    taskRepository: TaskRepository,
) : ViewModel() {

    data class ProjectRow(
        val project: Project,
        val openTasks: Int,
    )

    data class ProjectsUiState(
        val loaded: Boolean = false,
        val error: String? = null,
        val rows: List<ProjectRow> = emptyList(),
    )

    val state: StateFlow<ProjectsUiState> =
        combine(organizationRepository.observeProjects(), taskRepository.observeTasks()) { projects, tasks ->
            val openByProject = tasks
                .filter { it.status == TaskStatus.OPEN && it.projectId != null }
                .groupingBy { it.projectId!! }
                .eachCount()
            ProjectsUiState(
                loaded = true,
                rows = projects.map { ProjectRow(it, openByProject[it.id] ?: 0) },
            )
        }.catch { e -> emit(ProjectsUiState(loaded = true, error = e.userMessage())) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())
}

class AreasViewModel(
    organizationRepository: OrganizationRepository,
) : ViewModel() {

    data class AreasUiState(
        val loaded: Boolean = false,
        val error: String? = null,
        val areas: List<Area> = emptyList(),
    )

    val state: StateFlow<AreasUiState> = organizationRepository.observeAreas()
        .map { AreasUiState(loaded = true, areas = it) }
        .catch { e -> emit(AreasUiState(loaded = true, error = e.userMessage())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AreasUiState())
}

/**
 * Task list. `filter == All` renders the Today/Upcoming/Completed groups;
 * a project/area filter renders a flat open/completed list titled by the
 * filter target (dev plan §7: filter by project, filter by area).
 */
class TasksViewModel(
    taskRepository: TaskRepository,
    organizationRepository: OrganizationRepository,
    val now: Instant,
    filter: TaskFilter = TaskFilter.All,
) : ViewModel() {

    data class TasksUiState(
        val loaded: Boolean = false,
        val error: String? = null,
        /** Non-null when a project/area filter is active. */
        val filterTitle: String? = null,
        // grouped layout (filter == All)
        val today: List<Task> = emptyList(),
        val upcoming: List<Task> = emptyList(),
        // flat layout (filtered)
        val open: List<Task> = emptyList(),
        val completed: List<Task> = emptyList(),
    ) {
        val filtered: Boolean get() = filterTitle != null
    }

    private val endOfToday = LocalDate.fromEpochDays(now.toLocalDateTime(TimeZone.UTC).date.toEpochDays() + 1)
        .atStartOfDayIn(TimeZone.UTC)

    private val titleFlow: kotlinx.coroutines.flow.Flow<String?> = when (filter) {
        is TaskFilter.ByProject ->
            organizationRepository.observeProject(filter.projectId).map { it?.name }
        is TaskFilter.ByArea ->
            organizationRepository.observeArea(filter.areaId).map { it?.name }
        else -> flowOf(null)
    }

    val state: StateFlow<TasksUiState> =
        combine(taskRepository.observeTasks(filter), titleFlow) { list, title -> list to title }
            .map { (list, title) ->
                val open = list.filter { it.status == TaskStatus.OPEN }
                val completed = list.filter { it.status == TaskStatus.COMPLETED }
                if (title == null) {
                    TasksUiState(
                        loaded = true,
                        today = open
                            .filter { it.dueAt != null && it.dueAt!! < endOfToday }
                            .sortedBy { it.dueAt },
                        upcoming = open
                            .filter { it.dueAt == null || it.dueAt!! >= endOfToday }
                            .sortedWith(compareBy { it.dueAt ?: Instant.DISTANT_FUTURE }),
                        completed = completed,
                    )
                } else {
                    TasksUiState(
                        loaded = true,
                        filterTitle = title,
                        open = open.sortedWith(compareBy { it.dueAt ?: Instant.DISTANT_FUTURE }),
                        completed = completed,
                    )
                }
            }
            .catch { e -> emit(TasksUiState(loaded = true, error = e.userMessage())) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())
}

class NotesViewModel(
    noteRepository: NoteRepository,
    val now: Instant,
) : ViewModel() {

    data class NotesUiState(
        val loaded: Boolean = false,
        val error: String? = null,
        val notes: List<Note> = emptyList(),
    )

    val state: StateFlow<NotesUiState> = noteRepository.observeNotes()
        .map { NotesUiState(loaded = true, notes = it) }
        .catch { e -> emit(NotesUiState(loaded = true, error = e.userMessage())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())
}

/** Inbox — aggregated attention items; a view, not a source of truth. */
class InboxViewModel(
    private val inboxRepository: InboxRepository,
    val now: Instant,
) : ViewModel() {

    data class InboxUiState(
        val loaded: Boolean = false,
        val error: String? = null,
        val items: List<InboxItem> = emptyList(),
    )

    val state: StateFlow<InboxUiState> = inboxRepository.observeInbox()
        .map { InboxUiState(loaded = true, items = it) }
        .catch { e -> emit(InboxUiState(loaded = true, error = e.userMessage())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    /**
     * T-018: opening an agent-run alert marks it read server-side
     * (fire-and-forget — the list re-derives from the server on next load).
     * Derived alerts (no persisted record) clear themselves.
     */
    fun markOpened(item: InboxItem) {
        if (item.sourceType != EntityKind.AGENT_RUN) return
        viewModelScope.launch {
            runCatching {
                inboxRepository.dismiss(item.id, item.revision, "open-${item.id}")
            }
        }
    }
}

/**
 * Generic item detail — resolves an id across repositories and renders a
 * neutral summary. Tasks also show due date and owning project (joined
 * via the organization repository, T-006).
 */
class ItemDetailViewModel(
    taskRepository: TaskRepository,
    noteRepository: NoteRepository,
    agentRepository: AgentRepository,
    inboxRepository: InboxRepository,
    organizationRepository: OrganizationRepository,
    itemId: EntityId,
    val now: Instant,
) : ViewModel() {

    data class ItemUiState(
        val found: Boolean = false,
        val kind: String = "Unknown",
        val title: String = "—",
        val subtitle: String? = null,
        val status: String? = null,
        /** Human bucket for dueAt (Today / Tomorrow / Overdue / date + time). */
        val due: String? = null,
        /** Owning project name, when the object carries a project link. */
        val project: String? = null,
        val revision: Long? = null,
        val source: String? = null,
    )

    val state: StateFlow<ItemUiState> = combine(
        taskRepository.observeTask(itemId),
        noteRepository.observeNote(itemId),
        agentRepository.observeRun(itemId),
        inboxRepository.observeInbox().map { list -> list.firstOrNull { it.id == itemId } },
        organizationRepository.observeProjects(),
    ) { task, note, run, inboxItem, projects ->
        when {
            task != null -> ItemUiState(
                found = true, kind = "Task", title = task.title,
                subtitle = task.notes, status = task.status.wire,
                due = task.dueAt?.let { "${it.dayLabel(now)} ${it.timeOfDay()}" },
                project = task.projectId?.let { pid -> projects.firstOrNull { it.id == pid }?.name },
                revision = task.revision,
                source = task.sourceType?.let { "from ${it.wire} ${task.sourceId ?: ""}".trim() },
            )
            note != null -> ItemUiState(
                found = true, kind = "Note", title = note.preview,
                subtitle = note.text, revision = note.revision,
                project = note.projectId?.let { pid -> projects.firstOrNull { it.id == pid }?.name },
                source = note.sourceType?.let { "from ${it.wire} ${note.sourceId ?: ""}".trim() },
            )
            run != null -> ItemUiState(
                found = true, kind = "Agent run", title = run.displayTitle,
                subtitle = run.prompt, status = run.state.wire,
                source = "${run.agent} @ ${run.backend}",
            )
            inboxItem != null -> ItemUiState(
                found = true, kind = "Inbox", title = inboxItem.title,
                subtitle = inboxItem.summary, revision = inboxItem.revision,
                source = inboxItem.sourceType?.wire,
            )
            else -> ItemUiState()
        }
    }
        .catch { e -> emit(ItemUiState(found = false, title = e.userMessage())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemUiState())
}

/** Agent activity helper for screens that show runs compactly. */
fun AgentRun.isWaitingForInput(): Boolean =
    state == AgentRunState.WAITING_FOR_INPUT
