package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.cards.CardMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.ui.containerViewModel
import dev.magnor.kompakt.ui.timeOfDay
import dev.magnor.kompakt.ui.untilLabel
import dev.magnor.kompakt.ui.viewmodels.TodayViewModel
import dev.magnor.kompakt.ui.viewmodels.TodayViewModel.TodayUiState
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Today — operational overview (T-026 tabbed redesign, D031).
 * Three swipe/tap tabs: NOW (hero + timeline + anytime), TASKS
 * (quick-complete — the single mutation D031 grants this surface),
 * ATTENTION (cards). Agents + recent note were cut (placement
 * reopened later); empty sections render nothing.
 */
@Composable
fun TodayScreen(
    onOpenInbox: () -> Unit,
    onOpenCalendar: () -> Unit = {},
    onOpenAttention: (InboxItem) -> Unit = {},
    showInboxAction: Boolean = true,
    viewModel: TodayViewModel = containerViewModel {
        TodayViewModel(
            it.todayRepository,
            it.now(),
            it.onTodayLoaded,
            it.calendarRepository,
            it.taskRepository,
            it::nextRequestId,
        )
    },
) {
    val state by viewModel.state.collectAsState()
    val projection = state.projection
    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    val scope = rememberCoroutineScope()

    AppScreen(
        title = viewModel.now.titleToday(),
        actions = {
            // D030 entry, icon-only — minimal footprint next to the bell.
            IconButton(onClick = onOpenCalendar) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = "Calendar")
            }
            if (showInboxAction) {
                IconButton(onClick = onOpenInbox) {
                    Icon(Icons.Filled.Notifications, contentDescription = "Inbox")
                }
            }
        },
    ) {
        when {
            // Static error line — E-Ink rule: no spinners, reopening refetches.
            projection == null && state.error != null ->
                ListRow(title = state.error ?: "Could not load")
            projection == null -> ListRow(title = "Loading…")
            else -> {
                TodayTabRow(
                    selected = pagerState.currentPage,
                    openCount = state.openTasks.size,
                    attentionCount = state.attentionCount,
                    onSelect = { page -> scope.launch { pagerState.scrollToPage(page) } },
                )
                HorizontalPager(state = pagerState) { page ->
                    when (page) {
                        0 -> NowPage(state, viewModel.now)
                        1 -> TasksPage(state, onComplete = viewModel::completeTask)
                        else -> AttentionPage(state, onOpenAttention, onOpenInbox)
                    }
                }
            }
        }
    }
}

/** "Today · Fri 28 Aug" — fixed-English, locale-free (Oslo date, T-023 tz). */
private fun Instant.titleToday(): String {
    val d = toLocalDateTime(TimeZone.of("Europe/Oslo"))
    val day = d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    val month = d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return "Today · $day ${d.dayOfMonth} $month"
}

/** Static-text tab rail — selected tab bold + rule-underlined, counts inline, no badges. */
@Composable
private fun TodayTabRow(
    selected: Int,
    openCount: Int,
    attentionCount: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TodayTabLabel("NOW", selected == 0) { onSelect(0) }
        TodayTabLabel(label("TASKS", openCount), selected == 1) { onSelect(1) }
        TodayTabLabel(label("ATTENTION", attentionCount), selected == 2) { onSelect(2) }
    }
}

private fun label(base: String, count: Int) = if (count > 0) "$base $count" else base

@Composable
private fun RowScope.TodayTabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        // T-027: hug the text — without IntrinsicSize.Min the fillMaxWidth() underline
        // below makes the FIRST Row child consume the whole row, starving the other
        // tabs to zero width (only "NOW" ever visible — found in on-device smoke).
        Modifier
            .width(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextMMD(text = text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        // Rule underline — solid when selected, invisible otherwise; static ink.
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface),
        )
    }
}

/** NOW — hero next-up card, now-marker timeline, anytime band. A view; no mutations. */
@Composable
private fun NowPage(state: TodayUiState, now: Instant) {
    val hero = state.nextUp
    if (hero != null) {
        CardMMD(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                TextMMD(text = "NEXT UP")
                TextMMD(text = hero.title, fontWeight = FontWeight.Bold)
                TextMMD(text = "${hero.startAt.timeOfDay()} · ${hero.startAt.untilLabel(now)}")
                if (!hero.location.isNullOrBlank()) {
                    TextMMD(text = hero.location)
                }
            }
        }
    } else if (state.remainingEvents.isEmpty() && state.pastEvents.isEmpty()) {
        ListRow(title = "Nothing scheduled")
    }

    val later = state.remainingEvents.filter { it.id != hero?.id }
    if (later.isNotEmpty()) {
        SectionLabel("Later")
        later.forEach { event ->
            ListRow(title = event.title, trailing = event.startAt.timeOfDay())
        }
    }

    if (state.pastEvents.isNotEmpty()) {
        SectionLabel("Earlier")
        state.pastEvents.forEach { event ->
            ListRow(title = event.title, trailing = event.startAt.timeOfDay(), secondary = true)
        }
        TextMMD(
            text = "· now ·",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
    }

    val untimed = state.openTasks.filter { it.dueAt == null }
    if (untimed.isNotEmpty()) {
        SectionLabel("Anytime")
        untimed.forEach { task ->
            ListRow(title = task.title, trailing = "○")
        }
    }
}

/** TASKS — due-today list; quick-complete (D031) is the one mutation. */
@Composable
private fun TasksPage(state: TodayUiState, onComplete: (dev.magnor.kompakt.domain.Task) -> Unit) {
    state.notice?.let {
        TextMMD(
            text = it,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )
    }

    if (state.openTasks.isEmpty() && state.doneToday.isEmpty()) {
        ListRow(title = "Nothing due today")
        return
    }

    SectionLabel("Due today")
    state.openTasks.forEach { task ->
        ListRow(
            title = task.title,
            trailing = "○",
            // D031: whole row toggles complete — big target, one tap.
            onClick = { onComplete(task) },
        )
    }
    if (state.openTasks.isEmpty()) {
        ListRow(title = "All done for today")
    }

    if (state.doneToday.isNotEmpty()) {
        SectionLabel("Done today")
        state.doneToday.forEach { task ->
            ListRow(title = task.title, trailing = "✓", secondary = true)
        }
    }
}

/** ATTENTION — one bordered card per item; T-018 deep-link behavior preserved. */
@Composable
private fun AttentionPage(
    state: TodayUiState,
    onOpenAttention: (InboxItem) -> Unit,
    onOpenInbox: () -> Unit,
) {
    val attention = state.projection?.attention.orEmpty()
    if (attention.isEmpty()) {
        ListRow(title = "All clear")
        return
    }
    attention.forEach { item ->
        ListRow(
            title = item.title,
            subtitle = item.summary,
            trailing = "●",
            onClick = {
                if (item.sourceType == EntityKind.AGENT_RUN && !item.sourceId.isNullOrBlank()) {
                    onOpenAttention(item)
                } else {
                    onOpenInbox()
                }
            },
        )
    }
}
