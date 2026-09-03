package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.SyncRepository
import dev.magnor.kompakt.domain.CapabilitySet
import dev.magnor.kompakt.domain.ProtocolNegotiation
import dev.magnor.kompakt.domain.ProtocolVerdict
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.ServerStatus
import dev.magnor.kompakt.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Diagnostics — capability negotiation, server status, change backlog.
 * Protocol rules (protocol §9): hard-stop with an explicit error when
 * client < minimum_client_protocol; disabled features stay hidden.
 *
 * T-045: offline is a normal state (tailnet-only server, T-044 windowed
 * sync). Every probe degrades independently and the first transport
 * failure wins the honest error line — never an uncaught exception.
 */
class DiagnosticsViewModel(
    private val syncRepository: SyncRepository,
    private val clientProtocol: Int,
    private val minimumServerProtocol: Int,
    private val newRequestId: () -> RequestId,
) : ViewModel() {

    data class DiagnosticsUiState(
        val capabilities: CapabilitySet? = null,
        val verdict: ProtocolVerdict? = null,
        val serverStatus: ServerStatus? = null,
        val pendingChanges: Int? = null,
        val lastSync: Instant? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            try {
                syncRepository.observeLastSync().collect { last ->
                    _state.update { it.copy(lastSync = last) }
                }
            } catch (e: Exception) {
                // T-045: transport failure degrades to the error line —
                // "never an uncaught exception" must hold here too.
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            var transportError: String? = null
            fun noteFailure(e: Throwable) {
                if (transportError == null) transportError = e.userMessage()
            }
            val caps = runCatching { syncRepository.capabilities() }
                .onFailure(::noteFailure).getOrNull()
            val status = runCatching { syncRepository.status() }
                .onFailure(::noteFailure).getOrNull()
            val page = runCatching { syncRepository.changesSince(null) }
                .onFailure(::noteFailure).getOrNull()
            _state.update {
                it.copy(
                    capabilities = caps,
                    verdict = caps?.let { c ->
                        ProtocolNegotiation.evaluate(clientProtocol, minimumServerProtocol, c)
                    },
                    serverStatus = status,
                    pendingChanges = page?.changes?.size,
                    error = transportError,
                )
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            runCatching { syncRepository.markSynced(newRequestId()) }
                .onFailure { e -> _state.update { it.copy(error = e.userMessage()) } }
            refresh()
        }
    }
}
