package dev.magnor.kompakt.data

import dev.magnor.kompakt.data.repository.SyncRepository
import dev.magnor.kompakt.domain.CapabilitySet
import dev.magnor.kompakt.domain.SurfaceGating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Cache of GET /v1/capabilities — the single source for protocol §9 gating.
 *
 * Starts from the demo set (full UI) and is refreshed whenever the remote
 * stack activates (enrollment flip or static Remote construction). A failed
 * refresh keeps the last known set; [AppContainer.refreshCapabilities] is the
 * manual retry hook for Settings/Diagnostics.
 */
class CapabilityStore(private val scope: CoroutineScope) {

    private val state = MutableStateFlow(SurfaceGating.DEMO_CAPABILITIES)

    val capabilities: StateFlow<CapabilitySet> = state

    fun refresh(syncRepository: SyncRepository) {
        scope.launch {
            runCatching { syncRepository.capabilities() }
                .onSuccess { state.value = it }
        }
    }
}
