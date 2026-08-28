package dev.magnor.kompakt.ui

import dev.magnor.kompakt.domain.ForbiddenException
import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.ServerUnavailableException
import dev.magnor.kompakt.domain.UnauthorizedException
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** T-006: due-bucket labels and static error lines (E-Ink: short, no spinners). */
class FormatTest {

    private val now = Instant.parse("2026-08-22T15:00:00Z")

    @Test
    fun `dayLabel buckets due dates`() {
        assertEquals("Today", Instant.parse("2026-08-22T23:59:00Z").dayLabel(now))
        assertEquals("Tomorrow", Instant.parse("2026-08-23T09:00:00Z").dayLabel(now))
        assertEquals("Overdue", Instant.parse("2026-08-21T20:00:00Z").dayLabel(now))
        assertEquals("08.25", Instant.parse("2026-08-25T12:00:00Z").dayLabel(now))
    }

    @Test
    fun `userMessage maps domain errors to static actionable lines`() {
        assertEquals(
            "Server unreachable — check connection and reopen",
            OfflineException(RuntimeException()).userMessage(),
        )
        assertEquals(
            "Device not authorized — re-enroll in Settings",
            UnauthorizedException().userMessage(),
        )
        assertEquals(
            "Missing permission (project.read) — re-approve device in Settings",
            ForbiddenException("project.read").userMessage(),
        )
        assertEquals(
            "Missing permission — re-approve device in Settings",
            ForbiddenException(null).userMessage(),
        )
        assertEquals(
            "Server error (HTTP 500) — try again later",
            ServerUnavailableException(500).userMessage(),
        )
        assertEquals(
            "Could not load: boom",
            IllegalStateException("boom").userMessage(),
        )
    }

    @Test
    fun `untilLabel renders future buckets`() {
        val now = Instant.parse("2026-08-28T12:00:00Z")
        assertEquals("now", Instant.parse("2026-08-28T12:00:00Z").untilLabel(now, TimeZone.UTC))
        assertEquals("in 45m", Instant.parse("2026-08-28T12:45:00Z").untilLabel(now, TimeZone.UTC))
        assertEquals("in 3h", Instant.parse("2026-08-28T15:00:00Z").untilLabel(now, TimeZone.UTC))
        assertEquals("tomorrow 09:00", Instant.parse("2026-08-29T09:00:00Z").untilLabel(now, TimeZone.UTC))
        assertEquals("2026-09-02", Instant.parse("2026-09-02T09:00:00Z").untilLabel(now, TimeZone.UTC))
    }
}
