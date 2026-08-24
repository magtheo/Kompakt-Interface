package dev.magnor.kompakt.notifications

import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.InboxPriority
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-019 pure logic: stable notification ids, seen-id dedupe, and the
 * notification→route mapping (must equal the inbox tap mapping, T-018).
 */
class AlertNotificationsTest {

    private fun item(
        id: String = "alert:s1:1",
        sourceType: EntityKind? = EntityKind.AGENT_RUN,
        sourceId: String? = "s1",
        priority: InboxPriority = InboxPriority.NORMAL,
    ) = InboxItem(
        id = id,
        sourceType = sourceType,
        sourceId = sourceId,
        title = "build replied: Clean",
        summary = "succeeded",
        timestamp = Instant.parse("2026-08-24T10:00:00Z"),
        priority = priority,
    )

    @Test
    fun `notification id is stable across replays and positive`() {
        val a = AlertNotifications.notificationIdFor("alert:ses_abc:2")
        val b = AlertNotifications.notificationIdFor("alert:ses_abc:2")
        assertEquals(a, b)
        assertTrue(a >= 0)
        assertTrue(a <= Int.MAX_VALUE)
    }

    @Test
    fun `distinct alerts map to distinct notification ids`() {
        // Not a hash-collision proof, but pins the contract for the ids
        // we actually generate (alert:{run}:{episode}).
        val ids = (1..50).map { AlertNotifications.notificationIdFor("alert:run_$it:1") }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `agent-run alert routes to the run screen`() {
        assertEquals("runs/s1", AlertNotifications.routeFor(item()))
    }

    @Test
    fun `non-run items fall back to the generic resolver`() {
        assertEquals(
            "item/alert:x:9",
            AlertNotifications.routeFor(item(id = "alert:x:9", sourceType = EntityKind.TASK, sourceId = "t1")),
        )
    }

    @Test
    fun `seen alerts suppress repeats and new alerts pass`() {
        val seen = SeenAlerts()
        assertTrue(seen.firstSeen("a1"))
        assertFalse(seen.firstSeen("a1"))
        assertTrue(seen.firstSeen("a2"))
    }

    @Test
    fun `seen set is bounded`() {
        val seen = SeenAlerts(capacity = 4)
        listOf("a", "b", "c", "d", "e").forEach { seen.firstSeen(it) }
        // "a" evicted — worst case one silent re-post, by design.
        assertTrue(seen.firstSeen("a"))
        // The rest are still resident.
        assertFalse(seen.firstSeen("e"))
    }
}
