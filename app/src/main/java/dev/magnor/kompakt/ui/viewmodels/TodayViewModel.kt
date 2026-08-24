package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.TodayRepository
import dev.magnor.kompakt.domain.TodayProjection
import dev.magnor.kompakt.ui.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant

/**
 * Today — operational overview. A projection combining entities owned by
 * the other repositories; this ViewModel renders, it owns nothing.
 *
 * A failed fetch degrades to a single static error line (E-Ink rule:
 * no spinners, no retry loops — reopening the screen refetches).
 */
class TodayViewModel(
    todayRepository: TodayRepository,
    val now: Instant,
    private val onLoaded: ((TodayProjection) -> Unit)? = null,
) : ViewModel() {

    data class TodayUiState(
        val loaded: Boolean = false,
        val projection: TodayProjection? = null,
        val error: String? = null,
    )

    val state: StateFlow<TodayUiState> = todayRepository.observeToday()
        .map {
            onLoaded?.invoke(it) // T-020: local-reminder replan hook
            TodayUiState(loaded = true, projection = it)
        }
        .catch { e -> emit(TodayUiState(loaded = true, error = e.userMessage())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())
}
