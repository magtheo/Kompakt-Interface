package dev.magnor.kompakt.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.graphics.vector.ImageVector

/** Bottom navigation destinations — exactly four: Today | Chat | Agents | More. */
enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    TODAY(Routes.TODAY, "Today", Icons.Filled.Home),
    CHAT(Routes.CHAT_LIST, "Chat", Icons.Filled.Email),
    AGENTS(Routes.AGENTS_LIST, "Agents", Icons.Filled.Build),
    MORE(Routes.MORE, "More", Icons.Filled.Menu);

    companion object {
        val routes: Set<String> = entries.map { it.route }.toSet()
    }
}
