package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.CalendarRepository
import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.CalendarInfo
import dev.magnor.kompakt.domain.EventCreateResult
import dev.magnor.kompakt.domain.EventDraft
import dev.magnor.kompakt.domain.EventUpdate
import dev.magnor.kompakt.domain.MonthGrid
import dev.magnor.kompakt.domain.RequestId
import dev.magnor.kompakt.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth

/**
 * T-023 calendar state: month navigation, day focus, and windowed event
 * fetches. Fetch policy matches the E-Ink rules — one fetch per window
 * change, no spinners, no retry loops; reopening or navigating refetches.
 *
 * The window is the 42-day grid of the visible month (MonthGrid.window),
 * so lead/trail days always render their events.
 */
class CalendarViewModel(
    private val calendarRepository: CalendarRepository,
    private val now: Instant,
    private val zone: TimeZone = TimeZone.of("Europe/Oslo"),
    private val newRequestId: () -> RequestId,
) : ViewModel() {

    data class CalendarUiState(
        val month: YearMonth,
        /** Nested classes can't see outer ctor vals — carried explicitly. */
        val zone: TimeZone,
        val now: Instant,
        val selectedDay: LocalDate? = null,
        val calendars: List<CalendarInfo> = emptyList(),
        val events: List<CalendarEvent> = emptyList(),
        val error: String? = null,
        /** Sticky one-line outcome of the last write (create/edit/delete). */
        val writeOutcome: String? = null,
    ) {
        val grid: List<LocalDate> get() = MonthGrid.cells(month)
        val byDay: Map<LocalDate, List<CalendarEvent>> get() = MonthGrid.byDay(events, zone)
        val today: LocalDate get() = now.toLocalDateTime(zone).date

        fun eventsOn(day: LocalDate): List<CalendarEvent> = byDay[day].orEmpty()

        fun eventById(id: String): CalendarEvent? = events.firstOrNull { it.id == id }
    }

    private val _state = MutableStateFlow(
        CalendarUiState(
            month = now.toLocalDateTime(zone).date.yearMonth,
            // Opens with today selected — the agenda is visible on entry
            // (T-023a plan: Today date-header enters "at today, selected").
            selectedDay = now.toLocalDateTime(zone).date,
            zone = zone,
            now = now,
        ),
    )
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            calendarRepository.observeCalendars().collect { list ->
                _state.update { it.copy(calendars = list) }
            }
        }
        loadWindow()
    }

    /** Month navigation — clamps the selected day into the new month. */
    fun previousMonth() = shiftMonth(-1)
    fun nextMonth() = shiftMonth(1)

    /** Today button — jump back to the current month with today selected. */
    fun goToToday() {
        _state.update { s -> s.copy(month = s.today.yearMonth, selectedDay = s.today) }
        loadWindow()
    }

    private fun shiftMonth(delta: Int) {
        _state.update { s ->
            val month = s.month.plus(delta.toLong(), kotlinx.datetime.DateTimeUnit.MONTH)
            val day = s.selectedDay?.let { d ->
                if (MonthGrid.inMonth(d, month)) d else null
            }
            s.copy(month = month, selectedDay = day)
        }
        loadWindow()
    }

    fun selectDay(day: LocalDate) = _state.update { it.copy(selectedDay = day) }
    fun clearDay() = _state.update { it.copy(selectedDay = null) }
    fun clearOutcome() = _state.update { it.copy(writeOutcome = null) }

    fun refresh() = loadWindow()

    private fun loadWindow() {
        val month = _state.value.month
        val (from, to) = MonthGrid.window(month)
        viewModelScope.launch {
            try {
                val events = calendarRepository.fetchWindow(from.toString(), to.toString())
                // Ignore stale windows if the user navigated while fetching.
                if (_state.value.month == month) {
                    _state.update { it.copy(events = events, error = null) }
                }
            } catch (e: Exception) {
                if (_state.value.month == month) {
                    _state.update { it.copy(error = e.userMessage()) }
                }
            }
        }
    }

    fun createEvent(draft: EventDraft, onDone: (EventCreateResult?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val result = calendarRepository.createEvent(newRequestId(), draft)
                _state.update { it.copy(writeOutcome = "Created \"${draft.title}\"") }
                loadWindow()
                onDone(result)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage(), writeOutcome = "Create failed") }
                onDone(null)
            }
        }
    }

    fun updateEvent(id: String, patch: EventUpdate, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val ok = calendarRepository.updateEvent(id, patch)
                _state.update {
                    it.copy(writeOutcome = if (ok) "Saved" else "Edit failed")
                }
                if (ok) loadWindow()
                onDone(ok)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage(), writeOutcome = "Edit failed") }
                onDone(false)
            }
        }
    }

    fun deleteEvent(id: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val ok = calendarRepository.deleteEvent(id)
                _state.update {
                    it.copy(
                        events = if (ok) it.events.filterNot { e -> e.id == id } else it.events,
                        writeOutcome = if (ok) "Deleted" else "Delete failed",
                    )
                }
                if (ok) loadWindow()
                onDone(ok)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage(), writeOutcome = "Delete failed") }
                onDone(false)
            }
        }
    }
}
