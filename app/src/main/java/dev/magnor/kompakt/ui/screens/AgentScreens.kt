package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.cards.CardMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.domain.AgentBackendInfo
import dev.magnor.kompakt.domain.AgentEvent
import dev.magnor.kompakt.domain.AgentRole
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunKind
import dev.magnor.kompakt.domain.AgentRunState
import dev.magnor.kompakt.ui.MarkdownText
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.timeOfDay
import dev.magnor.kompakt.ui.viewmodels.AgentDetailViewModel
import dev.magnor.kompakt.ui.viewmodels.AgentRunDetailViewModel
import dev.magnor.kompakt.ui.viewmodels.AgentsListViewModel

/** Static E-Ink status glyphs — no icons, no color dependence. */
private fun runGlyph(state: AgentRunState): String = when (state) {
    AgentRunState.QUEUED -> "○"
    AgentRunState.RUNNING -> "●"
    AgentRunState.WAITING_FOR_INPUT -> "!"
    AgentRunState.SUCCEEDED -> "✓"
    AgentRunState.FAILED -> "✕"
    AgentRunState.CANCELLED -> "—"
    AgentRunState.IDLE -> "○"
    AgentRunState.UNKNOWN -> "?"
}

private fun capSummary(info: AgentBackendInfo): String = listOfNotNull(
    "sandboxed".takeIf { info.sandboxed },
    "resumable".takeIf { info.resumable },
    "live-steer".takeIf { info.liveSteering },
    "commands".takeIf { info.commands },
    "events".takeIf { info.eventStream },
    "projects".takeIf { info.projectRegistration },
).joinToString(" · ").ifEmpty { "no capabilities advertised" }

private fun yn(flag: Boolean): String = if (flag) "yes" else "no"

/**
 * Agents list — a process manager over swappable backends, not a chat
 * history (D025). Backends and roles come from the live surface; run ids
 * are backend-native (`run_…`/`ses_…`).
 */
@Composable
fun AgentsListScreen(
    onOpenAgent: (backend: String, name: String) -> Unit,
    onOpenRun: (runId: String) -> Unit,
    onOpenInbox: () -> Unit,
    viewModel: AgentsListViewModel = containerViewModel { AgentsListViewModel(it.agentRepository, it.now()) },
) {
    val surface by viewModel.surface.collectAsState()
    val runs by viewModel.runs.collectAsState()

    AppScreen(title = "Agents") {
        SectionLabel("Backends")
        if (surface.backends.isEmpty()) {
            ListRow(title = "No backends configured")
        } else {
            surface.backends.forEach { (name, info) ->
                ListRow(
                    title = name + if (name == surface.defaultBackend) " (default)" else "",
                    subtitle = capSummary(info),
                )
            }
        }

        SectionLabel("Workers")
        if (surface.agents.isEmpty()) {
            ListRow(title = "No agents configured")
        } else {
            surface.agents.forEach { role ->
                ListRow(
                    title = role.name,
                    subtitle = role.description,
                    trailing = "@${role.backend}",
                    onClick = { onOpenAgent(role.backend, role.name) },
                )
            }
        }

        SectionLabel("Attention")
        val waiting = runs.count { it.state == AgentRunState.WAITING_FOR_INPUT }
        if (waiting > 0) {
            ListRow(
                title = "$waiting ${if (waiting == 1) "run" else "runs"} waiting for input",
                trailing = "!",
                onClick = onOpenInbox,
            )
        } else {
            ListRow(title = "No runs need input")
        }

        SectionLabel("Live runs")
        val live = runs.filter { !it.state.isTerminal }
        if (live.isEmpty()) {
            ListRow(title = "Nothing running")
        } else {
            live.forEach { run ->
                ListRow(
                    title = run.displayTitle,
                    subtitle = "${run.backend}/${run.agent} · ${run.kind.wire}",
                    trailing = runGlyph(run.state),
                    onClick = { onOpenRun(run.id) },
                )
            }
        }

        SectionLabel("Recent")
        runs.filter { it.state.isTerminal }.take(5).forEach { run ->
            ListRow(
                title = run.displayTitle,
                subtitle = run.resultSummary ?: run.prompt,
                trailing = runGlyph(run.state),
                onClick = { onOpenRun(run.id) },
            )
        }
        if (runs.all { !it.state.isTerminal } && runs.isEmpty()) {
            ListRow(title = "No finished runs yet")
        }
    }
}

