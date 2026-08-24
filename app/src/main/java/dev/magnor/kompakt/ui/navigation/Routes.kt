package dev.magnor.kompakt.ui.navigation

import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.InboxItem

/**
 * Route table (Phase 1 — static placeholders; T-006 adds task filters).
 *
 * Every screen in the information architecture has exactly one route.
 * Arg screens use nav-placeholder ids for now; Phase 3+ replaces them
 * with real object ids from the server.
 */
object Routes {
    const val TODAY = "today"
    const val CHAT_LIST = "chat"
    const val CHAT_THREAD = "chat/{threadId}"
    const val AGENTS_LIST = "agents"
    const val AGENT_DETAIL = "agents/{backend}/{agentName}"
    const val AGENT_RUN_DETAIL = "runs/{runId}"
    const val MORE = "more"
    const val ORGANIZE = "organize"
    const val PROJECTS = "organize/projects"
    const val AREAS = "organize/areas"
    const val TASKS = "organize/tasks"

    /** Task list with optional project/area filter (dev plan §7). */
    const val TASKS_PATTERN = "organize/tasks?projectId={projectId}&areaId={areaId}"
    const val NOTES = "organize/notes"
    const val INBOX = "inbox"
    const val ITEM_DETAIL = "item/{itemId}"
    const val CAPTURE = "capture"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"

    /** All screen patterns — exactly one entry per Phase 1 screen (17). */
    val all: List<String> = listOf(
        TODAY, CHAT_LIST, CHAT_THREAD, AGENTS_LIST, AGENT_DETAIL,
        AGENT_RUN_DETAIL, MORE, ORGANIZE, PROJECTS, AREAS, TASKS_PATTERN, NOTES,
        INBOX, ITEM_DETAIL, CAPTURE, SETTINGS, DIAGNOSTICS,
    )

    fun chatThread(id: String) = "chat/$id"
    fun agent(backend: String, name: String) = "agents/$backend/$name"
    fun run(id: String) = "runs/$id"
    fun item(id: String) = "item/$id"

    /**
     * Deep-link route for an inbox / Today attention item (T-018).
     * Agent-run alerts jump straight to the run screen; everything else
     * falls back to the generic resolver.
     */
    fun fromInboxItem(item: InboxItem): String =
        if (item.sourceType == EntityKind.AGENT_RUN && !item.sourceId.isNullOrBlank()) {
            run(item.sourceId)
        } else {
            item(item.id)
        }

    /** Build a tasks route with at most one filter (project wins if both given). */
    fun tasks(projectId: String? = null, areaId: String? = null): String = when {
        projectId != null -> "$TASKS?projectId=$projectId"
        areaId != null -> "$TASKS?areaId=$areaId"
        else -> TASKS
    }
}
