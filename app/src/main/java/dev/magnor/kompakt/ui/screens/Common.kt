package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.cards.CardMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

/** Shared building blocks for Phase 1 placeholder screens. */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    // T-036: opaque page background. NavHost transitions (even with
    // EnterTransition.None/ExitTransition.None) compose BOTH destinations for
    // one frame inside AnimatedContent. Screens were transparent (the root
    // Surface painted the backdrop), so that single composite frame showed both
    // pages' content stacked — invisible at 60 Hz LCD, but a visible "flash" on
    // the slow partial-refresh e-ink panel. An opaque per-page background lets
    // the entering page (AnimatedContent places the target on top) fully cover
    // the exiting one, so the framebuffer never contains mixed content.
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBarMMD(
            title = { TextMMD(title, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                val back = onBack
                if (back != null) {
                    IconButton(onClick = back) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = actions,
        )
        Column(
            Modifier
                .fillMaxSize()
                .then(
                    // T-028: screens hosting a viewport-filling lazy layout (Today's
                    // pager) must opt out — a scrollable parent hands it unbounded
                    // height and any scrollable page child crashes on measure.
                    if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
                )
                .padding(16.dp),
        ) {
            content()
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    TextMMD(
        text = text,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    /** Visual de-emphasis (past events, completed rows) — weight only, monochrome-safe. */
    secondary: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    if (onClick != null) {
        CardMMD(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            ListRowContent(title, subtitle, trailing, secondary)
        }
    } else {
        CardMMD(modifier = Modifier.fillMaxWidth()) {
            ListRowContent(title, subtitle, trailing, secondary)
        }
    }
}

@Composable
private fun ListRowContent(title: String, subtitle: String?, trailing: String?, secondary: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 8.dp).weight(1f)) {
            TextMMD(
                text = title,
                fontWeight = if (secondary) FontWeight.Normal else FontWeight.SemiBold,
            )
            if (subtitle != null) {
                TextMMD(text = subtitle)
            }
        }
        if (trailing != null) {
            TextMMD(text = trailing, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextMMD(text = label)
        TextMMD(text = value, fontWeight = FontWeight.Bold)
    }
}

/**
 * Conversation layout (T-013): top bar, scrolling transcript, fixed bottom
 * composer. Replaces AppScreen's scroll-the-whole-page pattern for every
 * message-style screen (chat threads, agent run detail) — the composer must
 * never live below the fold, and the transcript must start at the latest
 * message. No animation on scroll (e-ink: each frame is a full refresh).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    header: @Composable () -> Unit = {},
    transcript: LazyListScope.() -> Unit,
    composer: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBarMMD(
            title = { TextMMD(title, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                val back = onBack
                if (back != null) {
                    IconButton(onClick = back) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = actions,
        )
        header()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            transcript()
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            composer()
        }
    }
}
