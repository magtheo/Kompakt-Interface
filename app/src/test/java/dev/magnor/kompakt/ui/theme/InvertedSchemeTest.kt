package dev.magnor.kompakt.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mudita.mmd.eInkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-016 — the inverted e-ink scheme must be an exact, lossless polarity
 * mirror of MMD's scheme: every role is the channel flip of the original
 * (pure grayscale, so channel flip == luminance flip), except scrim which
 * must never brighten.
 */
class InvertedSchemeTest {

    private fun assertFlipped(role: String, base: Color, inverted: Color) {
        assertEquals(
            "$role must be the exact channel flip (RGB, alpha kept)",
            base.toArgb().inv() and 0x00FFFFFF,
            inverted.toArgb() and 0x00FFFFFF,
        )
        assertEquals(
            "$role must keep its alpha (transparent stays transparent)",
            base.toArgb() and 0xFF000000.toInt(),
            inverted.toArgb() and 0xFF000000.toInt(),
        )
    }

    @Test
    fun mmdSchemeFactsTheInversionReliesOn() {
        // Backdrop contract: the root Surface paints scheme.background, so
        // it must be opaque in the base scheme — the inverted backdrop is
        // then opaque black.
        assertEquals(
            "base background must be opaque",
            0xFF,
            (eInkColorScheme.background.toArgb() ushr 24) and 0xFF,
        )
        // MMD's container tier is deliberately transparent (borders-only
        // cards). If a future MMD makes these opaque, re-evaluate whether
        // transparent-stays-transparent is still the right rule.
        val transparentTier = listOf(
            eInkColorScheme.surfaceContainer,
            eInkColorScheme.surfaceContainerHighest,
            eInkColorScheme.surfaceContainerLowest,
        ).any { (it.toArgb() ushr 24) == 0x00 }
        assertEquals("container tier must contain transparent roles", true, transparentTier)
    }

    @Test
    fun everyRoleIsExactChannelFlipExceptScrim() {
        val b = eInkColorScheme
        val i = eInkInvertedColorScheme
        assertFlipped("primary", b.primary, i.primary)
        assertFlipped("onPrimary", b.onPrimary, i.onPrimary)
        assertFlipped("primaryContainer", b.primaryContainer, i.primaryContainer)
        assertFlipped("onPrimaryContainer", b.onPrimaryContainer, i.onPrimaryContainer)
        assertFlipped("inversePrimary", b.inversePrimary, i.inversePrimary)
        assertFlipped("secondary", b.secondary, i.secondary)
        assertFlipped("onSecondary", b.onSecondary, i.onSecondary)
        assertFlipped("secondaryContainer", b.secondaryContainer, i.secondaryContainer)
        assertFlipped("onSecondaryContainer", b.onSecondaryContainer, i.onSecondaryContainer)
        assertFlipped("tertiary", b.tertiary, i.tertiary)
        assertFlipped("onTertiary", b.onTertiary, i.onTertiary)
        assertFlipped("tertiaryContainer", b.tertiaryContainer, i.tertiaryContainer)
        assertFlipped("onTertiaryContainer", b.onTertiaryContainer, i.onTertiaryContainer)
        assertFlipped("background", b.background, i.background)
        assertFlipped("onBackground", b.onBackground, i.onBackground)
        assertFlipped("surface", b.surface, i.surface)
        assertFlipped("onSurface", b.onSurface, i.onSurface)
        assertFlipped("surfaceVariant", b.surfaceVariant, i.surfaceVariant)
        assertFlipped("onSurfaceVariant", b.onSurfaceVariant, i.onSurfaceVariant)
        assertFlipped("surfaceTint", b.surfaceTint, i.surfaceTint)
        assertFlipped("inverseSurface", b.inverseSurface, i.inverseSurface)
        assertFlipped("inverseOnSurface", b.inverseOnSurface, i.inverseOnSurface)
        assertFlipped("error", b.error, i.error)
        assertFlipped("onError", b.onError, i.onError)
        assertFlipped("errorContainer", b.errorContainer, i.errorContainer)
        assertFlipped("onErrorContainer", b.onErrorContainer, i.onErrorContainer)
        assertFlipped("outline", b.outline, i.outline)
        assertFlipped("outlineVariant", b.outlineVariant, i.outlineVariant)
        assertFlipped("surfaceBright", b.surfaceBright, i.surfaceBright)
        assertFlipped("surfaceDim", b.surfaceDim, i.surfaceDim)
        assertFlipped("surfaceContainer", b.surfaceContainer, i.surfaceContainer)
        assertFlipped("surfaceContainerHigh", b.surfaceContainerHigh, i.surfaceContainerHigh)
        assertFlipped("surfaceContainerHighest", b.surfaceContainerHighest, i.surfaceContainerHighest)
        assertFlipped("surfaceContainerLow", b.surfaceContainerLow, i.surfaceContainerLow)
        assertFlipped("surfaceContainerLowest", b.surfaceContainerLowest, i.surfaceContainerLowest)

        assertEquals("scrim stays black", 0xFF000000.toInt(), i.scrim.toArgb())
        assertEquals("scrim unchanged from base", b.scrim.toArgb(), i.scrim.toArgb())
    }

    @Test
    fun flipIsExactInvolution() {
        val samples = listOf(
            eInkColorScheme.background,
            eInkColorScheme.onSurface,
            eInkColorScheme.outline,
            eInkColorScheme.surfaceContainerHighest,
        )
        for (c in samples) {
            val twice = flippedPolarity(flippedPolarity(c))
            assertEquals("double flip must be identity (ARGB bits)", c.toArgb(), twice.toArgb())
        }
    }

    @Test
    fun baseSchemeIsMonochrome() {
        // Documents the invariant the flip relies on: MMD's e-ink scheme is
        // pure grayscale, so a channel flip is a luminance flip (lossless).
        val samples = listOf(
            eInkColorScheme.background,
            eInkColorScheme.surface,
            eInkColorScheme.primary,
            eInkColorScheme.onPrimary,
            eInkColorScheme.surfaceContainer,
            eInkColorScheme.outline,
        )
        for (c in samples) {
            val argb = c.toArgb()
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            assertEquals("R must equal G (grayscale)", r, g)
            assertEquals("G must equal B (grayscale)", g, b)
        }
    }
}
