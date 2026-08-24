package dev.magnor.kompakt.notifications

import dev.magnor.kompakt.domain.CalendarEvent
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-020 — protocol §5 planning: lead time, horizon window, past-event
 * and inside-lead skipping (anti-spam on replan), ordering.
 */
class ReminderPlannerTest {

    private val now: Instant = Instant.parse("2026-08-24T12:00:00Z")

    private fun event(id: String, startAt: String) = CalendarEvent(
        id = id,
        title = "Event $id",
        startAt = Instant.parse(startAt),
    )

    @Test
    fun `future event arms at start minus lead`() {
        val plan = ReminderPlanner.plan(listOf(event("e1", "2026-08-24T15:00:00Z")), now)
        assertEquals(1, plan.size)
        assertEquals("e1", plan[0].eventId)
        assertEquals(Instant.parse("2026-08-24T14:50:00Z"), plan[0].triggerAt)
        assertEquals("Event e1", plan[0].title)
    }

    @Test
    fun `past events are skipped`() {
        val plan = ReminderPlanner.plan(
            listOf(
                event("past", "2026-08-24T10:00:00Z"),
                event("now", "2026-08-24T12:00:00Z"), // starts *at* now → past
                event("future", "2026-08-24T13:00:00Z"),
            ),
            now,
        )
        assertEquals(listOf("future"), plan.map { it.eventId })
    }

    @Test
    fun `events beyond horizon are skipped`() {
        val plan = ReminderPlanner.plan(
            listOf(
                event("in", "2026-08-25T11:00:00Z"),      // <24h away
                event("out", "2026-08-25T12:00:01Z"),      // >24h away
            ),
            now,
        )
        assertEquals(listOf("in"), plan.map { it.eventId })
    }

    @Test
    fun `event inside its own lead window is skipped not fired`() {
        // Starts in 5 minutes; lead is 10 → trigger would be in the past.
        val plan = ReminderPlanner.plan(listOf(event("soon", "2026-08-24T12:05:00Z")), now)
        assertEquals(emptyList<String>(), plan.map { it.eventId })
    }

    @Test
    fun `shorter lead keeps inside-window event`() {
        val plan = ReminderPlanner.plan(
            listOf(event("soon", "2026-08-24T12:05:00Z")),
            now,
            leadMinutes = 2,
        )
        assertEquals(listOf("soon"), plan.map { it.eventId })
        assertEquals(Instant.parse("2026-08-24T12:03:00Z"), plan[0].triggerAt)
    }

    @Test
    fun `plan sorted by trigger time`() {
        val plan = ReminderPlanner.plan(
            listOf(
                event("late", "2026-08-24T18:00:00Z"),
                event("early", "2026-08-24T13:30:00Z"),
                event("mid", "2026-08-24T15:00:00Z"),
            ),
            now,
        )
        assertEquals(listOf("early", "mid", "late"), plan.map { it.eventId })
    }

    @Test
    fun `empty events yields empty plan`() {
        assertEquals(0, ReminderPlanner.plan(emptyList(), now).size)
    }
}
