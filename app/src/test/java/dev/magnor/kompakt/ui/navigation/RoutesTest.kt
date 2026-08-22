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
        assertEquals("agents/2", Routes.agent("2"))
        assertEquals("agents/2/runs/run-1", Routes.agentRun("2", "run-1"))
        assertEquals("item/task", Routes.item("task"))
    }

    @Test
    fun organizeChildrenAreScopedUnderOrganize() {
        assertTrue(Routes.PROJECTS.startsWith("organize/"))
        assertTrue(Routes.AREAS.startsWith("organize/"))
        assertTrue(Routes.TASKS.startsWith("organize/"))
        assertTrue(Routes.NOTES.startsWith("organize/"))
    }
}
