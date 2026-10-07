package dev.magnor.kompakt.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.mudita.mmd.components.cards.CardMMD

/**
 * T-054: hairline card — the app's [CardMMD] with a 1-physical-pixel border
 * instead of the 1dp default. On the 480x800 panel (density ~1.26) 1dp is
 * ~1.26px, so this is strictly thinner while staying a solid, dither-free
 * line on the B/W e-ink surface (sub-pixel dp widths would antialias to
 * gray). Same shape/fill/elevation as before — only the outline weight
 * changes, app-wide, so every card keeps a common visual language.
 */
@Composable
fun ThinCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Density member: Int.toDp() — no import; 1px stays 1px on any panel.
    val hairline = with(LocalDensity.current) { 1.toDp() }
    val border = BorderStroke(hairline, MaterialTheme.colorScheme.outline)
    if (onClick != null) {
        CardMMD(onClick = onClick, modifier = modifier, border = border, content = content)
    } else {
        CardMMD(modifier = modifier, border = border, content = content)
    }
}
