package dev.magnor.kompakt.ui.navigation

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
    const val AGENT_DETAIL = "agents/{agentId}"
    const val AGENT_RUN_DETAIL = "agents/{agentId}/runs/{runId}"
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
    fun agent(id: String) = "agents/$id"
    fun agentRun(agentId: String, runId: String) = "agents/$agentId/runs/$runId"
    fun item(id: String) = "item/$id"

    /** Build a tasks route with at most one filter (project wins if both given). */
    fun tasks(projectId: String? = null, areaId: String? = null): String = when {
        projectId != null -> "$TASKS?projectId=$projectId"
        areaId != null -> "$TASKS?areaId=$areaId"
        else -> TASKS
    }
}
