package dev.magnor.kompakt.ui

import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.ServerUnavailableException
import dev.magnor.kompakt.domain.UnauthorizedException
import kotlinx.datetime.Instant
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
            "Server error (HTTP 500) — try again later",
            ServerUnavailableException(500).userMessage(),
        )
        assertEquals(
            "Could not load: boom",
            IllegalStateException("boom").userMessage(),
        )
    }
}
