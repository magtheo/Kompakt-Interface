package dev.magnor.kompakt.ui

import dev.magnor.kompakt.domain.ForbiddenException
import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.ServerUnavailableException
import dev.magnor.kompakt.domain.UnauthorizedException
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Display formatting for E-Ink: short, static, monochrome-friendly text.
 * All UTC for the fake-data phase; real TZ handling lands with the server
 * contract (Phase 3+).
 */

fun Instant.timeOfDay(): String {
    val t = toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d:%02d".format(t.hour, t.minute)
}

fun Instant.dateShort(): String {
    val d = toLocalDateTime(TimeZone.UTC).date
    return "%02d.%02d".format(d.monthNumber, d.dayOfMonth)
}

fun Instant.relativeTo(now: Instant): String {
    val seconds = (now - this).inWholeSeconds
    return when {
        seconds < 0 -> "soon"
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        seconds < 7 * 86_400 -> "${seconds / 86_400}d ago"
        else -> dateShort()
    }
}

/** Future-facing counterpart to [relativeTo] — hero "in 45m / in 2h / tomorrow 09:00". */
fun Instant.untilLabel(now: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val local = toLocalDateTime(zone)
    val days = local.date.toEpochDays() - now.toLocalDateTime(zone).date.toEpochDays()
    val seconds = (this - now).inWholeSeconds
    return when {
        seconds <= 0 -> "now"
        days == 0L && seconds < 3_600 -> "in ${seconds / 60}m"
        days == 0L -> "in ${seconds / 3_600}h"
        days == 1L -> "tomorrow %02d:%02d".format(local.hour, local.minute)
        else -> local.date.humanShort()
    }
}

/**
 * Fixed-English short date, weekday-first — "Wed 2 Sep". Same convention as
 * the fixed-English Today title; kotlinx has no locale names, so hand-rolled.
 * (T-030: untilLabel's far bucket previously leaked ISO "2026-09-02".)
 */
private val MONTHS_SHORT = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val WEEKDAYS_SHORT = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun") // kotlinx DayOfWeek.ordinal: Mon=0

fun LocalDate.humanShort(): String =
    "${WEEKDAYS_SHORT[dayOfWeek.ordinal]} $dayOfMonth ${MONTHS_SHORT[monthNumber - 1]}"

/** Due-date bucket label: Today / Tomorrow / Overdue / date. */
fun Instant.dayLabel(now: Instant): String {
    val zone = TimeZone.UTC
    val date = toLocalDateTime(zone).date
    val today = now.toLocalDateTime(zone).date
    return when {
        date == today -> "Today"
        date == LocalDate.fromEpochDays(today.toEpochDays() + 1) -> "Tomorrow"
        this < now -> "Overdue"
        else -> dateShort()
    }
}

/**
 * Map a repository failure to one short, static, actionable line
 * (E-Ink rule: no dynamic error streams, no spinners).
 */
fun Throwable.userMessage(): String = when (this) {
    is OfflineException -> "Server unreachable — check connection and reopen"
    is UnauthorizedException -> "Device not authorized — re-enroll in Settings"
    is ForbiddenException ->
        if (capability != null) "Missing permission ($capability) — re-approve device in Settings"
        else "Missing permission — re-approve device in Settings"
    is ServerUnavailableException -> "Server error (HTTP $code) — try again later"
    else -> "Could not load: ${message ?: "unknown error"}"
}

/**
 * T-023 calendar display zone. Naive user input (dates, HH:MM) is
 * interpreted here; wire format stays UTC Z (protocol V-065).
 */
val CalendarZone: TimeZone = TimeZone.of("Europe/Oslo")

/** Render an instant in [CalendarZone] (calendar screens only). */
fun Instant.inCalendarZone(): LocalDateTime =
    toLocalDateTime(CalendarZone)
