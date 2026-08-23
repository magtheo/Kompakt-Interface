package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.cards.CardMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.domain.AgentBackendInfo
import dev.magnor.kompakt.domain.AgentRole
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunKind
import dev.magnor.kompakt.domain.AgentRunState
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

/** Run detail — process-manager controls + evidence + explicit transitions. */
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

    AppScreen(title = run?.displayTitle ?: "Run", onBack = onBack) {
        run?.let { r: AgentRun ->
            DetailRow(label = "Status", value = "${runGlyph(r.state)} ${r.state.wire}")
            DetailRow(label = "Kind", value = r.kind.wire)
            DetailRow(label = "Backend", value = "${r.backend}/${r.agent.ifBlank { "—" }}")
            r.projectRef?.let { DetailRow(label = "Project", value = it) }
            r.prompt?.let { DetailRow(label = "Objective", value = it) }
            r.createdAt?.let { DetailRow(label = "Started", value = it.timeOfDay()) }
            r.updatedAt?.let { DetailRow(label = "Updated", value = it.timeOfDay()) }
            if (r.tokensIn != null || r.tokensOut != null) {
                DetailRow(label = "Tokens", value = "↑${r.tokensIn ?: 0} ↓${r.tokensOut ?: 0}")
            }

            if (!r.state.isTerminal) {
                SectionLabel("Controls")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (r.kind == AgentRunKind.SESSION && info?.resumable == true) {
                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { TextMMD("Message the session") },
                            singleLine = false,
                            maxLines = 3,
                        )
                        ButtonMMD(
                            onClick = {
                                viewModel.send(message)
                                message = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = message.isNotBlank(),
                        ) { TextMMD("Send") }
                    }
                    if (info?.liveSteering == true) {
                        OutlinedButtonMMD(
                            onClick = { viewModel.steer(message) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = message.isNotBlank(),
                        ) { TextMMD("Steer (mid-run)") }
                    } else {
                        ListRow(title = "Steering", subtitle = "not supported by this backend")
                    }
                    if (commands.isNotEmpty() && r.kind == AgentRunKind.SESSION) {
                        commands.forEach { command ->
                            OutlinedButtonMMD(
                                onClick = { viewModel.runCommand(command) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { TextMMD("/${command.name} — ${command.description}") }
                        }
                    }
                    OutlinedButtonMMD(
                        onClick = viewModel::cancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) { TextMMD("Cancel run") }
                }
            }

            result?.let { res ->
                SectionLabel("Result")
                CardMMD(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        res.summary?.let { TextMMD(it) }
                        res.branch?.let { TextMMD("Branch: $it", modifier = Modifier.padding(top = 4.dp)) }
                        if (res.commitRefs.isNotEmpty()) {
                            TextMMD("Commits: ${res.commitRefs.joinToString(", ")}", modifier = Modifier.padding(top = 4.dp))
                        }
                        res.salvageRef?.let { TextMMD("Salvage: $it", modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
            }

            if (events.isNotEmpty()) {
                SectionLabel("Events")
                events.takeLast(8).forEach { event ->
                    ListRow(
                        title = "#${event.seq} ${event.kind}",
                        subtitle = event.text?.take(80),
                    )
                }
            }

            SectionLabel("Explicit transitions (D003)")
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButtonMMD(
                    onClick = viewModel::discussInChat,
                    modifier = Modifier.fillMaxWidth(),
                ) { TextMMD("Discuss in chat") }
                OutlinedButtonMMD(
                    onClick = viewModel::createTask,
                    modifier = Modifier.fillMaxWidth(),
                ) { TextMMD("Create task") }
                OutlinedButtonMMD(
                    onClick = viewModel::saveNote,
                    modifier = Modifier.fillMaxWidth(),
                ) { TextMMD("Save note") }
            }
            feedback?.let { TextMMD(it, modifier = Modifier.padding(top = 8.dp)) }
        } ?: ListRow(title = "Run not found")
    }
}
