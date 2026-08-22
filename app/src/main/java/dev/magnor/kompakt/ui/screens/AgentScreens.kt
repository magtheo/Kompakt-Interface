package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.cards.CardMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.data.MockData

/**
 * Agents list — a process manager view, not a chat history.
 * Status markers are plain glyphs (E-Ink friendly): ● running, ○ idle,
 * ✓ finished, ! needs input.
 */
@Composable
fun AgentsListScreen(
    onOpenAgent: (String) -> Unit,
    onOpenInbox: () -> Unit,
) {
    AppScreen(title = "Agents") {
        SectionLabel("Workers")
        MockData.agents.forEachIndexed { index, (name, marker, status, activity) ->
            ListRow(
                title = name,
                subtitle = activity,
                trailing = "$marker $status",
                onClick = { onOpenAgent("agent-${index + 1}") },
            )
        }
        SectionLabel("Attention")
        ListRow(title = "1 agent waiting for input", trailing = "!", onClick = onOpenInbox)
    }
}

/** Agent detail — persistent worker with objective and live state. */
@Composable
fun AgentDetailScreen(
    onOpenRun: (String) -> Unit,
    onBack: () -> Unit,
) {
    AppScreen(title = "Kompakt audit", onBack = onBack) {
        MockData.agentDetail.forEach { (label, value) ->
            DetailRow(label = label, value = value)
        }

        SectionLabel("Runs")
        MockData.agentRuns.forEach { (runId, label) ->
            ListRow(title = label, onClick = { onOpenRun(runId) })
        }

        SectionLabel("Actions")
        ActionButtons()
    }
}

@Composable
private fun ActionButtons() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ButtonMMD(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            TextMMD("Message agent")
        }
        OutlinedButtonMMD(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            TextMMD("Stop")
        }
        OutlinedButtonMMD(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            TextMMD("Open result")
        }
    }
}

/** Agent run detail — one execution with an inspectable result. */
@Composable
fun AgentRunDetailScreen(onBack: () -> Unit) {
    AppScreen(title = "Run", onBack = onBack) {
        DetailRow(label = "Run", value = "Spec drift check")
        DetailRow(label = "Status", value = "Needs input")
        DetailRow(label = "Started", value = "22:41")

        SectionLabel("Result")
        CardMMD(Modifier.fillMaxWidth()) {
            TextMMD(
                text = "3 findings: protocol wording differs between " +
                    "protocol-and-sync.md and capabilities sketch; tombstone " +
                    "semantics need one canonical description; revision guard " +
                    "assumes monotonic per-object counters.",
                modifier = Modifier.padding(12.dp),
            )
        }

        SectionLabel("Explicit transitions (D003)")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButtonMMD(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                TextMMD("Discuss in chat")
            }
            OutlinedButtonMMD(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                TextMMD("Create task")
            }
            OutlinedButtonMMD(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                TextMMD("Save note")
            }
            OutlinedButtonMMD(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                TextMMD("Archive")
            }
        }
    }
}
