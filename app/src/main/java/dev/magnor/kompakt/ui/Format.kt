package dev.magnor.kompakt.ui

import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.ServerUnavailableException
import dev.magnor.kompakt.domain.UnauthorizedException
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Display formatting for E-Ink: short, static, monochrome-friendly text.
 * All UTC for the fake-data phase; real TZ handling lands with the server
 * contract (Phase 3+).
 */

fun Instant.timeOfDay(): String {
    val t = toLocalDateTime(TimeZone.UTC)
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
    is ServerUnavailableException -> "Server error (HTTP $code) — try again later"
    else -> "Could not load: ${message ?: "unknown error"}"
}