/** Role detail — capabilities first, dispatch second, runs third. */
@Composable
fun AgentDetailScreen(
    backend: String,
    agentName: String,
    onOpenRun: (runId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: AgentDetailViewModel = containerViewModel(key = "agent-$backend-$agentName") {
        AgentDetailViewModel(it.agentRepository, backend, agentName, it::nextRequestId)
    },
) {
    val role by viewModel.role.collectAsState()
    val info by viewModel.backendInfo.collectAsState()
    val runs by viewModel.runs.collectAsState()
    val feedback by viewModel.feedback.collectAsState()
    val dispatched by viewModel.lastDispatched.collectAsState()

    var prompt by remember { mutableStateOf("") }
    var projectRef by remember { mutableStateOf("") }
    val needsProject = info?.projectRegistration == true

    AppScreen(title = agentName.ifBlank { "Agent" }, onBack = onBack) {
        role?.let { r: AgentRole ->
            DetailRow(label = "Role", value = r.name)
            DetailRow(label = "Backend", value = r.backend)
            DetailRow(label = "Steering", value = r.steering)
            r.description.takeIf { it.isNotBlank() }?.let {
                DetailRow(label = "Description", value = it)
            }
        } ?: ListRow(title = "Role not found on this backend")

        info?.let { caps ->
            SectionLabel("Backend capabilities")
            DetailRow(label = "Sandboxed", value = yn(caps.sandboxed))
            DetailRow(label = "Resumable", value = yn(caps.resumable))
            DetailRow(label = "Live steering", value = yn(caps.liveSteering))
            DetailRow(label = "Commands", value = yn(caps.commands))
            DetailRow(label = "Event stream", value = yn(caps.eventStream))
            DetailRow(label = "Project registration", value = yn(caps.projectRegistration))
        }

        SectionLabel("New run")
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { TextMMD("Objective") },
            singleLine = false,
            maxLines = 4,
        )
        if (needsProject) {
            OutlinedTextField(
                value = projectRef,
                onValueChange = { projectRef = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                label = { TextMMD("Project ref (required)") },
                singleLine = true,
            )
        }
        ButtonMMD(
            onClick = {
                viewModel.dispatch(prompt, projectRef.takeIf { needsProject })
                prompt = ""
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            enabled = prompt.isNotBlank() && (!needsProject || projectRef.isNotBlank()),
        ) {
            TextMMD(if (info?.resumable == true) "Start session" else "Dispatch run")
        }
        feedback?.let { TextMMD(it, modifier = Modifier.padding(top = 8.dp)) }

        // T-012: the dispatched run is a live path to its detail, not a text note.
        dispatched?.let { run ->
            ListRow(
                title = "Dispatched: ${run.displayTitle}",
                subtitle = "Tap to open",
                trailing = runGlyph(run.state),
                onClick = { onOpenRun(run.id) },
            )
        }

        SectionLabel("Runs")
        if (runs.isEmpty()) {
            ListRow(title = "No runs yet")
        } else {
            runs.forEach { run ->
                ListRow(
                    title = run.displayTitle,
                    subtitle = run.resultSummary,
                    trailing = runGlyph(run.state),
                    onClick = { onOpenRun(run.id) },
                )
            }
        }
    }
}

/**
 * Run detail (T-013): transcript-first on the shared ChatScaffold. The
 * dialogue owns the screen — assistant/user events render as full
 * conversation rows (monochrome sender coding like chat), process events
 * stay as dim one-liners. Metadata collapses into one summary card with an
 * expandable Details section; commands live behind a "Run command" menu
 * section; transitions stay explicit (D003).
 */
@Composable
fun AgentRunDetailScreen(
    runId: String,
    onBack: () -> Unit,
    viewModel: AgentRunDetailViewModel = containerViewModel(key = "run-$runId") {
        AgentRunDetailViewModel(
            agentRepository = it.agentRepository,
            taskRepository = it.taskRepository,
            noteRepository = it.noteRepository,
            chatRepository = it.chatRepository,
            runId = runId,
            newRequestId = it::nextRequestId,
        )
    },
) {
    val run by viewModel.run.collectAsState()
    val info by viewModel.backendInfo.collectAsState()
    val commands by viewModel.commands.collectAsState()
    val result by viewModel.result.collectAsState()
    val events by viewModel.events.collectAsState()
    val feedback by viewModel.feedback.collectAsState()

    var message by remember { mutableStateOf("") }
    var detailsOpen by remember { mutableStateOf(false) }
    var commandsOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) runCatching { listState.scrollToItem(events.size) }
    }

    val resumable = run?.kind == AgentRunKind.SESSION && info?.resumable == true

    ChatScaffold(
        title = run?.displayTitle ?: "Run",
        onBack = onBack,
        listState = listState,
        transcript = {
            run?.let { r: AgentRun ->
                // Status summary — one line instead of a metadata dashboard.
                item(key = "status") {
                    CardMMD(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            TextMMD(
                                "${runGlyph(r.state)} ${r.state.wire} · ${r.backend}/${r.agent.ifBlank { "—" }}",
                                fontWeight = FontWeight.SemiBold,
                            )
                            val parts = buildList {
                                r.createdAt?.let { add("started ${it.timeOfDay()}") }
                                if (r.tokensIn != null || r.tokensOut != null) {
                                    add("↑${r.tokensIn ?: 0} ↓${r.tokensOut ?: 0}")
                                }
                                r.projectRef?.let { add("· $it") }
                            }
                            if (parts.isNotEmpty()) {
                                TextMMD(parts.joinToString(" · "), fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (detailsOpen) {
                    item(key = "details") {
                        CardMMD(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                DetailRow(label = "Kind", value = r.kind.wire)
                                r.prompt?.let { DetailRow(label = "Objective", value = it) }
                                r.updatedAt?.let { DetailRow(label = "Updated", value = it.timeOfDay()) }
                                if (r.tokensIn != null || r.tokensOut != null) {
                                    DetailRow(label = "Tokens", value = "↑${r.tokensIn ?: 0} ↓${r.tokensOut ?: 0}")
                                }
                                info?.let { caps ->
                                    DetailRow(label = "Capabilities", value = capSummary(caps))
                                }
                            }
                        }
                    }
                }
                // Details toggle is the card's own affordance — tap the card.
                item(key = "status-tap") {
                    ButtonMMD(
                        onClick = { detailsOpen = !detailsOpen },
                        modifier = Modifier.fillMaxWidth(),
                    ) { TextMMD(if (detailsOpen) "Hide details" else "Show details") }
                }

                result?.let { res ->
                    item(key = "result") {
                        CardMMD(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                TextMMD("Result", fontWeight = FontWeight.Bold)
                                res.summary?.let { TextMMD(it, modifier = Modifier.padding(top = 4.dp)) }
                                res.branch?.let { TextMMD("Branch: $it", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                                if (res.commitRefs.isNotEmpty()) {
                                    TextMMD("Commits: ${res.commitRefs.joinToString(", ")}", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                                res.salvageRef?.let { TextMMD("Salvage: $it", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                            }
                        }
                    }
                }

                // Transcript: dialogue events in full, process events dim.
                items(events.size) { index ->
                    val event = events[index]
                    val isDialogue = event.kind.contains("message")
                    if (isDialogue) {
                        ChatEventRow(event)
                    } else {
                        TextMMD(
                            "#${event.seq} ${event.kind}${event.text?.let { " · ${it.take(60)}" } ?: ""}",
                            fontSize = 12.sp,
                        )
                    }
                }

                if (events.isEmpty()) {
                    item(key = "no-events") {
                        ListRow(title = "No events yet", subtitle = "Activity will appear here")
                    }
                }

                // Command menu — collapsed by default, one tap to open.
                if (commands.isNotEmpty() && resumable && r.state != AgentRunState.SUCCEEDED && r.state != AgentRunState.FAILED && r.state != AgentRunState.CANCELLED) {
                    item(key = "commands") {
                        Column {
                            ButtonMMD(
                                onClick = { commandsOpen = !commandsOpen },
                                modifier = Modifier.fillMaxWidth(),
                            ) { TextMMD(if (commandsOpen) "Close commands ▴" else "Run command ▾") }
                            if (commandsOpen) {
                                commands.forEach { command ->
                                    ListRow(
                                        title = "/${command.name}",
                                        subtitle = command.description,
                                        onClick = {
                                            commandsOpen = false
                                            viewModel.runCommand(command)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Explicit transitions — compact row (D003).
                item(key = "transitions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButtonMMD(
                            onClick = viewModel::discussInChat,
                            modifier = Modifier.weight(1f),
                        ) { TextMMD("Discuss") }
                        OutlinedButtonMMD(
                            onClick = viewModel::createTask,
                            modifier = Modifier.weight(1f),
                        ) { TextMMD("Task") }
                        OutlinedButtonMMD(
                            onClick = viewModel::saveNote,
                            modifier = Modifier.weight(1f),
                        ) { TextMMD("Note") }
                    }
                }

                if (!r.state.isTerminal) {
                    item(key = "cancel") {
                        OutlinedButtonMMD(
                            onClick = viewModel::cancel,
                            modifier = Modifier.fillMaxWidth(),
                        ) { TextMMD("Cancel run") }
                    }
                }

                feedback?.let {
                    item(key = "feedback") { ListRow(title = it, trailing = "·") }
                }
            } ?: item(key = "missing") { ListRow(title = "Run not found") }
        },
        composer = {
            if (run != null && resumable && run?.state?.isTerminal == false) {
                Column {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { TextMMD("Message the session") },
                        singleLine = false,
                        maxLines = 3,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ButtonMMD(
                            onClick = {
                                viewModel.send(message)
                                message = ""
                            },
                            modifier = Modifier.weight(1f),
                            enabled = message.isNotBlank(),
                        ) { TextMMD("Send") }
                        if (info?.liveSteering == true) {
                            OutlinedButtonMMD(
                                onClick = { viewModel.steer(message) },
                                modifier = Modifier.weight(1f),
                                enabled = message.isNotBlank(),
                            ) { TextMMD("Steer") }
                        }
                    }
                }
            }
        },
    )
}

/** Dialogue-style event row — same monochrome sender coding as chat (T-013). */
@Composable
private fun ChatEventRow(event: AgentEvent) {
    val fromUser = event.kind.startsWith("user")
    if (fromUser) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            CardMMD(modifier = Modifier.fillMaxWidth(0.85f)) {
                Column(Modifier.padding(12.dp)) {
                    MarkdownText(
                        raw = event.text ?: "—",
                        baseFontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    } else {
        CardMMD(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                MarkdownText(raw = event.text ?: "—")
            }
        }
    }
}
