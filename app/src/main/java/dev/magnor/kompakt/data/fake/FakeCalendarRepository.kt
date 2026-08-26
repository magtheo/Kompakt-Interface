package dev.magnor.kompakt.data.fake

import dev.magnor.kompakt.data.repository.CalendarRepository
import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.CalendarInfo
import dev.magnor.kompakt.domain.EventCreateResult
import dev.magnor.kompakt.domain.EventDraft
import dev.magnor.kompakt.domain.EventUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * T-023 fake calendar repository (demo mode). Stateful: create/update/delete
 * mutate an in-memory list so the demo calendar and VM tests observe real
 * behavior (idempotent replay, window filtering, occurrence ids with `~`).
 *
 * Simplifications vs. the server, documented where they occur:
 * - No recurrence expansion — occurrence ids exist only as seeded entries.
 * - PATCH/DELETE on an occurrence edits/removes the entry itself; the
 *   server instead patches the master or records an exception.
 */
class FakeCalendarRepository(
    initialEvents: List<CalendarEvent> = FakeData.calendarEvents,
) : CalendarRepository {

    private val zone = TimeZone.of("Europe/Oslo")

    private val _events = MutableStateFlow(initialEvents)
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()

    private val replayed = ConcurrentHashMap<String, EventCreateResult>()

    override fun observeCalendars(): StateFlow<List<CalendarInfo>> = MutableStateFlow(listOf(
        CalendarInfo(id = "personal", displayName = "Personal", symbol = "●", writable = true),
        CalendarInfo(id = "sa", displayName = "SA registry", symbol = "○", writable = false),
    ))

    override suspend fun fetchWindow(from: String, to: String): List<CalendarEvent> {
        val lo = LocalDate.parse(from)
        val hi = LocalDate.parse(to)
        return _events.value.filter { e ->
            val d = e.startAt.toLocalDateTime(zone).date
            d in lo..hi
        }
    }

    override suspend fun fetchEvent(id: String): CalendarEvent? =
        _events.value.firstOrNull { it.id == id }

    override suspend fun createEvent(requestId: String, draft: EventDraft): EventCreateResult {
        replayed[requestId]?.let { return it.copy(status = "already_exists") }

        // Server pattern: {calendar_id}:{uid}; uid derived from request id.
        val uid = "req-${requestId.hashCode().toUInt().toString(16)}"
        val id = "${draft.calendarId}:$uid"
        val start = Instant.parse(draft.startAt)
        val end = draft.endAt?.let { Instant.parse(it) }
        val event = CalendarEvent(
            id = id,
            title = draft.title,
            startAt = start,
            endAt = end,
            location = draft.location,
            description = draft.description,
            allDay = draft.allDay,
            symbol = if (draft.calendarId == "personal") "●" else "○",
            calendarId = draft.calendarId,
        )
        _events.value = _events.value + event
        return EventCreateResult(id, "created").also { replayed[requestId] = it }
    }

    override suspend fun updateEvent(id: String, patch: EventUpdate): Boolean {
        if (patch.isEmpty) return false
        val current = _events.value.firstOrNull { it.id == id } ?: return false
        val updated = current.copy(
            title = patch.title ?: current.title,
            startAt = patch.startAt?.let { Instant.parse(it) } ?: current.startAt,
            endAt = patch.endAt?.let { Instant.parse(it) } ?: current.endAt,
            description = patch.description ?: current.description,
            location = patch.location ?: current.location,
        )
        _events.value = _events.value.map { if (it.id == id) updated else it }
        return true
    }

    override suspend fun deleteEvent(id: String): Boolean {
        val before = _events.value
        _events.value = before.filterNot { it.id == id }
        return _events.value.size != before.size
    }
}
