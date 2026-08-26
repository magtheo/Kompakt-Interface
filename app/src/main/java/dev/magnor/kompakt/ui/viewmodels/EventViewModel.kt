package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.CalendarRepository
import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.CalendarInfo
import dev.magnor.kompakt.domain.EventDraft
import dev.magnor.kompakt.domain.EventUpdate
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.plus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * T-023 detail/editor state for one event (V-065). Independent of the month
 * grid — loads by id via GET /v1/events/{id}, so deep links and occurrences
 * (`~slot`) work without the grid's window fetch.
 *
 * Naive user input (date "YYYY-MM-DD", time "HH:MM") is interpreted in
 * Europe/Oslo and sent as UTC Z datetimes; all-day events send midnight
 * Oslo (server slices to the date part).
 */
class EventViewModel(
    private val calendarRepository: CalendarRepository,
    private val now: Instant,
    private val zone: TimeZone = TimeZone.of("Europe/Oslo"),
    private val newRequestId: () -> RequestId,
) : ViewModel() {

    data class EventUiState(
        val loading: Boolean = true,
        val event: CalendarEvent? = null,
        val calendars: List<CalendarInfo> = emptyList(),
        /** Form fields — prefilled from the event once loaded (or defaults). */
        val title: String = "",
        val calendarId: String? = null,
        val allDay: Boolean = false,
        val date: String = "",   // YYYY-MM-DD
        val start: String = "",  // HH:MM
        val end: String = "",    // HH:MM
        val location: String = "",
        val description: String = "",
        val error: String? = null,
        val saved: Boolean = false,
        val deleted: Boolean = false,
    ) {
        val isNew: Boolean get() = event == null && !loading
        val isOccurrence: Boolean get() = event?.id?.contains("~") == true

        /** Write gating: registry must exist AND be writable (read-only SA). */
        fun writable(calendars: List<CalendarInfo>): Boolean {
            val calId = event?.calendarId ?: calendarId
            return calendars.firstOrNull { it.id == calId }?.writable == true
        }

        val defaultCalendarId: String? get() = calendarId
    }

    private val _state = MutableStateFlow(EventUiState())
    val state: StateFlow<EventUiState> = _state.asStateFlow()

    val calendars: StateFlow<List<CalendarInfo>> = calendarRepository.observeCalendars()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Load an existing event by id; null id (or 404) starts a fresh form. */
    fun load(eventId: String?) {
        if (eventId == null || eventId == "new") {
            startNew()
            return
        }
        viewModelScope.launch {
            try {
                val event = calendarRepository.fetchEvent(eventId)
                if (event == null) {
                    startNew()
                } else {
                    val local = event.startAt.toLocalDateTime(zone)
                    val endLocal = event.endAt?.toLocalDateTime(zone)
                    _state.update {
                        it.copy(
                            loading = false,
                            event = event,
                            title = event.title,
                            calendarId = event.calendarId,
                            allDay = event.allDay,
                            date = local.date.toString(),
                            start = local.time.toString(),
                            end = endLocal?.time?.toString() ?: "",
                            location = event.location.orEmpty(),
                            description = event.description.orEmpty(),
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    private fun startNew() {
        val today = now.toLocalDateTime(zone)
        _state.update {
            it.copy(
                loading = false,
                event = null,
                date = today.date.toString(),
                start = "%02d:00".format(today.hour),
                end = "%02d:00".format((today.hour + 1).coerceAtMost(23)),
            )
        }
    }

    // ---- form field setters ----

    fun setTitle(v: String) = _state.update { it.copy(title = v, error = null) }
    fun setCalendarId(v: String) = _state.update { it.copy(calendarId = v, error = null) }
    fun setAllDay(v: Boolean) = _state.update { it.copy(allDay = v, error = null) }
    fun setDate(v: String) = _state.update { it.copy(date = v.trim(), error = null) }
    fun setStart(v: String) = _state.update { it.copy(start = v.trim(), error = null) }
    fun setEnd(v: String) = _state.update { it.copy(end = v.trim(), error = null) }
    fun setLocation(v: String) = _state.update { it.copy(location = v, error = null) }
    fun setDescription(v: String) = _state.update { it.copy(description = v, error = null) }

    /** Default the calendar picker to the first writable registry (once). */
    fun applyDefaultCalendar() {
        _state.update { s ->
            if (s.calendarId == null && s.event == null) {
                s.copy(calendarId = calendars.value.firstOrNull { it.writable }?.id)
            } else {
                s
            }
        }
    }

    // ---- actions ----

    fun save() {
        val s = _state.value
        val event = s.event
        try {
            if (event == null) create(s) else update(event, s)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    private fun create(s: EventUiState) {
        val calId = requireWritableCalendar(s)
        val start = parseStart(s)
        val end = if (s.allDay) null else parseEnd(s, start)
        viewModelScope.launch {
            try {
                calendarRepository.createEvent(
                    newRequestId(),
                    EventDraft(
                        calendarId = calId,
                        title = s.title.trim(),
                        startAt = start.toString(),
                        endAt = end?.toString(),
                        allDay = s.allDay,
                        description = s.description.trim().ifEmpty { null },
                        location = s.location.trim().ifEmpty { null },
                    ),
                )
                _state.update { it.copy(saved = true, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    private fun update(event: CalendarEvent, s: EventUiState) {
        val patch = EventUpdate(
            title = s.title.trim().ifEmpty { null },
            startAt = parseStart(s).toString(),
            endAt = if (s.allDay) null else parseEnd(s, parseStart(s)).toString(),
            description = s.description.trim().ifEmpty { null },
            location = s.location.trim().ifEmpty { null },
        )
        viewModelScope.launch {
            try {
                val ok = calendarRepository.updateEvent(event.id, patch)
                _state.update {
                    if (ok) it.copy(saved = true, error = null)
                    else it.copy(error = "Save failed")
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    fun delete() {
        val id = _state.value.event?.id ?: return
        viewModelScope.launch {
            try {
                val ok = calendarRepository.deleteEvent(id)
                _state.update { if (ok) it.copy(deleted = true) else it.copy(error = "Delete failed") }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    // ---- input parsing (Europe/Oslo for naive values) ----

    private fun requireWritableCalendar(s: EventUiState): String {
        val calId = s.calendarId
            ?: throw IllegalArgumentException("Pick a calendar")
        val info = calendars.value.firstOrNull { it.id == calId }
            ?: throw IllegalArgumentException("Unknown calendar")
        if (!info.writable) throw IllegalArgumentException("Calendar is read-only")
        return calId
    }

    private fun parseStart(s: EventUiState): Instant = parseLocal(s.date, s.start, "start")

    private fun parseEnd(s: EventUiState, start: Instant): Instant {
        val end = if (s.end.isEmpty()) {
            start.plus(1, kotlinx.datetime.DateTimeUnit.HOUR, zone)
        } else {
            parseLocal(s.date, s.end, "end")
        }
        if (end <= start) throw IllegalArgumentException("End must be after start")
        return end
    }

    private fun parseLocal(dateStr: String, timeStr: String, field: String): Instant {
        val date = try {
            LocalDate.parse(dateStr)
        } catch (e: Exception) {
            throw IllegalArgumentException("Date must be YYYY-MM-DD")
        }
        val time = try {
            LocalTime.parse(timeStr)
        } catch (e: Exception) {
            throw IllegalArgumentException("$field time must be HH:MM")
        }
        return date.atTime(time).toInstant(zone)
    }
}
