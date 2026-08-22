package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.domain.CaptureType
import dev.magnor.kompakt.domain.ProtocolVerdict
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.relativeTo
import dev.magnor.kompakt.ui.viewmodels.CaptureViewModel
import dev.magnor.kompakt.ui.viewmodels.DiagnosticsViewModel

/**
 * Universal capture — propose → user confirms type → commit.
 * The server proposes structure; the user has the final word (D0xx).
 */
@Composable
fun CaptureScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: CaptureViewModel = containerViewModel {
        CaptureViewModel(it.captureRepository, it::nextRequestId)
    },
) {
    val state by viewModel.state.collectAsState()

    AppScreen(title = "Capture", onBack = onBack) {
        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::onTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { TextMMD("What is this?") },
            enabled = state.result == null,
            singleLine = false,
            maxLines = 4,
        )

        if (state.proposal == null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ButtonMMD(
                    onClick = viewModel::interpret,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.text.isNotBlank() && !state.interpreting,
                ) { TextMMD(if (state.interpreting) "Interpreting…" else "Interpret") }
            }
            SectionLabel("Voice capture arrives in Phase 11")
        } else {
            val proposal = state.proposal!!
            SectionLabel("Server proposal")
            DetailRow(label = "Type", value = proposal.proposedType.wire)
            DetailRow(label = "Title", value = proposal.title)
            proposal.dueAt?.let { DetailRow(label = "Due", value = "proposed") }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButtonMMD(
                    onClick = viewModel::changeType,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.result == null,
                ) { TextMMD("Change type → ${nextCaptureType(proposal.proposedType).wire}") }
                ButtonMMD(
                    onClick = viewModel::commit,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.result == null && !state.working,
                ) { TextMMD(if (state.working) "Saving…" else "Confirm as ${proposal.proposedType.wire}") }
            }
        }

        state.result?.let { result ->
            SectionLabel("Done")
            ListRow(title = result, trailing = "✓")
            ButtonMMD(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) { TextMMD("Back") }
        }

        state.error?.let { error ->
            SectionLabel("Error")
            ListRow(title = error, trailing = "!")
        }
    }
}

private fun nextCaptureType(current: CaptureType): CaptureType = when (current) {
    CaptureType.TASK -> CaptureType.NOTE
    CaptureType.NOTE -> CaptureType.CHAT
    CaptureType.CHAT -> CaptureType.AGENT_REQUEST
    CaptureType.AGENT_REQUEST, CaptureType.UNKNOWN -> CaptureType.TASK
}

/** Diagnostics — capability negotiation + sync state (Settings → Diagnostics). */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = containerViewModel {
        DiagnosticsViewModel(
            syncRepository = it.syncRepository,
            clientProtocol = dev.magnor.kompakt.AppInfo.APP_PROTOCOL,
            minimumServerProtocol = dev.magnor.kompakt.AppInfo.MINIMUM_SERVER_PROTOCOL,
            newRequestId = it::nextRequestId,
        )
    },
) {
    val state by viewModel.state.collectAsState()

    AppScreen(title = "Diagnostics", onBack = onBack) {
        SectionLabel("Protocol")
        state.verdict?.let { verdict ->
            val (label, value) = when (verdict) {
                is ProtocolVerdict.Ok -> "Compatible" to "client ${state.capabilities?.serverProtocol ?: "?"} · server OK"
                is ProtocolVerdict.ClientTooOld -> "Incompatible" to
                    "client too old — server requires ≥ ${verdict.minimumClientProtocol}"
                is ProtocolVerdict.ServerTooOld -> "Incompatible" to
                    "server too old (${verdict.serverProtocol})"
            }
            DetailRow(label = label, value = value)
        } ?: ListRow(title = "Not negotiated yet")

        SectionLabel("Server")
        state.serverStatus?.let { status ->
            DetailRow(label = "Healthy", value = if (status.healthy) "✓ yes" else "✕ no")
            status.version?.let { DetailRow(label = "Version", value = it) }
        }

        SectionLabel("Sync")
        DetailRow(label = "Pending changes", value = state.pendingChanges?.toString() ?: "—")
        DetailRow(label = "Last sync", value = state.lastSync?.toString() ?: "never")

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            ButtonMMD(onClick = viewModel::syncNow, modifier = Modifier.fillMaxWidth()) {
                TextMMD("Sync now")
            }
        }

        SectionLabel("Capabilities")
        state.capabilities?.let { caps ->
            caps.features.forEach { (feature, enabled) ->
                ListRow(title = feature, trailing = if (enabled) "✓" else "—")
            }
        }
    }
}

@Composable
fun SettingsScreen(onOpenDiagnostics: () -> Unit, onBack: () -> Unit) {
    AppScreen(title = "Settings", onBack = onBack) {
        SectionLabel("Device")
        ListRow(title = "Enrollment", subtitle = "Not enrolled — Phase 4")
        ListRow(title = "Server", subtitle = "dev-server:8650 (placeholder — Phase 4)")
        ListRow(title = "Protocol", subtitle = "Client v1 · minimum server v1")

        SectionLabel("Sync")
        ListRow(title = "Mode", subtitle = "Manual — Phase 9/10 add push + periodic")

        SectionLabel("About")
        ListRow(title = "Diagnostics", onClick = onOpenDiagnostics)
        ListRow(title = "Version", subtitle = "0.2.0-dev (Phase 2 — domain model)")
        ListRow(title = "Data", subtitle = "In-memory fakes — server lands in Phase 3+")
    }
}
