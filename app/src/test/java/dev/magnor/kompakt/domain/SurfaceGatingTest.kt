package dev.magnor.kompakt.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-006: capability-driven surface gating (protocol §9). A feature the
 * server marks false must hide its surface — unknown or absent keys are
 * treated as false (conservative: never show what was not confirmed).
 */
class SurfaceGatingTest {

    private fun caps(features: Map<String, Boolean>) = CapabilitySet(
        serverProtocol = 1,
        minimumClientProtocol = 1,
        features = features,
    )

    @Test
    fun `all features on shows every surface`() {
        val s = SurfaceGating.evaluate(
            caps(
                mapOf(
                    "today" to true, "chat" to true, "agents" to true,
                    "notes" to true, "inbox" to true, "offline_capture" to true,
                ),
            ),
        )
        assertTrue(s.chatTab)
        assertTrue(s.agentsTab)
        assertTrue(s.notesEntry)
        assertTrue(s.captureFab)
        assertTrue(s.inboxEntry)
        assertTrue(s.agentsSectionOnToday)
        assertTrue(s.recentNoteOnToday)
    }

    @Test
    fun `live coordinator shape hides chat agents notes and capture`() {
        // Exactly what :8650 served in the T-004 smoke: read surfaces on,
        // conversational/autonomous surfaces off.
        val s = SurfaceGating.evaluate(
            caps(
                mapOf(
                    "today" to true, "chat" to false, "agents" to false,
                    "projects" to true, "areas" to true, "tasks" to true,
                    "notes" to false, "inbox" to true, "offline_capture" to false,
                ),
            ),
        )
        assertFalse(s.chatTab)
        assertFalse(s.agentsTab)
        assertFalse(s.notesEntry)
        assertFalse(s.captureFab)
        assertFalse(s.agentsSectionOnToday)
        assertFalse(s.recentNoteOnToday)
        assertTrue("inbox stays visible", s.inboxEntry)
    }

    @Test
    fun `absent feature keys default to hidden`() {
        val s = SurfaceGating.evaluate(caps(mapOf("today" to true)))
        assertFalse(s.chatTab)
        assertFalse(s.agentsTab)
        assertFalse(s.notesEntry)
        assertFalse(s.captureFab)
        assertFalse(s.inboxEntry)
    }

    @Test
    fun `demo capabilities present the full information architecture`() {
        val s = SurfaceGating.evaluate(SurfaceGating.DEMO_CAPABILITIES)
        assertTrue(s.chatTab)
        assertTrue(s.agentsTab)
        assertTrue(s.notesEntry)
        assertTrue(s.captureFab)
        assertTrue(s.inboxEntry)
        assertTrue(s.agentsSectionOnToday)
        assertTrue(s.recentNoteOnToday)
    }
}
