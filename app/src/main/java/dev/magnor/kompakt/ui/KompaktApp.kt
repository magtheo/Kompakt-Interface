package dev.magnor.kompakt.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD
import com.mudita.mmd.components.nav_bar.NavigationBarMMD
import com.mudita.mmd.components.nav_bar.NavigationBarItemMMD
import com.mudita.mmd.components.text.TextMMD
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

/**
 * App shell: bottom navigation (Today | Chat | Agents | More) over a NavHost.
 * E-Ink rule: navigation transitions disabled (docs/development-plan.md Phase 1).
 */
@Composable
fun KompaktApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = currentRoute in TopLevelDestination.routes

    Scaffold(
        bottomBar = {
            if (isTopLevel) {
                NavigationBarMMD {
                    TopLevelDestination.entries.forEach { dest ->
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
            // Universal capture action — persistent on the main surfaces.
            if (isTopLevel) {
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
                TodayScreen(onOpenInbox = { navController.navigate(Routes.INBOX) })
            }
            composable(Routes.CHAT_LIST) {
                ChatListScreen(
                    onOpenThread = { navController.navigate(Routes.chatThread(it)) },
                )
            }
            composable(Routes.CHAT_THREAD) {
                ChatThreadScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.AGENTS_LIST) {
                AgentsListScreen(
                    onOpenAgent = { navController.navigate(Routes.agent(it)) },
                    onOpenInbox = { navController.navigate(Routes.INBOX) },
                )
            }
            composable(Routes.AGENT_DETAIL) {
                AgentDetailScreen(
                    onOpenRun = { runId ->
                        navController.navigate(Routes.agentRun("1", runId))
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.AGENT_RUN_DETAIL) {
                AgentRunDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.MORE) {
                MoreScreen(
                    onOpenOrganize = { navController.navigate(Routes.ORGANIZE) },
                    onOpenInbox = { navController.navigate(Routes.INBOX) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.ORGANIZE) {
                OrganizeScreen(
                    onOpenProjects = { navController.navigate(Routes.PROJECTS) },
                    onOpenAreas = { navController.navigate(Routes.AREAS) },
                    onOpenTasks = { navController.navigate(Routes.TASKS) },
                    onOpenNotes = { navController.navigate(Routes.NOTES) },
                )
            }
            composable(Routes.PROJECTS) {
                ProjectsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.AREAS) {
                AreasScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.TASKS) {
                TasksScreen(
                    onOpenItem = { navController.navigate(Routes.item(it)) },
                    onBack = { navController.popBackStack() },
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
            composable(Routes.ITEM_DETAIL) {
                ItemDetailScreen(onBack = { navController.popBackStack() })
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
