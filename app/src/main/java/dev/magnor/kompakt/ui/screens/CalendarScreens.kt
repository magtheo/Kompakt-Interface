package dev.magnor.kompakt.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.CalendarInfo
import dev.magnor.kompakt.domain.MonthGrid
import dev.magnor.kompakt.ui.CalendarZone
import dev.magnor.kompakt.ui.inCalendarZone
import dev.magnor.kompakt.ui.viewmodels.CalendarViewModel
import dev.magnor.kompakt.ui.viewmodels.EventViewModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/**
 * T-023 calendar screens (V-065): month grid + day agenda, event detail,
 * event editor. E-Ink rules: monochrome, static (no animations), borders
 * instead of color fills, one error line — no spinners.
 */

private val WEEKDAY_HEADER = listOf("M", "T", "W", "T", "F", "S", "S")

@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onBack: () -> Unit,
    onOpenEvent: (String) -> Unit,
    onNewEvent: (LocalDate) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    AppScreen(
        title = "Calendar",
        onBack = onBack,
        actions = {
            OutlinedButtonMMD(onClick = { viewModel.goToToday() }) { TextMMD("Today") }
            OutlinedButtonMMD(onClick = { viewModel.previousMonth() }) { TextMMD("<") }
            TextMMD(
                text = monthLabel(state.month),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            OutlinedButtonMMD(onClick = { viewModel.nextMonth() }) { TextMMD(">") }
        },
    ) {
        if (state.error != null) {
            TextMMD(text = state.error.orEmpty(), fontWeight = FontWeight.Bold)
        }

        // Weekday header — Monday-first (MonthGrid.cells).
        Row(Modifier.fillMaxWidth()) {
            WEEKDAY_HEADER.forEach {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TextMMD(text = it, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 6×7 grid. Borders mark today / selected; dimmed numbers are
        // lead/trail days from neighbouring months.
        state.grid.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        inMonth = MonthGrid.inMonth(day, state.month),
                        isToday = day == state.today,
                        isSelected = day == state.selectedDay,
                        symbols = state.eventsOn(day).mapNotNull { e ->
                            e.symbol.ifEmpty { null }
                        },
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.selectDay(day) },
                    )
                }
            }
        }

        // Day agenda under the grid — the selected day's events.
        val selected = state.selectedDay
        if (selected != null) {
            SectionLabel(dayLabel(selected))
            val events = state.eventsOn(selected)
            if (events.isEmpty()) {
                TextMMD("No events")
            } else {
                events.forEach { event ->
                    ListRow(
                        title = event.title,
                        subtitle = eventRange(event),
                        trailing = event.symbol.ifEmpty { null },
                        onClick = { onOpenEvent(event.id) },
                    )
                }
            }
            OutlinedButtonMMD(
                onClick = { onNewEvent(selected) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { TextMMD("+ New event") }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    symbols: List<String>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Weight comes from the RowScope caller — cells fill their column 1:1.
    var m = modifier
        .aspectRatio(1f)
        .padding(1.dp)
        .clickable(onClick = onClick)
    if (isToday || isSelected) {
        // border() defaults to RectangleShape — no shape param needed.
        m = m.border(
            width = if (isSelected) 2.dp else 1.dp,
            color = androidx.compose.ui.graphics.Color.Black,
        )
    }
    Box(m, contentAlignment = Alignment.TopStart) {
        Column(Modifier.padding(4.dp)) {
            TextMMD(
                text = day.dayOfMonth.toString(),
                fontWeight = when {
                    isToday -> FontWeight.Bold
                    inMonth -> FontWeight.Normal
                    else -> FontWeight.Light
                },
            )
            // Up to two calendar symbols under the number.
            symbols.distinct().take(2).forEach { s ->
                TextMMD(text = s)
            }
        }
    }
}

private fun monthLabel(month: YearMonth): String {
    val name = month.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$name ${month.year}"
}

private fun dayLabel(day: LocalDate): String {
    val name = day.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$name ${day.dayOfMonth}"
}

private fun eventRange(event: CalendarEvent): String {
    if (event.allDay) return "All day"
    val start = event.startAt.inCalendarZone()
    val end = event.endAt?.inCalendarZone()
    val time = "%02d:%02d".format(start.time.hour, start.time.minute) +
        (end?.let { "–%02d:%02d".format(it.time.hour, it.time.minute) } ?: "")
    return "${start.date} $time"
}

// ---------------------------------------------------------------- detail

@Composable
fun EventDetailScreen(
    eventId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: EventViewModel,
) {
    val state by viewModel.state.collectAsState()
    val calendars by viewModel.calendars.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(eventId) { viewModel.load(eventId) }

    AppScreen(
        title = state.event?.title ?: if (state.loading) "Event" else "Event not found",
        onBack = onBack,
    ) {
        val event = state.event
        if (state.error != null) {
            TextMMD(text = state.error.orEmpty(), fontWeight = FontWeight.Bold)
        }
        if (event == null) {
            if (!state.loading && state.error == null) {
                TextMMD("This event does not exist (it may have been deleted).")
            }
        } else {
            val writable = state.writable(calendars)
            DetailRow("Calendar", calendarLabel(calendars, event))
            DetailRow("When", eventRange(event))
            if (event.location != null) DetailRow("Location", event.location)
            if (event.description != null) DetailRow("Note", event.description)
            if ("~" in event.id) {
                TextMMD(
                    "One occurrence of a recurring series. Edits and deletes apply to this occurrence.",
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            if (!writable) {
                TextMMD(
                    "Read-only calendar — edits are disabled.",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButtonMMD(onClick = { onEdit(event.id) }) { TextMMD("Edit") }
                    if (!confirmDelete) {
                        OutlinedButtonMMD(onClick = { confirmDelete = true }) { TextMMD("Delete") }
                    } else {
                        ButtonMMD(onClick = { viewModel.delete() }) { TextMMD("Confirm delete") }
                    }
                }
            }
        }
    }
}

private fun calendarLabel(calendars: List<CalendarInfo>, event: CalendarEvent): String {
    val info = calendars.firstOrNull { it.id == event.calendarId }
    return info?.label() ?: event.calendarId ?: "—"
}

// ---------------------------------------------------------------- editor

@Composable
fun EventEditorScreen(
    eventId: String,
    initialDate: String?,
    onDone: () -> Unit,
    viewModel: EventViewModel,
) {
    val state by viewModel.state.collectAsState()
    val calendars by viewModel.calendars.collectAsState()

    LaunchedEffect(eventId) { viewModel.load(eventId) }
    // Prefill: capture's chosen date, then default calendar (once each).
    LaunchedEffect(initialDate) {
        if (!initialDate.isNullOrBlank()) viewModel.setDate(initialDate)
    }
    LaunchedEffect(calendars) { viewModel.applyDefaultCalendar() }
    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onDone()
    }

    AppScreen(
        title = if (state.isNew) "New event" else "Edit event",
        onBack = onDone,
    ) {
        if (state.error != null) {
            TextMMD(text = state.error.orEmpty(), fontWeight = FontWeight.Bold)
        }

        // Calendar picker — writable registries only, only for new events
        // (the server PATCH contract has no calendar move).
        if (state.isNew) {
            SectionLabel("Calendar")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                calendars.filter { it.writable }.forEach { cal ->
                    val selected = state.calendarId == cal.id
                    OutlinedButtonMMD(onClick = { viewModel.setCalendarId(cal.id) }) {
                        TextMMD(
                            text = cal.label(),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        SectionLabel("Title")
        OutlinedTextField(
            value = state.title,
            onValueChange = viewModel::setTitle,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        SectionLabel("All day")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButtonMMD(onClick = { viewModel.setAllDay(true) }) {
                TextMMD("Yes", fontWeight = if (state.allDay) FontWeight.Bold else FontWeight.Normal)
            }
            OutlinedButtonMMD(onClick = { viewModel.setAllDay(false) }) {
                TextMMD("No", fontWeight = if (!state.allDay) FontWeight.Bold else FontWeight.Normal)
            }
        }

        SectionLabel("Date (YYYY-MM-DD)")
        OutlinedTextField(
            value = state.date,
            onValueChange = viewModel::setDate,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        if (!state.allDay) {
            SectionLabel("Start (HH:MM)")
            OutlinedTextField(
                value = state.start,
                onValueChange = viewModel::setStart,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            SectionLabel("End (HH:MM) — empty = +1h")
            OutlinedTextField(
                value = state.end,
                onValueChange = viewModel::setEnd,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        SectionLabel("Location")
        OutlinedTextField(
            value = state.location,
            onValueChange = viewModel::setLocation,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        SectionLabel("Note")
        OutlinedTextField(
            value = state.description,
            onValueChange = viewModel::setDescription,
            modifier = Modifier.fillMaxWidth(),
        )

        ButtonMMD(
            onClick = viewModel::save,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { TextMMD(if (state.isNew) "Create" else "Save") }
    }
}
