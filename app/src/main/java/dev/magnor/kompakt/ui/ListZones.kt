package dev.magnor.kompakt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.text.TextMMD

/**
 * T-056 zone vocabulary for headed, flat lists (pilot: Chats tab). On a B/W
 * e-ink panel color can't separate meaning, so each semantic role gets ONE
 * distinct treatment:
 *
 *  - [ZoneHeading] — small bold caps zone boundary ("Start", "Recent")
 *  - [PrimaryActionRow] — inverted surface; the single loud action per screen
 *    (inversion is already the interactive language — T-055 selection)
 *  - [FlatRow] — borderless row for content and quiet controls; boxes
 *    ([ThinCard]) stop being the default so actions and content stop
 *    blending into a wall of identical outlines (T-054 follow-up)
 *  - [HairlineDivider] — open 1-physical-pixel rule BETWEEN rows (an
 *    e-reader TOC list, not closed cards)
 */
@Composable
fun ZoneHeading(text: String) {
    TextMMD(
        text = text.uppercase(),
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

/**
 * The screen's primary action: an inverted row (inverseSurface/inverseOnSurface
 * — follows theme polarity). Same internal geometry as [FlatRow]/ListRow so
 * it reads as "row, but loud" rather than a foreign element.
 */
@Composable
fun PrimaryActionRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        onClick = onClick,
    ) {
        FlatRowContent(title, subtitle, trailing, capLines = true)
    }
}

/**
 * Borderless list row. Mirrors ListRow's text hierarchy (SemiBold title,
 * regular subtitle, bold trailing) with slightly tighter padding — separation
 * comes from [HairlineDivider]s and spacing, not boxes. Tappable rows cap
 * lines (a detail screen carries the full text); static rows don't.
 */
@Composable
fun FlatRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    /** Visual de-emphasis — weight only, monochrome-safe (same as ListRow). */
    secondary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlatRowContent(
            title = title,
            subtitle = subtitle,
            trailing = trailing,
            secondary = secondary,
            capLines = onClick != null,
        )
    }
}

@Composable
private fun FlatRowContent(
    title: String,
    subtitle: String?,
    trailing: String?,
    secondary: Boolean = false,
    capLines: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            TextMMD(
                text = title,
                fontWeight = if (secondary) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = if (capLines) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                TextMMD(
                    text = subtitle,
                    maxLines = if (capLines) 2 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            TextMMD(text = trailing, fontWeight = FontWeight.Bold)
        }
    }
}

/** Open hairline rule between flat rows — the list idiom, not a closed card. */
@Composable
fun HairlineDivider() {
    val hairline = with(LocalDensity.current) { 1.toDp() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(hairline)
            .background(MaterialTheme.colorScheme.outline),
    )
}
