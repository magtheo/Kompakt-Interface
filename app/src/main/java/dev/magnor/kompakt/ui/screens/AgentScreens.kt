package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.cards.CardMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.domain.AgentRunStatus
import dev.magnor.kompakt.domain.AgentStatus
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.relativeTo
import dev.magnor.kompakt.ui.timeOfDay
import dev.magnor.kompakt.ui.viewmodels.AgentDetailViewModel
import dev.magnor.kompakt.ui.viewmodels.AgentRunDetailViewModel
import dev.magnor.kompakt.ui.viewmodels.AgentsListViewModel

/** Static E-Ink status glyphs — no icons, no color dependence. */
private fun agentGlyph(status: AgentStatus): String = when (status) {
    AgentStatus.RUNNING -> "●"
    AgentStatus.IDLE -> "○"
    AgentStatus.FINISHED -> "✓"
    AgentStatus.WAITING_FOR_INPUT -> "!"
    AgentStatus.UNKNOWN -> "?"
}

private fun runGlyph(status: AgentRunStatus): String = when (status) {
    AgentRunStatus.QUEUED -> "○"
    AgentRunStatus.RUNNING -> "●"
    AgentRunStatus.WAITING_FOR_INPUT -> "!"
    AgentRunStatus.SUCCEEDED -> "✓"
    AgentRunStatus.FAILED -> "✕"
    AgentRunStatus.STOPPED -> "—"
    AgentRunStatus.UNKNOWN -> "?"
}

/**
 * Agents list — a process manager view, not a chat history.
 */
@Composable
fun AgentsListScreen(
    onOpenAgent: (EntityId) -> Unit,
    onOpenInbox: () -> Unit,
    viewModel: AgentsListViewModel = containerViewModel { AgentsListViewModel(it.agentRepository, it.now()) },
) {
    val agents by viewModel.agents.collectAsState()
    val runs by viewModel.runs.collectAsState()

    AppScreen(title = "Agents") {
        SectionLabel("Workers")
        if (agents.isEmpty()) {
            ListRow(title = "No agents configured")
        } else {
            agents.forEach { agent ->
                ListRow(
                    title = agent.name,
                    subtitle = agent.description,
                    trailing = "${agentGlyph(agent.status)} ${agent.lastActivity?.relativeTo(viewModel.now) ?: ""}".trim(),
                    onClick = { onOpenAgent(agent.id) },
                )
            }
        }

        SectionLabel("Attention")
        val waiting = runs.count { it.status == AgentRunStatus.WAITING_FOR_INPUT }
        if (waiting > 0) {
            ListRow(
                title = "$waiting agent ${if (waiting == 1) "run" else "runs"} waiting for input",
                trailing = "!",
                onClick = onOpenInbox,
            )
        } else {
            ListRow(title = "No agents need input")
        }
    }
}

/** Agent detail — persistent worker with objective and live state. */
@Composable
fun AgentDetailScreen(
    agentId: EntityId,
    onOpenRun: (EntityId) -> Unit,
    onBack: () -> Unit,
    viewModel: AgentDetailViewModel = containerViewModel(key = "agent-$agentId") {
        AgentDetailViewModel(it.agentRepository, agentId)
    },
) {
    val agent by viewModel.agent.collectAsState()
    val runs by viewModel.runs.collectAsState()

    AppScreen(title = agent?.name ?: "Agent", onBack = onBack) {
        agent?.let { a ->
            DetailRow(label = "Status", value = "${agentGlyph(a.status)} ${a.status.wire}")
            DetailRow(label = "Description", value = a.description)
            DetailRow(label = "Capabilities", value = a.capabilities.joinToString(", ").ifEmpty { "—" })
        } ?: ListRow(title = "Agent not found")

        SectionLabel("Runs")
        runs.filter { !it.archived }.forEach { run ->
            ListRow(
                title = run.title,
                subtitle = run.resultSummary,
                trailing = runGlyph(run.status),
                onClick = { onOpenRun(run.id) },
            )
        }
        if (runs.isEmpty()) {
            ListRow(title = "No runs yet")
        }

        SectionLabel("Actions")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ButtonMMD(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                TextMMD("Message agent — Phase 8")
            }
            OutlinedButtonMMD(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                TextMMD("Request run — Phase 8")
            }
        }
    }
}

/** Agent run detail — one execution with an inspectable result. */
@Composable
fun AgentRunDetailScreen(
    agentId: EntityId,
    runId: EntityId,
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
    val feedback by viewModel.feedback.collectAsState()

    AppScreen(title = run?.title ?: "Run", onBack = onBack) {
        run?.let { r ->
            DetailRow(label = "Status", value = "${runGlyph(r.status)} ${r.status.wire}")
            DetailRow(label = "Objective", value = r.objective)
            DetailRow(label = "Started", value = r.startedAt.timeOfDay())
            r.resultSummary?.let { summary ->
                SectionLabel("Result")
                CardMMD(Modifier.fillMaxWidth()) {
                    TextMMD(
                        text = summary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            SectionLabel("Explicit transitions (D003)")
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
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
                OutlinedButtonMMD(
                    onClick = viewModel::archive,
                    modifier = Modifier.fillMaxWidth(),
                ) { TextMMD("Archive") }
            }
            feedback?.let { TextMMD(it, modifier = Modifier.padding(top = 8.dp)) }
        } ?: ListRow(title = "Run not found")
    }
}
