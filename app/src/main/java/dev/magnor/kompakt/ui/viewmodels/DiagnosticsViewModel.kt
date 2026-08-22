package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.SyncRepository
import dev.magnor.kompakt.domain.CapabilitySet
import dev.magnor.kompakt.domain.ProtocolNegotiation
import dev.magnor.kompakt.domain.ProtocolVerdict
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.domain.ServerStatus
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
    )

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            syncRepository.observeLastSync().collect { last ->
                _state.update { it.copy(lastSync = last) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val caps = syncRepository.capabilities()
            val status = syncRepository.status()
            val page = syncRepository.changesSince(null)
            _state.update {
                it.copy(
                    capabilities = caps,
                    verdict = ProtocolNegotiation.evaluate(
                        clientProtocol, minimumServerProtocol, caps,
                    ),
                    serverStatus = status,
                    pendingChanges = page.changes.size,
                )
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            syncRepository.markSynced(newRequestId())
            refresh()
        }
    }
}
