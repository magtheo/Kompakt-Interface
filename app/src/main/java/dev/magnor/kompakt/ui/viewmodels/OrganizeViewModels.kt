package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.AgentRepository
import dev.magnor.kompakt.data.repository.InboxRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.OrganizationRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Organize surface — vault PARA projection, read-only in v0.1 (D004). */

class ProjectsViewModel(
    organizationRepository: OrganizationRepository,
) : ViewModel() {
    val projects: StateFlow<List<Project>> = organizationRepository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class AreasViewModel(
    organizationRepository: OrganizationRepository,
) : ViewModel() {
    val areas: StateFlow<List<Area>> = organizationRepository.observeAreas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class TasksViewModel(
    taskRepository: TaskRepository,
    val now: Instant,
) : ViewModel() {

    data class TaskGroups(
        val today: List<Task> = emptyList(),
        val upcoming: List<Task> = emptyList(),
        val completed: List<Task> = emptyList(),
    )

    private val endOfToday = LocalDate.fromEpochDays(now.toLocalDateTime(TimeZone.UTC).date.toEpochDays() + 1)
        .atStartOfDayIn(TimeZone.UTC)

    val groups: StateFlow<TaskGroups> = taskRepository.observeTasks()
        .map { list ->
            val open = list.filter { it.status == TaskStatus.OPEN }
            TaskGroups(
                today = open
                    .filter { it.dueAt != null && it.dueAt!! < endOfToday }
                    .sortedBy { it.dueAt },
                upcoming = open
                    .filter { it.dueAt == null || it.dueAt!! >= endOfToday }
                    .sortedBy { it.dueAt },
                completed = list.filter { it.status == TaskStatus.COMPLETED },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskGroups())
}

class NotesViewModel(
    noteRepository: NoteRepository,
    val now: Instant,
) : ViewModel() {
    val notes: StateFlow<List<Note>> = noteRepository.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Inbox — aggregated attention items; a view, not a source of truth. */
class InboxViewModel(
    inboxRepository: InboxRepository,
    val now: Instant,
) : ViewModel() {
    val items: StateFlow<List<InboxItem>> = inboxRepository.observeInbox()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Generic item detail — resolves an id across repositories and renders a
 * neutral summary. Real per-type detail screens land with their phases.
 */
class ItemDetailViewModel(
    taskRepository: TaskRepository,
    noteRepository: NoteRepository,
    agentRepository: AgentRepository,
    inboxRepository: InboxRepository,
    itemId: EntityId,
) : ViewModel() {

    data class ItemUiState(
        val found: Boolean = false,
        val kind: String = "Unknown",
        val title: String = "—",
        val subtitle: String? = null,
        val status: String? = null,
        val revision: Long? = null,
        val source: String? = null,
    )

    val state: StateFlow<ItemUiState> = combine(
        taskRepository.observeTask(itemId),
        noteRepository.observeNote(itemId),
        agentRepository.observeRun(itemId),
        inboxRepository.observeInbox().map { list -> list.firstOrNull { it.id == itemId } },
    ) { task, note, run, inboxItem ->
        when {
            task != null -> ItemUiState(
                found = true, kind = "Task", title = task.title,
                subtitle = task.notes, status = task.status.wire,
                revision = task.revision,
                source = task.sourceType?.let { "from ${it.wire} ${task.sourceId ?: ""}".trim() },
            )
            note != null -> ItemUiState(
                found = true, kind = "Note", title = note.preview,
                subtitle = note.text, revision = note.revision,
                source = note.sourceType?.let { "from ${it.wire} ${note.sourceId ?: ""}".trim() },
            )
            run != null -> ItemUiState(
                found = true, kind = "Agent run", title = run.title,
                subtitle = run.objective, status = run.status.wire,
                revision = run.revision,
                source = run.agentId,
            )
            inboxItem != null -> ItemUiState(
                found = true, kind = "Inbox", title = inboxItem.title,
                subtitle = inboxItem.summary, revision = inboxItem.revision,
                source = inboxItem.sourceType?.wire,
            )
            else -> ItemUiState()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemUiState())
}

/** Agent activity helper for screens that show runs compactly. */
fun AgentRun.isWaitingForInput(): Boolean =
    status == dev.magnor.kompakt.domain.AgentRunStatus.WAITING_FOR_INPUT
