package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.magnor.kompakt.ui.LocalAppContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.AppInfo
import dev.magnor.kompakt.data.EnrollmentManager
import dev.magnor.kompakt.data.ThemePolarity
import dev.magnor.kompakt.domain.CaptureType
import dev.magnor.kompakt.domain.ProtocolVerdict
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.relativeTo
import dev.magnor.kompakt.ui.viewmodels.CaptureViewModel
import dev.magnor.kompakt.ui.viewmodels.DiagnosticsViewModel
import dev.magnor.kompakt.ui.viewmodels.EnrollmentViewModel
import dev.magnor.kompakt.ui.viewmodels.ThemeViewModel
import dev.magnor.kompakt.voice.MicButton
import dev.magnor.kompakt.voice.VoiceStatusText
import dev.magnor.kompakt.voice.appendTranscript
import dev.magnor.kompakt.voice.rememberVoiceInput

/**
 * Universal capture — propose → user confirms type → commit.
 * The server proposes structure; the user has the final word (D0xx).
 */
@Composable
fun CaptureScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: CaptureViewModel = containerViewModel {
        CaptureViewModel(it.captureRepository, it.calendarRepository, it::nextRequestId)
    },
) {
    val state by viewModel.state.collectAsState()

    // T-021: tap mic → dictate → transcript lands here as editable text.
    val voice = rememberVoiceInput { transcript ->
        viewModel.onTextChange(appendTranscript(state.text, transcript))
    }

    AppScreen(title = "Capture", onBack = onBack) {
        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::onTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { TextMMD("What is this?") },
            enabled = state.result == null,
            singleLine = false,
            maxLines = 4,
            trailingIcon = { MicButton(voice) },
        )
        VoiceStatusText(voice)

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
    CaptureType.AGENT_REQUEST -> CaptureType.EVENT
    CaptureType.EVENT, CaptureType.UNKNOWN -> CaptureType.TASK
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
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit,
    viewModel: EnrollmentViewModel = containerViewModel {
        EnrollmentViewModel(it.enrollment)
    },
    themeViewModel: ThemeViewModel = containerViewModel {
        ThemeViewModel(it.themeStore)
    },
) {
    val ui by viewModel.ui.collectAsState()
    val enrollment by viewModel.enrollmentState.collectAsState()

    // T-031: the enrollment state's capability list is restore-time empty
    // (EnrollmentManager line ~152) and rendered as a lying "reads". The
    // CapabilityStore (GET /v1/capabilities granted block, V-069) is the
    // single source of truth for §9 gating — render its grants here.
    val appContainer = LocalAppContainer.current
    val liveCaps by appContainer.capabilityStore.capabilities.collectAsState()

    AppScreen(title = "Settings", onBack = onBack) {
        SectionLabel("Device")
        when (val e = enrollment) {
            is EnrollmentManager.State.NotEnrolled -> {
                ListRow(title = "Status", subtitle = "Not enrolled")
                OutlinedTextField(
                    value = ui.serverUrl,
                    onValueChange = viewModel::onServerUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { TextMMD("Server address") },
                    singleLine = true,
                    enabled = !ui.busy,
                )
                OutlinedTextField(
                    value = ui.deviceName,
                    onValueChange = viewModel::onDeviceNameChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    label = { TextMMD("Device name") },
                    singleLine = true,
                    enabled = !ui.busy,
                )
                ButtonMMD(
                    onClick = viewModel::requestEnrollment,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    enabled = ui.canRequest,
                ) { TextMMD(if (ui.busy) "Requesting…" else "Request enrollment") }
            }

            is EnrollmentManager.State.AwaitingApproval -> {
                ListRow(title = "Status", subtitle = "Waiting for approval")
                ListRow(title = "Device", subtitle = e.name)
                ListRow(title = "Server", subtitle = e.baseUrl)
                ListRow(title = "Device ID", subtitle = e.deviceId)
                ButtonMMD(
                    onClick = viewModel::poll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    enabled = !ui.busy,
                ) { TextMMD(if (ui.busy) "Checking…" else "Check again") }
                OutlinedButtonMMD(
                    onClick = viewModel::forget,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) { TextMMD("Forget this device") }
            }

            is EnrollmentManager.State.Active -> {
                ListRow(title = "Status", subtitle = "Active — live data")
                ListRow(title = "Device", subtitle = e.name)
                ListRow(title = "Server", subtitle = e.baseUrl)
                // Live grants (V-069); fall back to enrollment-time list on
                // demo/old servers where granted is null.
                val grants = liveCaps.granted?.capabilities ?: e.capabilities
                ListRow(title = "Capabilities", subtitle = grants.joinToString(", ").ifEmpty { "reads" })
                OutlinedButtonMMD(
                    onClick = viewModel::forget,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) { TextMMD("Forget this device") }
            }
        }
        ui.message?.let { ListRow(title = it) }

        SectionLabel("Appearance")
        val polarity by themeViewModel.polarity.collectAsState()
        ListRow(
            title = "Light",
            trailing = if (polarity == ThemePolarity.LIGHT) "✓" else null,
            onClick = { themeViewModel.setPolarity(ThemePolarity.LIGHT) },
        )
        ListRow(
            title = "Inverted",
            subtitle = "Night reading — flips ink polarity",
            trailing = if (polarity == ThemePolarity.INVERTED) "✓" else null,
            onClick = { themeViewModel.setPolarity(ThemePolarity.INVERTED) },
        )

        SectionLabel("Protocol")
        ListRow(title = "Client", subtitle = "v${AppInfo.APP_PROTOCOL} · minimum server v${AppInfo.MINIMUM_SERVER_PROTOCOL}")

        SectionLabel("Sync")
        ListRow(title = "Mode", subtitle = "Manual — Phase 9/10 add push + periodic")

        SectionLabel("About")
        ListRow(title = "Diagnostics", onClick = onOpenDiagnostics)
        ListRow(title = "Version", subtitle = "0.3.0-dev (Phase 4 — enrollment)")
        ListRow(title = "Data", subtitle = "Fakes until enrolled, then live /v1/")
    }
}
