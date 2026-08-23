package dev.magnor.kompakt.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ink polarity of the monochrome UI (e-ink "dark mode" = INVERTED). */
enum class ThemePolarity { LIGHT, INVERTED }

/**
 * Process-lifetime state + tiny file persistence for the theme setting.
 *
 * Unknown or missing file content resolves to LIGHT — fail toward the
 * default rather than a surprise inversion at startup.
 */
class ThemeStore(private val file: File) {

    private val _polarity = MutableStateFlow(load())
    val polarity: StateFlow<ThemePolarity> = _polarity.asStateFlow()

    fun setPolarity(polarity: ThemePolarity) {
        // UI state first: a failed disk write must not freeze the toggle —
        // worst case the choice reverts after a restart.
        _polarity.value = polarity
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(polarity.name)
            if (!tmp.renameTo(file)) file.writeText(polarity.name)
        }
    }

    private fun load(): ThemePolarity =
        runCatching { file.readText() }
            .getOrNull()
            ?.trim()
            ?.uppercase()
            ?.let { stored -> ThemePolarity.entries.firstOrNull { it.name == stored } }
            ?: ThemePolarity.LIGHT
}
