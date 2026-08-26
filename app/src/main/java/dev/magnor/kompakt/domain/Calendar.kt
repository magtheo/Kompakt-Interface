@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * T-023 calendar domain: registry, drafts, and month-grid math.
 *
 * Wire contract (verified against vault-coordinator V-065 source):
 * - `GET /v1/calendars` → `{calendars:[{id, display_name, symbol, writable}]}`
 * - `GET /v1/schedule/range?from&to` → `{events:[CalendarEvent-wire]}`;
 *   occurrence ids use `~` (never `#`).
 * - `POST /v1/events` → `{id, status}` ("created"|"already_exists").
 * - `PATCH /v1/events/{id}` has NO `all_day`; `DELETE /v1/events/{id}` returns 200 body.
 */

/** One registry calendar — id, e-ink symbol, and whether writes are allowed. */
@Serializable
data class CalendarInfo(
    val id: EntityId,
    @SerialName("display_name") val displayName: String,
    val symbol: String,
    val writable: Boolean = false,
) {
    fun label(): String = "$symbol $displayName"
}

/** Result of a create: id plus idempotent-replay status. */
@Serializable
data class EventCreateResult(
    val id: EntityId,
    val status: String,
) {
    val replayed: Boolean get() = status == "already_exists"
}

/** Create payload for `POST /v1/events`. */
@Serializable
data class EventDraft(
    @SerialName("calendar_id") val calendarId: EntityId,
    val title: String,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val description: String? = null,
    val location: String? = null,
)

/** Partial update for `PATCH /v1/events/{id}`. */
@Serializable
data class EventUpdate(
    val title: String? = null,
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("end_at") val endAt: String? = null,
    val description: String? = null,
    val location: String? = null,
) {
    val isEmpty: Boolean
        get() = title == null && startAt == null && endAt == null &&
            description == null && location == null
}

object MonthGrid {
    const val ROWS = 6
    const val COLS = 7
    const val CELLS = ROWS * COLS

    /** The 42 dates of the grid containing [month], Monday-first. */
    fun cells(month: YearMonth): List<LocalDate> {
        // YearMonth has no atDay() in kotlinx-datetime — build day 1 directly.
        val first = LocalDate(month.year, month.month, 1)
        // kotlinx DayOfWeek enum is declared Monday-first → ordinal IS the
        // Monday-based offset (0=Mon…6=Sun). No .value property exists.
        val lead = first.dayOfWeek.ordinal - DayOfWeek.MONDAY.ordinal
        val start = first.minus(lead, DateTimeUnit.DAY)
        return List(CELLS) { i -> start.plus(i, DateTimeUnit.DAY) }
    }

    fun inMonth(date: LocalDate, month: YearMonth): Boolean = date.year == month.year && date.month == month.month

    fun window(month: YearMonth): Pair<LocalDate, LocalDate> {
        val c = cells(month)
        return c.first() to c.last()
    }

    fun byDay(events: List<CalendarEvent>, zone: TimeZone): Map<LocalDate, List<CalendarEvent>> = events
        .groupBy { it.startAt.toLocalDateTime(zone).date }
        .mapValues { (_, day) -> day.sortedWith(compareBy({ !it.allDay }, { it.startAt })) }
}
