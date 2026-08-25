package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.ui.MarkdownText
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.timeOfDay
import dev.magnor.kompakt.ui.viewmodels.NoteEditorViewModel
import dev.magnor.kompakt.voice.MicButton
import dev.magnor.kompakt.voice.insertAtSelection
import dev.magnor.kompakt.voice.rememberVoiceInput

/**
 * T-022a: note detail + editor (D028 v2 — file-authoritative). Read view
 * renders markdown + meta; Edit switches to the full-text editor with the
 * checksum optimistic lock. Voice inserts at the cursor
 * ([insertAtSelection]); a 409 never discards the user's text — Reload is
 * the only explicit path to the server version.
 */
@Composable
fun NoteEditorScreen(
    noteId: EntityId,
    onBack: () -> Unit,
    viewModel: NoteEditorViewModel = containerViewModel(key = "note-$noteId") {
        NoteEditorViewModel(
            noteRepository = it.noteRepository,
            taskRepository = it.taskRepository,
            organizationRepository = it.organizationRepository,
            noteId = noteId,
            newRequestId = it::nextRequestId,
            now = it.now,
        )
    },
) {
    val state by viewModel.state.collectAsState()
    var editing by remember(noteId) { mutableStateOf(false) }

    // Selection is UI state — the editor value lives here, synced from the
    // VM whenever the text is clean (initial load, Reload, post-save).
    var editor by remember(noteId) { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(state.text, state.dirty) {
        if (!state.dirty) editor = TextFieldValue(state.text, TextRange(state.text.length))
    }

    val voice = rememberVoiceInput { transcript ->
        editor = insertAtSelection(editor, transcript)
        viewModel.onTextChange(editor.text)
    }

    // Successful save returns to the read view (plan: snackbar-equivalent
    // is the persistent "Saved HH:MM" meta line — e-ink friendly).
    LaunchedEffect(state.savedAt) {
        if (state.savedAt != null) editing = false
    }

    AppScreen(
        title = state.title,
        onBack = onBack,
        actions = {
            when {
                editing -> TextButton(
                    onClick = viewModel::save,
                    enabled = state.canSave,
                ) {
                    TextMMD(if (state.saving) "Saving…" else "Save")
                }
                !state.loading && !state.notFound -> TextButton(onClick = { editing = true }) {
                    TextMMD("Edit")
                }
            }
        },
    ) {
        when {
            state.notFound -> ListRow(
                title = "Note not found",
                subtitle = "It may have been moved or filed — the list has the current location.",
            )
            state.loading -> ListRow(title = "Loading…")
            editing -> {
                if (state.conflict) {
                    ListRow(
                        title = "Changed on the server",
                        subtitle = "Save again to overwrite, or reload the server version (your edits are replaced).",
                        trailing = "↻",
                        onClick = viewModel::reload,
                    )
                }
                state.error?.let { ListRow(title = it, onClick = viewModel::reload) }
                OutlinedTextField(
                    value = editor,
                    onValueChange = {
                        editor = it
                        viewModel.onTextChange(it.text)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.saving,
                    minLines = 12,
                    trailingIcon = { MicButton(voice) },
                )
                state.savedAt?.let {
                    TextMMD(
                        "Saved ${it.timeOfDay()} — tap back when done",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            else -> {
                // Read view: triage cards for scratchpad/inbox docs (T-022b),
                // rendered markdown for everything else.
                if (state.triageable && state.sections.isNotEmpty()) {
                    NoteTriageList(
                        state = state,
                        viewModel = viewModel,
                    )
                } else {
                    MarkdownText(raw = state.text)
                }
                val meta = buildList {
                    add("Updated ${state.relativeUpdated}")
                    state.category?.let { add(it) }
                    state.source?.let { add(it) }
                    state.savedAt?.let { add("Saved ${it.timeOfDay()}") }
                }.joinToString(" · ")
                TextMMD(text = meta, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                state.error?.let { ListRow(title = it, onClick = viewModel::reload) }
            }
        }
    }
}

/**
 * T-022b: per-section triage cards (D028 v2 stage 3). One expanded section
 * at a time; the actions are explicit — nothing auto-files. Sub-pickers
 * (New note in… / Append to…) expand inline below the action row.
 */
@Composable
private fun NoteTriageList(
    state: NoteEditorViewModel.EditorUiState,
    viewModel: NoteEditorViewModel,
) {
    val saveTargets by viewModel.saveTargets.collectAsState()
    val appendTargets by viewModel.appendTargets.collectAsState()
    var openSection by remember { mutableStateOf<Int?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }

    // Anything before the first `## ` heading (e.g. "# Scratchpad") stays visible.
    val preamble = state.text.lines()
        .take(state.sections.first().lineIndex)
        .joinToString("\n").trim()

    Column {
        if (preamble.isNotEmpty()) {
            MarkdownText(raw = preamble, modifier = Modifier.padding(bottom = 8.dp))
        }
        state.triageNotice?.let { notice ->
            ListRow(
                title = notice,
                trailing = "✕",
                onClick = viewModel::dismissTriageNotice,
            )
        }
        state.sections.forEach { section ->
            val open = openSection == section.lineIndex
            ListRow(
                title = section.title,
                subtitle = section.body.takeIf { it.isNotBlank() },
                trailing = if (open) "▾" else "▸",
            ) {
                openSection = if (open) null else section.lineIndex
            }
            if (open) {
                when (picker) {
                    "new" -> {
                        ListRow(
                            title = "00 - Inbox",
                            subtitle = "New note in the inbox",
                            trailing = if (state.triageBusy) "…" else null,
                        ) {
                            viewModel.newNoteIn(section, null)
                            picker = null
                        }
                        saveTargets.forEach { project ->
                            ListRow(
                                title = project.name,
                                subtitle = "New note in this project",
                                trailing = if (state.triageBusy) "…" else null,
                            ) {
                                viewModel.newNoteIn(section, project)
                                picker = null
                            }
                        }
                    }
                    "append" -> {
                        if (appendTargets.isEmpty()) {
                            ListRow(title = "No other notes yet")
                        }
                        appendTargets.forEach { target ->
                            ListRow(
                                title = target.displayTitle,
                                subtitle = target.category,
                                trailing = if (state.triageBusy) "…" else null,
                            ) {
                                viewModel.appendTo(section, target)
                                picker = null
                            }
                        }
                    }
                    else -> {}
                }
                if (picker == null) {
                    Column {
                        ListRow(title = "New note in…", subtitle = "File as its own note") {
                            picker = "new"
                        }
                        ListRow(
                            title = "Append to…",
                            subtitle = "Add to an existing note",
                        ) {
                            picker = "append"
                        }
                        ListRow(
                            title = "Create task",
                            subtitle = "Follow-up from this section",
                        ) {
                            viewModel.createTaskFrom(section)
                        }
                        ListRow(
                            title = "Keep",
                            subtitle = "Leave it here",
                        ) {
                            openSection = null
                        }
                        ListRow(
                            title = "Discard",
                            subtitle = "Remove from this doc (git history keeps it)",
                        ) {
                            viewModel.discard(section)
                            openSection = null
                        }
                    }
                }
            }
        }
    }
}
