package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.data.MockData

/**
 * Inbox — aggregated attention items. A view, not a source of truth:
 * each item opens the original object elsewhere.
 */
@Composable
fun InboxScreen(onOpenItem: (String) -> Unit, onBack: () -> Unit) {
    AppScreen(title = "Inbox", onBack = onBack) {
        MockData.inboxItems.forEach { (title, meta) ->
            ListRow(title = title, subtitle = meta, trailing = "●", onClick = { onOpenItem("inbox") })
        }
    }
}

/** Generic item detail placeholder — real detail screens land with their phases. */
@Composable
fun ItemDetailScreen(onBack: () -> Unit) {
    AppScreen(title = "Item", onBack = onBack) {
        DetailRow(label = "Type", value = "Placeholder")
        DetailRow(label = "Title", value = "—")
        DetailRow(label = "Source", value = "Server (Phase 3+)")
        DetailRow(label = "Revision", value = "—")
        SectionLabel("Detail screen arrives with its feature phase")
    }
}

/** Universal capture — user confirms the object type; the app never guesses. */
@Composable
fun CaptureScreen(onDone: () -> Unit, onBack: () -> Unit) {
    AppScreen(title = "Capture", onBack = onBack) {
        TextMMD("What is this?")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButtonMMD(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                TextMMD("Chat")
            }
            OutlinedButtonMMD(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                TextMMD("Task")
            }
            OutlinedButtonMMD(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                TextMMD("Note")
            }
            OutlinedButtonMMD(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                TextMMD("Agent request")
            }
        }
        SectionLabel("Voice capture arrives in Phase 11")
    }
}

@Composable
fun SettingsScreen(onOpenDiagnostics: () -> Unit, onBack: () -> Unit) {
    AppScreen(title = "Settings", onBack = onBack) {
        SectionLabel("Device")
        ListRow(title = "Enrollment", subtitle = "Not enrolled — Phase 4")
        ListRow(title = "Server", subtitle = "dev-server:8650 (placeholder)")
        ListRow(title = "Protocol", subtitle = "Client v1 · minimum server v1")

        SectionLabel("Sync")
        ListRow(title = "Mode", subtitle = "Manual — Phase 9/10 add push + periodic")

        SectionLabel("About")
        ListRow(title = "Diagnostics", onClick = onOpenDiagnostics)
        ListRow(title = "Version", subtitle = "0.1.0-dev (Phase 1)")
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    AppScreen(title = "Diagnostics", onBack = onBack) {
        DetailRow(label = "Reachability", value = "Unknown (Phase 4)")
        DetailRow(label = "Capabilities endpoint", value = "—")
        DetailRow(label = "Last successful sync", value = "—")
        DetailRow(label = "Pending captures", value = "0")
        DetailRow(label = "App protocol", value = "1")
    }
}
