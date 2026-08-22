package dev.magnor.kompakt.ui.theme

import androidx.compose.runtime.Composable
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.eInkColorScheme
import com.mudita.mmd.eInkTypography

/**
 * Application theme: Mudita MMD with the monochrome E-Ink color scheme and
 * E-Ink typography. ThemeMMD disables ripple effects globally (D017).
 */
@Composable
fun KompaktTheme(content: @Composable () -> Unit) {
    ThemeMMD(
        colorScheme = eInkColorScheme,
        typography = eInkTypography,
        content = content
    )
}
