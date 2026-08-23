package dev.magnor.kompakt.ui.navigation

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

    @Test
    fun organizeChildrenAreScopedUnderOrganize() {
        assertTrue(Routes.PROJECTS.startsWith("organize/"))
        assertTrue(Routes.AREAS.startsWith("organize/"))
        assertTrue(Routes.TASKS.startsWith("organize/"))
        assertTrue(Routes.NOTES.startsWith("organize/"))
    }
}
