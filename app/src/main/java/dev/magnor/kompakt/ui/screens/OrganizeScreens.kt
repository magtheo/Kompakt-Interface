package dev.magnor.kompakt.ui.screens

import androidx.compose.runtime.Composable
import dev.magnor.kompakt.data.MockData

/** More — overflow menu for the fourth tab. */
@Composable
fun MoreScreen(
    onOpenOrganize: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AppScreen(title = "More") {
        ListRow(title = "Organize", subtitle = "Projects · Areas · Tasks · Notes", onClick = onOpenOrganize)
        ListRow(title = "Inbox", subtitle = "Things from any subsystem that need you", onClick = onOpenInbox)
        ListRow(title = "Settings", subtitle = "Enrollment · Sync · About", onClick = onOpenSettings)
    }
}

/** Organize — browse axes mirror the vault entity model (D004). */
@Composable
fun OrganizeScreen(
    onOpenProjects: () -> Unit,
    onOpenAreas: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenNotes: () -> Unit,
) {
    AppScreen(title = "Organize") {
        ListRow(title = "Projects", subtitle = "Evershift, KodeVerket, …", onClick = onOpenProjects)
        ListRow(title = "Areas", subtitle = "Health, Career, …", onClick = onOpenAreas)
        ListRow(title = "Tasks", subtitle = "Aggregated across adapters", onClick = onOpenTasks)
        ListRow(title = "Notes", subtitle = "Vault markdown", onClick = onOpenNotes)
    }
}

@Composable
fun ProjectsScreen(onBack: () -> Unit) {
    AppScreen(title = "Projects", onBack = onBack) {
        MockData.projects.forEach { (name, meta) ->
            ListRow(title = name, subtitle = meta)
        }
    }
}

@Composable
fun AreasScreen(onBack: () -> Unit) {
    AppScreen(title = "Areas", onBack = onBack) {
        MockData.areas.forEach { (name, meta) ->
            ListRow(title = name, subtitle = meta)
        }
    }
}

@Composable
fun TasksScreen(onOpenItem: (String) -> Unit, onBack: () -> Unit) {
    AppScreen(title = "Tasks", onBack = onBack) {
        SectionLabel("Today")
        MockData.tasksToday.forEach { task ->
            ListRow(title = task, trailing = "○", onClick = { onOpenItem("task") })
        }
        SectionLabel("Upcoming")
        MockData.tasksUpcoming.forEach { task ->
            ListRow(title = task, trailing = "○", onClick = { onOpenItem("task") })
        }
    }
}

@Composable
fun NotesScreen(onOpenItem: (String) -> Unit, onBack: () -> Unit) {
    AppScreen(title = "Notes", onBack = onBack) {
        MockData.notes.forEach { (title, time) ->
            ListRow(title = title, trailing = time, onClick = { onOpenItem("note") })
        }
    }
}
