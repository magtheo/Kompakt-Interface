package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.TodayRepository
import dev.magnor.kompakt.domain.TodayProjection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant

/**
 * Today — operational overview. A projection combining entities owned by
 * the other repositories; this ViewModel renders, it owns nothing.
 */
class TodayViewModel(
    todayRepository: TodayRepository,
    val now: Instant,
) : ViewModel() {

    data class TodayUiState(
        val loaded: Boolean = false,
        val projection: TodayProjection? = null,
    )

    val state: StateFlow<TodayUiState> = todayRepository.observeToday()
        .map { TodayUiState(loaded = true, projection = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())
}
