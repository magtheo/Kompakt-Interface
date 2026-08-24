package dev.magnor.kompakt.notifications

import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.EntityId
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * T-020 — protocol §5: local reminders fire from the device itself, so
 * an event still pings when the server is unreachable. Pure planning,
 * no Android imports — fully unit-testable.
 *
 * Semantics:
 *  - only future events inside a 24h horizon get alarms (matches the
 *    Today projection window; replanning is a full re-derive, so the
 *    horizon slides as the projection refreshes)
 *  - alarm fires LEAD before start
 *  - events whose lead window already passed are *skipped*, not fired
 *    instantly — otherwise every replan after the lead moment would
 *    spam a notification for the same event
 */
object ReminderPlanner {

    data class PlannedReminder(
        val eventId: EntityId,
        val title: String,
        val triggerAt: Instant,
    )

    fun plan(
        events: List<CalendarEvent>,
        now: Instant,
        leadMinutes: Long = LEAD_MINUTES,
        horizonHours: Long = HORIZON_HOURS,
    ): List<PlannedReminder> = events.asSequence()
        .filter { it.startAt > now }
        .filter { it.startAt <= now + horizonHours.hours }
        .map { PlannedReminder(it.id, it.title, it.startAt - leadMinutes.minutes) }
        .filter { it.triggerAt > now }
        .sortedBy { it.triggerAt }
        .toList()

    const val LEAD_MINUTES = 10L
    const val HORIZON_HOURS = 24L
}
