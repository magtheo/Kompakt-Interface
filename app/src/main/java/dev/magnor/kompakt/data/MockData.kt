package dev.magnor.kompakt.data

/**
 * Phase 1 mock data — plain strings only. Phase 2 replaces this with the
 * domain model (ChatThread, Message, Agent, AgentRun, Task, Note, Project,
 * Area, InboxItem — see docs/development-plan.md Phase 2) and Phase 3
 * feeds repositories from the server contract.
 */
object MockData {

    val todayEvents = listOf("09:00" to "Philosophy", "12:30" to "Dentist")

    val todayTasks = listOf(
        "Review PR" to false,
        "Buy groceries" to false,
        "Send application" to false,
    )

    val attention = listOf("Agent asks for approval — Kompakt audit", "1 overdue task")

    const val recentNote = "Look into agent UI idea"

    val chatThreads = listOf(
        Triple("General", "How does X compare to Y?", "12:04"),
        Triple("KodeVerket ideas", "Pricing model sketch…", "Yesterday"),
        Triple("Linux questions", "systemd timer vs cron", "Yesterday"),
        Triple("Health", "Supplement stack check", "Mon"),
        Triple("Travel research", "Japan in autumn?", "Sun"),
    )

    val chatMessages = listOf(
        "You" to "How does X compare to Y?",
        "Assistant" to "X is simpler but Y scales better for your case because…",
        "You" to "And what if the load doubles?",
        "Assistant" to "Then Y's queue depth stays flat while X degrades linearly.",
    )

    /** name, status marker, status text, last activity */
    val agents = listOf(
        listOf("Kompakt audit", "●", "Running", "12 min"),
        listOf("Job Search", "○", "Idle", "2h ago"),
        listOf("Fedora Research", "✓", "Finished", "Result ready"),
        listOf("PR Reviewer", "!", "Waiting for input", "PR #55"),
    )

    val agentDetail = listOf(
        "Status" to "Running",
        "Current objective" to "Check cross-spec drift",
        "Started" to "22:41",
        "Latest activity" to "Comparing semantic model…",
        "Output" to "3 findings so far",
    )

    val agentRuns = listOf(
        "run-1" to "Spec drift check — Running",
        "run-2" to "PR audit — Finished",
        "run-3" to "Course research — Needs input",
    )

    val projects = listOf(
        "Evershift" to "12 tasks · 3 notes",
        "KodeVerket" to "8 tasks · 1 note",
        "Kompakt-Interface" to "6 tasks · 7 specs",
        "dev-server" to "4 tasks · 2 notes",
    )

    val areas = listOf(
        "Health" to "training, supplements",
        "Career" to "applications, KodeVerket",
        "Personal" to "philosophy, travel",
        "Economy" to "budget, subscriptions",
    )

    val tasksToday = listOf("Review PR", "Dentist", "Buy food")
    val tasksUpcoming = listOf("Submit course application", "Renew passport")

    val notes = listOf(
        "Agent UI idea" to "2h ago",
        "Philosophy thought" to "yesterday",
        "Kodeverket pricing" to "3 days ago",
    )

    val inboxItems = listOf(
        "Agent needs approval" to "Kompakt audit · 22:38",
        "Task overdue" to "Dentist",
        "Agent finished" to "Summer course research",
        "System warning" to "Server backup failed",
    )
}
