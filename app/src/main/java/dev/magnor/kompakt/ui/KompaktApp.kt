package dev.magnor.kompakt.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD
import com.mudita.mmd.components.nav_bar.NavigationBarMMD
import com.mudita.mmd.components.nav_bar.NavigationBarItemMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.data.AppContainer
import dev.magnor.kompakt.domain.SurfaceGating
import dev.magnor.kompakt.domain.TaskFilter
import dev.magnor.kompakt.ui.navigation.Routes
import dev.magnor.kompakt.ui.navigation.TopLevelDestination
import dev.magnor.kompakt.ui.screens.AgentDetailScreen
import dev.magnor.kompakt.ui.screens.AgentRunDetailScreen
import dev.magnor.kompakt.ui.screens.AgentsListScreen
import dev.magnor.kompakt.ui.screens.AreasScreen
import dev.magnor.kompakt.ui.screens.CaptureScreen
import dev.magnor.kompakt.ui.screens.ChatListScreen
import dev.magnor.kompakt.ui.screens.ChatThreadScreen
import dev.magnor.kompakt.ui.screens.DiagnosticsScreen
import dev.magnor.kompakt.ui.screens.InboxScreen
import dev.magnor.kompakt.ui.screens.ItemDetailScreen
import dev.magnor.kompakt.ui.screens.MoreScreen
import dev.magnor.kompakt.ui.screens.NotesScreen
import dev.magnor.kompakt.ui.screens.OrganizeScreen
import dev.magnor.kompakt.ui.screens.ProjectsScreen
import dev.magnor.kompakt.ui.screens.SettingsScreen
import dev.magnor.kompakt.ui.screens.TasksScreen
import dev.magnor.kompakt.ui.screens.TodayScreen
import dev.magnor.kompakt.ui.viewmodels.TasksViewModel

/**
 * App shell: bottom navigation (Today | Chat | Agents | More) over a NavHost.
 * E-Ink rule: navigation transitions disabled (docs/development-plan.md Phase 1).
 *
 * T-006: the shell is the single capability-gating point (protocol §9) —
 * it derives one [SurfaceGating.Surfaces] from the live capability cache
 * and passes visibility down; screens never re-read capabilities.
 */
@Composable
fun KompaktApp(container: AppContainer) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        KompaktNavHost()
    }
}

@Composable
private fun KompaktNavHost() {
    val navController = rememberNavController()
    val appContainer = LocalAppContainer.current
    val caps by appContainer.capabilityStore.capabilities.collectAsState()
    val surfaces = remember(caps) { SurfaceGating.evaluate(caps) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = currentRoute in TopLevelDestination.routes

    val visibleTabs = TopLevelDestination.entries.filter { dest ->
        when (dest) {
            TopLevelDestination.CHAT -> surfaces.chatTab
            TopLevelDestination.AGENTS -> surfaces.agentsTab
            else -> true
        }
    }

    Scaffold(
        bottomBar = {
            if (isTopLevel) {
                NavigationBarMMD {
                    visibleTabs.forEach { dest ->
                        NavigationBarItemMMD(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(Routes.TODAY) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = dest.icon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = { TextMMD(dest.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // Universal capture action — persistent on the main surfaces,
            // but only while the server supports capture (protocol §9).
            if (isTopLevel && surfaces.captureFab) {
                FloatingActionButtonMMD(
                    onClick = { navController.navigate(Routes.CAPTURE) },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Capture")
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TODAY,
            modifier = Modifier.padding(padding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(Routes.TODAY) {
                TodayScreen(
                    onOpenInbox = { navController.navigate(Routes.INBOX) },
                    showInboxAction = surfaces.inboxEntry,
                    showAgentsSection = surfaces.agentsSectionOnToday,
                    showRecentNote = surfaces.recentNoteOnToday,
                )
            }
            composable(Routes.CHAT_LIST) {
                ChatListScreen(
                    onOpenThread = { navController.navigate(Routes.chatThread(it)) },
                )
            }
            composable(Routes.CHAT_THREAD) { entry ->
                ChatThreadScreen(
                    threadId = entry.arguments?.getString("threadId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.AGENTS_LIST) {
                AgentsListScreen(
                    onOpenAgent = { backend, name ->
                        navController.navigate(Routes.agent(backend, name))
                    },
                    onOpenRun = { navController.navigate(Routes.run(it)) },
                    onOpenInbox = { navController.navigate(Routes.INBOX) },
                )
            }
            composable(Routes.AGENT_DETAIL) { entry ->
                AgentDetailScreen(
                    backend = entry.arguments?.getString("backend").orEmpty(),
                    agentName = entry.arguments?.getString("agentName").orEmpty(),
                    onOpenRun = { navController.navigate(Routes.run(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.AGENT_RUN_DETAIL) { entry ->
                AgentRunDetailScreen(
                    runId = entry.arguments?.getString("runId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.MORE) {
                MoreScreen(
                    onOpenOrganize = { navController.navigate(Routes.ORGANIZE) },
                    onOpenInbox = { navController.navigate(Routes.INBOX) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    showInbox = surfaces.inboxEntry,
                )
            }
            composable(Routes.ORGANIZE) {
                OrganizeScreen(
                    onOpenProjects = { navController.navigate(Routes.PROJECTS) },
                    onOpenAreas = { navController.navigate(Routes.AREAS) },
                    onOpenTasks = { navController.navigate(Routes.tasks()) },
                    onOpenNotes = { navController.navigate(Routes.NOTES) },
                    showNotes = surfaces.notesEntry,
                )
            }
            composable(Routes.PROJECTS) {
                ProjectsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenProjectTasks = { projectId ->
                        navController.navigate(Routes.tasks(projectId = projectId))
                    },
                )
            }
            composable(Routes.AREAS) {
                AreasScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAreaTasks = { areaId ->
                        navController.navigate(Routes.tasks(areaId = areaId))
                    },
                )
            }
            composable(
                route = Routes.TASKS_PATTERN,
                arguments = listOf(
                    navArgument("projectId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("areaId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val projectId = entry.arguments?.getString("projectId")
                val areaId = entry.arguments?.getString("areaId")
                TasksScreen(
                    onOpenItem = { navController.navigate(Routes.item(it)) },
                    onBack = { navController.popBackStack() },
                    viewModel = containerViewModel(key = "tasks-$projectId-$areaId") {
                        TasksViewModel(
                            taskRepository = it.taskRepository,
                            organizationRepository = it.organizationRepository,
                            now = it.now(),
                            filter = when {
                                projectId != null -> TaskFilter.ByProject(projectId)
                                areaId != null -> TaskFilter.ByArea(areaId)
                                else -> TaskFilter.All
                            },
                        )
                    },
                )
            }
            composable(Routes.NOTES) {
                NotesScreen(
                    onOpenItem = { navController.navigate(Routes.item(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.INBOX) {
                InboxScreen(
                    onOpenItem = { navController.navigate(Routes.item(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.ITEM_DETAIL) { entry ->
                ItemDetailScreen(
                    itemId = entry.arguments?.getString("itemId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.CAPTURE) {
                CaptureScreen(
                    onDone = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
