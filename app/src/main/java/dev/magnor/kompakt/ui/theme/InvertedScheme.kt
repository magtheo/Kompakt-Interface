package dev.magnor.kompakt.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mudita.mmd.eInkColorScheme

/**
 * Channel-wise RGB flip that preserves the alpha byte exactly — a bitwise
 * involution, so flipping twice yields the original color.
 *
 * MMD's e-ink scheme pairs opaque black/white roles with a deliberately
 * TRANSPARENT surfaceContainer tier (borders-only cards let the backdrop
 * show through — the root Surface in [KompaktTheme] provides that backdrop,
 * opaque in both polarities). Transparent roles must therefore STAY
 * transparent under inversion; forcing them opaque would paint black boxes
 * over the inverted backdrop. Verified against MMD 1.0.2: every role's
 * alpha is either 00 or FF (no partial alphas to preserve semantically).
 */
internal fun flippedPolarity(color: Color): Color {
    val argb = color.toArgb()
    return Color((argb and 0xFF000000.toInt()) or (argb.inv() and 0x00FFFFFF))
}

/**
 * MMD's eInkColorScheme with the ink polarity inverted: every role is the
 * exact channel flip of the original (background white→black, onSurface
 * black→white…), rendering the monochrome UI light-on-dark for night
 * reading. Scrim intentionally stays untouched — scrims dim, never
 * brighten.
 */
val eInkInvertedColorScheme: ColorScheme = inverted(eInkColorScheme)

private fun inverted(s: ColorScheme): ColorScheme = s.copy(
    primary = flippedPolarity(s.primary),
    onPrimary = flippedPolarity(s.onPrimary),
    primaryContainer = flippedPolarity(s.primaryContainer),
    onPrimaryContainer = flippedPolarity(s.onPrimaryContainer),
    inversePrimary = flippedPolarity(s.inversePrimary),
    secondary = flippedPolarity(s.secondary),
    onSecondary = flippedPolarity(s.onSecondary),
    secondaryContainer = flippedPolarity(s.secondaryContainer),
    onSecondaryContainer = flippedPolarity(s.onSecondaryContainer),
    tertiary = flippedPolarity(s.tertiary),
    onTertiary = flippedPolarity(s.onTertiary),
    tertiaryContainer = flippedPolarity(s.tertiaryContainer),
    onTertiaryContainer = flippedPolarity(s.onTertiaryContainer),
    background = flippedPolarity(s.background),
    onBackground = flippedPolarity(s.onBackground),
    surface = flippedPolarity(s.surface),
    onSurface = flippedPolarity(s.onSurface),
    surfaceVariant = flippedPolarity(s.surfaceVariant),
    onSurfaceVariant = flippedPolarity(s.onSurfaceVariant),
    surfaceTint = flippedPolarity(s.surfaceTint),
    inverseSurface = flippedPolarity(s.inverseSurface),
    inverseOnSurface = flippedPolarity(s.inverseOnSurface),
    error = flippedPolarity(s.error),
    onError = flippedPolarity(s.onError),
    errorContainer = flippedPolarity(s.errorContainer),
    onErrorContainer = flippedPolarity(s.onErrorContainer),
    outline = flippedPolarity(s.outline),
    outlineVariant = flippedPolarity(s.outlineVariant),
    scrim = s.scrim, // scrims dim, never brighten
    surfaceBright = flippedPolarity(s.surfaceBright),
    surfaceDim = flippedPolarity(s.surfaceDim),
    surfaceContainer = flippedPolarity(s.surfaceContainer),
    surfaceContainerHigh = flippedPolarity(s.surfaceContainerHigh),
    surfaceContainerHighest = flippedPolarity(s.surfaceContainerHighest),
    surfaceContainerLow = flippedPolarity(s.surfaceContainerLow),
    surfaceContainerLowest = flippedPolarity(s.surfaceContainerLowest),
)
