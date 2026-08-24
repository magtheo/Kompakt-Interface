package dev.magnor.kompakt.ui.navigation

import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.InboxItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    @Test
    fun everyScreenHasExactlyOneRoute() {
        assertEquals(17, Routes.all.size)
        assertEquals(Routes.all.size, Routes.all.toSet().size)
    }

    @Test
    fun topLevelNavigationIsExactlyFourTabs() {
        assertEquals(4, TopLevelDestination.entries.size)
        assertEquals(
            listOf("Today", "Chat", "Agents", "More"),
            TopLevelDestination.entries.map { it.label },
        )
        assertTrue(TopLevelDestination.routes.all { it in Routes.all })
    }

    @Test
    fun argRouteBuildersMatchTheirPatterns() {
        assertEquals("chat/9", Routes.chatThread("9"))
        assertEquals("agents/warren/pi", Routes.agent("warren", "pi"))
        assertEquals("runs/run_001", Routes.run("run_001"))
        assertEquals("item/task", Routes.item("task"))
    }

    @Test
    fun builtRoutesResolveToTheirPatterns() {
        // Builders must produce paths the NavHost patterns actually match.
        assertTrue(Routes.agent("warren", "pi").matches(Regex(Routes.AGENT_DETAIL.replace("{backend}", "[^/]+").replace("{agentName}", "[^/]+"))))
        assertTrue(Routes.run("run_001").matches(Regex(Routes.AGENT_RUN_DETAIL.replace("{runId}", "[^/]+"))))
    }

    // ── T-018: inbox / attention deep links ─────────────────────────────

    private fun inboxItem(
        sourceType: EntityKind?,
        sourceId: String?,
        id: String = "alert_1",
    ) = InboxItem(
        id = id,
        sourceType = sourceType,
        sourceId = sourceId,
        title = "Agent done: fix CI",
        summary = "succeeded",
        timestamp = Instant.parse("2026-08-24T09:00:00Z"),
    )

    @Test
    fun agentRunAlertDeepLinksToRunScreen() {
        assertEquals(
            "runs/run_7",
            Routes.fromInboxItem(inboxItem(EntityKind.AGENT_RUN, "run_7")),
        )
        assertEquals(
            "runs/ses_5",
            Routes.fromInboxItem(inboxItem(EntityKind.AGENT_RUN, "ses_5", id = "alert:ses_5:1")),
        )
    }

    @Test
    fun nonAgentItemsFallBackToGenericDetail() {
        assertEquals(
            "item/alert_1",
            Routes.fromInboxItem(inboxItem(EntityKind.TASK, "task_5")),
        )
        assertEquals(
            "item/alert_1",
            Routes.fromInboxItem(inboxItem(null, null)),
        )
    }

    @Test
    fun agentRunWithoutSourceFallsBackToGenericDetail() {
        assertEquals(
            "item/alert_1",
            Routes.fromInboxItem(inboxItem(EntityKind.AGENT_RUN, null)),
        )
    }

    @Test
    fun organizeChildrenAreScopedUnderOrganize() {
        assertTrue(Routes.PROJECTS.startsWith("organize/"))
        assertTrue(Routes.AREAS.startsWith("organize/"))
        assertTrue(Routes.TASKS.startsWith("organize/"))
        assertTrue(Routes.NOTES.startsWith("organize/"))
    }
}
