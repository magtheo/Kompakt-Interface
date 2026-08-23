package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import dev.magnor.kompakt.data.ThemePolarity
import dev.magnor.kompakt.data.ThemeStore
import kotlinx.coroutines.flow.StateFlow

/** Reads/writes the persisted ink polarity (Settings → Appearance). */
class ThemeViewModel(private val store: ThemeStore) : ViewModel() {
    val polarity: StateFlow<ThemePolarity> = store.polarity
    fun setPolarity(polarity: ThemePolarity) = store.setPolarity(polarity)
}
