package dev.magnor.kompakt.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.eInkColorScheme
import com.mudita.mmd.eInkTypography
import dev.magnor.kompakt.data.ThemePolarity

/**
 * Application theme: Mudita MMD with the monochrome E-Ink color scheme and
 * E-Ink typography. ThemeMMD disables ripple effects globally (D017).
 *
 * The root Surface makes the app genuinely theme-owned: before T-016 the
 * white background was the platform window (manifest pins a Light theme)
 * and text color fell back to LocalContentColor's default — both
 * coincidences that an inverted scheme would have broken.
 *
 * T-055: text selection colors are theme-owned and monochrome-correct —
 * Material's default derives them from `primary`, which is fine in light
 * polarity but we pin them to onSurface (follows inversion) with a 45%
 * fill so the highlight stays readable after the panel's B/W threshold.
 */
@Composable
fun KompaktTheme(
    polarity: ThemePolarity = ThemePolarity.LIGHT,
    content: @Composable () -> Unit,
) {
    val colors = if (polarity == ThemePolarity.INVERTED) eInkInvertedColorScheme else eInkColorScheme
    val selectionColors = TextSelectionColors(
        handleColor = colors.onSurface,
        backgroundColor = colors.onSurface.copy(alpha = 0.45f),
    )
    ThemeMMD(
        colorScheme = colors,
        typography = eInkTypography,
    ) {
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colors.background,
                contentColor = colors.onBackground,
            ) {
                content()
            }
        }
    }
}
