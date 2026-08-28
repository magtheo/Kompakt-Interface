package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.AgentRepository
import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.data.repository.InboxRepository
import dev.magnor.kompakt.data.repository.NoteRepository
import dev.magnor.kompakt.data.repository.OrganizationRepository
import dev.magnor.kompakt.data.repository.TaskRepository
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunState
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.NoteConflictException
import dev.magnor.kompakt.domain.NoteDraft
import dev.magnor.kompakt.domain.NoteSection
import dev.magnor.kompakt.domain.NoteSections
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.TaskDraft
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.domain.TaskStatus
import dev.magnor.kompakt.ui.dayLabel
import dev.magnor.kompakt.ui.relativeTo
import dev.magnor.kompakt.ui.timeOfDay
import dev.magnor.kompakt.ui.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        combine(
            organizationRepository.observeProjects(),
            // T-024: open-count join is display-only — degrade to zero
            // counts on failure instead of killing the projects list.
            taskRepository.observeTasks().catch { emit(emptyList()) },
        ) { projects, tasks ->
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
        // T-024: the title join is display-only — a failing projects/areas
        // read (e.g. missing project.read, Aug 27 incident) must degrade the
        // filter title to null, never collapse the task list itself.
        is TaskFilter.ByProject ->
            organizationRepository.observeProject(filter.projectId)
                .map { it?.name }
                .catch { emit(null) }
        is TaskFilter.ByArea ->
            organizationRepository.observeArea(filter.areaId)
                .map { it?.name }
                .catch { emit(null) }
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
    private val noteRepository: NoteRepository,
    val now: Instant,
) : ViewModel() {

    /** Category bucket in the list below the pinned scratchpad. */
    data class NotesSection(
        val label: String,
        val notes: List<Note>,
    )

    data class NotesUiState(
        val loaded: Boolean = false,
        val error: String? = null,
        val notes: List<Note> = emptyList(),
        /** Pinned scratchpad row — the unprocessed queue (stage 1). */
        val scratchpad: Note? = null,
        /** Sections after the scratchpad, grouped by category. */
        val sections: List<NotesSection> = emptyList(),
        /** Capture sections currently sitting in the scratchpad. */
        val unprocessedCount: Int = 0,
    )

    val state: StateFlow<NotesUiState> = noteRepository.observeNotes()
        .map { raw -> derive(raw) }
        .catch { e -> emit(NotesUiState(loaded = true, error = e.userMessage())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    /**
     * Scratchpad pinned (as its Unprocessed header), everything else
     * grouped by category with Inbox first. The unprocessed count needs
     * the scratchpad detail (section headings) — one extra request per
     * list load; a failure just yields 0, never blocks the list.
     */
    private suspend fun derive(raw: List<Note>): NotesUiState {
        val scratchpad = raw.firstOrNull { it.isScratchpad }
        val rest = raw.filterNot { it.isScratchpad }
        val unprocessed = scratchpad
            ?.let { runCatching { noteRepository.getNote(it.id) }.getOrNull() }
            ?.text.orEmpty()
            .lineSequence()
            .count { it.startsWith("## ") }
        val sections = rest
            .groupBy { it.category?.takeIf { c -> c.isNotBlank() } ?: "Notes" }
            .map { (label, notes) ->
                NotesSection(label, notes.sortedByDescending { it.updatedAt })
            }
            .sortedWith(
                compareBy({ if (it.label.equals("Inbox", ignoreCase = true)) 0 else 1 }, { it.label }),
            )
        return NotesUiState(
            loaded = true,
            notes = raw,
            scratchpad = scratchpad,
            sections = sections,
            unprocessedCount = unprocessed,
        )
    }
}

/**
 * T-022a: full-text note editor with the checksum optimistic lock (D028 v2).
 * On 409 the user's text is NEVER replaced — the fresh checksum is adopted
 * so a second Save overwrites, and Reload is the only path that pulls the
 * server version into the editor (wording contract: explicit user action).
 */
class NoteEditorViewModel(
    private val noteRepository: NoteRepository,
    private val taskRepository: TaskRepository,
    private val organizationRepository: OrganizationRepository,
    private val noteId: EntityId,
    private val newRequestId: () -> RequestId,
    private val now: () -> Instant,
) : ViewModel() {

    data class EditorUiState(
        val loading: Boolean = true,
        val notFound: Boolean = false,
        val title: String = "Note",
        val text: String = "",
        /** sha256 the text was loaded under — the save lock. */
        val checksum: String? = null,
        /** Read-view meta: category bucket + origin line (12sp dim idiom). */
        val category: String? = null,
        val source: String? = null,
        val relativeUpdated: String = "",
        val dirty: Boolean = false,
        val saving: Boolean = false,
        /** 409 seen — banner stays until Reload or a successful save. */
        val conflict: Boolean = false,
        val savedAt: Instant? = null,
        val error: String? = null,
        /** T-022b: pipeline role — triage UI shows for scratchpad/inbox docs. */
        val role: String? = null,
        /** T-022b: `## ` sections of the loaded text (triage cards). */
        val sections: List<NoteSection> = emptyList(),
        val triageBusy: Boolean = false,
        val triageNotice: String? = null,
    ) {
        val canSave: Boolean get() = !loading && !notFound && dirty && !saving
        val triageable: Boolean
            get() = role == Note.ROLE_SCRATCHPAD || role == Note.ROLE_INBOX
    }

    private var loadedText: String = ""

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        reload()
    }

    /** Fetch server text + checksum; also the conflict-banner Reload action.
     *  [keepNotice] carries an in-flight triage notice across the state rebuild
     *  (the T-022b conflict path reloads while telling the user what happened). */
    fun reload(keepNotice: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val note = noteRepository.getNote(noteId)
                loadedText = note?.text.orEmpty()
                _state.value = if (note == null) {
                    EditorUiState(loading = false, notFound = true)
                } else {
                    EditorUiState(
                        loading = false,
                        title = note.displayTitle,
                        text = loadedText,
                        checksum = note.checksum,
                        category = note.category,
                        source = note.sourceType?.let { "from ${it.wire} ${note.sourceId ?: ""}".trim() },
                        relativeUpdated = note.updatedAt.relativeTo(now()),
                        role = note.role,
                        sections = NoteSections.parse(loadedText),
                    ).let { fresh ->
                        if (keepNotice) fresh.copy(triageNotice = _state.value.triageNotice) else fresh
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.userMessage())
            }
        }
    }

    fun onTextChange(text: String) {
        _state.value = _state.value.copy(text = text, dirty = text != loadedText)
    }

    fun save() {
        val lock = _state.value.checksum
        if (lock == null) {
            _state.value = _state.value.copy(error = "This note cannot be saved from here")
            return
        }
        if (!_state.value.canSave) return
        _state.value = _state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val updated = noteRepository.updateNote(noteId, _state.value.text, lock)
                loadedText = _state.value.text
                _state.value = _state.value.copy(
                    saving = false,
                    savedAt = now(),
                    dirty = false,
                    conflict = false,
                    checksum = updated.checksum,
                )
            } catch (e: NoteConflictException) {
                // Server moved on (e.g. sorter sweep). Keep the user's text,
                // adopt the fresh checksum so a second Save overwrites.
                _state.value = _state.value.copy(
                    saving = false,
                    conflict = true,
                    checksum = e.fresh.checksum,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(saving = false, error = e.userMessage())
            }
        }
    }

    // ---- T-022b: section triage (D028 v2 stage 3 — explicit user actions) ----

    /**
     * "New note in…" targets: Inbox + vault projects only — the server
     * (V-064) 422s machine projects, so they are never offered (T-022e rule).
     */
    val saveTargets: StateFlow<List<Project>> = organizationRepository.observeProjects()
        .map { projects -> projects.filter { it.id.startsWith("vault:project:") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "Append to…" targets: any note except this doc and the scratchpad. */
    val appendTargets: StateFlow<List<Note>> = noteRepository.observeNotes()
        .map { notes ->
            notes.filter { it.id != noteId && it.role != Note.ROLE_SCRATCHPAD }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * File the section as its own note, then remove it from this doc.
     * Create FIRST (the new file is the external effect) — a crash between
     * the two calls duplicates the section, which is the safe direction.
     * Triage-filed notes carry no source_type: provenance is the file
     * location + git history (server accepts {chat, agent_run, capture,
     * triage} — none match a client EntityKind, deliberately not forced).
     */
    fun newNoteIn(section: NoteSection, target: Project?) = triage { state ->
        noteRepository.createNote(
            NoteDraft(
                text = section.body.ifBlank { section.title },
                projectId = target?.id,
            ),
            newRequestId(),
        )
        putSourceWithout(section)
        if (target == null) "Filed to inbox: ${section.title}" else "Filed to ${target.name}: ${section.title}"
    }

    /**
     * Append the section verbatim (heading + body) to an existing note,
     * then remove it from this doc. Target PUT FIRST — a crash between the
     * two PUTs duplicates the section, safe direction (documented).
     */
    fun appendTo(section: NoteSection, target: Note) = triage { state ->
        val fresh = noteRepository.getNote(target.id)
            ?: error("${target.displayTitle} is gone — pick another note")
        val withSection = fresh.text.orEmpty().trimEnd() +
            "\n\n## ${section.heading}" +
            if (section.body.isBlank()) "" else "\n${section.body}"
        noteRepository.updateNote(target.id, withSection, fresh.checksum!!)
        putSourceWithout(section)
        "Appended to ${target.displayTitle}"
    }

    /** Derive a task from the section title — the section is NOT consumed. */
    fun createTaskFrom(section: NoteSection) = triage { state ->
        val task = taskRepository.createTask(
            TaskDraft(
                title = section.title,
                sourceType = EntityKind.NOTE,
                sourceId = noteId,
            ),
            newRequestId(),
        )
        "Task created: ${task.title}"
    }

    /** Remove the section from this doc. Git history is the undo. */
    fun discard(section: NoteSection) = triage { state ->
        putSourceWithout(section)
        "Discarded: ${section.title}"
    }

    fun dismissTriageNotice() {
        _state.value = _state.value.copy(triageNotice = null)
    }

    /** PUT this doc minus the section, under the loaded checksum. */
    private suspend fun putSourceWithout(section: NoteSection) {
        val text = NoteSections.remove(loadedText, section)
        val updated = noteRepository.updateNote(noteId, text, _state.value.checksum!!)
        loadedText = text
        _state.value = _state.value.copy(
            text = text,
            checksum = updated.checksum,
            sections = NoteSections.parse(text),
        )
    }

    private fun triage(label: suspend (EditorUiState) -> String) {
        val state = _state.value
        if (state.triageBusy || state.notFound || state.checksum == null) return
        _state.value = state.copy(triageBusy = true, triageNotice = null)
        viewModelScope.launch {
            try {
                val message = label(state)
                _state.value = _state.value.copy(triageBusy = false, triageNotice = message)
            } catch (e: NoteConflictException) {
                _state.value = _state.value.copy(
                    triageBusy = false,
                    triageNotice = "Changed on the server — reloaded the current version",
                )
                reload(keepNotice = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(triageBusy = false, triageNotice = "Failed: ${e.userMessage()}")
            }
        }
    }
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
    kind: EntityKind? = null,
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

    private fun taskUi(task: Task?, projects: List<Project>) = if (task == null) {
        ItemUiState()
    } else {
        ItemUiState(
            found = true, kind = "Task", title = task.title,
            subtitle = task.notes, status = task.status.wire,
            due = task.dueAt?.let { "${it.dayLabel(now)} ${it.timeOfDay()}" },
            project = task.projectId?.let { pid -> projects.firstOrNull { it.id == pid }?.name },
            revision = task.revision,
            source = task.sourceType?.let { "from ${it.wire} ${task.sourceId ?: ""}".trim() },
        )
    }

    /**
     * T-025: with a known kind the screen fetches the ONE typed
     * repository. The projects list is a display join only — losing it
     * (403, offline) degrades the name to null, never the entity itself.
     */
    val state: StateFlow<ItemUiState> =
        if (kind == EntityKind.TASK) {
            combine(
                taskRepository.observeTask(itemId),
                organizationRepository.observeProjects().catch { emit(emptyList()) },
            ) { task, projects -> taskUi(task, projects) }
                .catch { e -> emit(ItemUiState(found = false, title = e.userMessage())) }
        } else {
            // T-024: unknown-kind fallback — each probe degrades
            // independently (null / empty) so one failing source (403,
            // offline, 5xx) never collapses the whole screen.
            combine(
                taskRepository.observeTask(itemId).catch { emit(null) },
                noteRepository.observeNote(itemId).catch { emit(null) },
                agentRepository.observeRun(itemId).catch { emit(null) },
                inboxRepository.observeInbox()
                    .map { list -> list.firstOrNull { it.id == itemId } }
                    .catch { emit(null) },
                organizationRepository.observeProjects().catch { emit(emptyList()) },
            ) { task, note, run, inboxItem, projects ->
                when {
                    task != null -> taskUi(task, projects)
                    note != null -> ItemUiState(
                        found = true, kind = "Note", title = note.displayTitle,
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
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemUiState())
}

/** Agent activity helper for screens that show runs compactly. */
fun AgentRun.isWaitingForInput(): Boolean =
    state == AgentRunState.WAITING_FOR_INPUT

/**
 * T-022e: a project's context surface — workspace-scoped chats that belong to
 * the project (client-side join over existing lists) + project-filtered notes.
 * Tasks stay one tap deeper (existing filter route, nothing duplicated).
 */
class ProjectDetailViewModel(
    organizationRepository: OrganizationRepository,
    noteRepository: NoteRepository,
    chatRepository: ChatRepository,
    projectId: EntityId,
) : ViewModel() {

    data class ProjectDetailUiState(
        val loaded: Boolean = false,
        val error: String? = null,
        val project: Project? = null,
        val chats: List<ChatThread> = emptyList(),
        val notes: List<Note> = emptyList(),
    )

    val state: StateFlow<ProjectDetailUiState> = combine(
        organizationRepository.observeProject(projectId),
        noteRepository.observeNotes(projectId = projectId),
        chatRepository.observeThreads(),
    ) { project, notes, chats ->
        val ref = workspaceRefFor(projectId)
        ProjectDetailUiState(
            loaded = true,
            project = project,
            notes = notes,
            chats = if (ref == null) {
                emptyList()
            } else {
                chats.filter { it.scopeType == "workspace" && it.scopeRef == ref }
            },
        )
    }.catch { e -> emit(ProjectDetailUiState(loaded = true, error = e.userMessage())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectDetailUiState())

    companion object {
        /**
         * Workspace chats bind a repo-dir slug; project ids carry it after the
         * last `:`. Vault ids already ARE slugs (server slugify); machine repo
         * ids get the client mirror (lowercase, spaces→hyphens — server-side
         * slug overrides not replicated, documented limitation).
         */
        fun workspaceRefFor(projectId: EntityId): String? = when {
            projectId.startsWith("vault:project:") ->
                projectId.removePrefix("vault:project:")
            projectId.startsWith("machine:project:") ->
                projectId.removePrefix("machine:project:")
                    .lowercase().replace(' ', '-')
            else -> null
        }
    }
}
