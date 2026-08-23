package dev.magnor.kompakt.domain

/**
 * Capability-driven surface gating (protocol §9): a feature the server marks
 * false must not be shown or enabled. Pure logic — the shell derives one
 * [Surfaces] value per capability snapshot and passes it down; screens never
 * re-read capabilities themselves.
 *
 * Fake/demo mode ships [DEMO_CAPABILITIES] (everything on) so the demo
 * build presents the full information architecture.
 */
object SurfaceGating {

    // Server feature-flag names (docs/protocol-and-sync.md §8; coordinator FEATURES).
    const val FEATURE_TODAY = "today"
    const val FEATURE_CHAT = "chat"
    const val FEATURE_AGENTS = "agents"
    const val FEATURE_NOTES = "notes"
    const val FEATURE_INBOX = "inbox"
    const val FEATURE_CAPTURE = "offline_capture"

    /** Which user-visible surfaces exist for a given capability snapshot. */
    data class Surfaces(
        val chatTab: Boolean,
        val agentsTab: Boolean,
        val notesEntry: Boolean,
        val captureFab: Boolean,
        val inboxEntry: Boolean,
        val agentsSectionOnToday: Boolean,
        val recentNoteOnToday: Boolean,
    )

    fun evaluate(caps: CapabilitySet): Surfaces = Surfaces(
        chatTab = caps.supports(FEATURE_CHAT),
        agentsTab = caps.supports(FEATURE_AGENTS),
        notesEntry = caps.supports(FEATURE_NOTES),
        captureFab = caps.supports(FEATURE_CAPTURE),
        inboxEntry = caps.supports(FEATURE_INBOX),
        agentsSectionOnToday = caps.supports(FEATURE_AGENTS),
        recentNoteOnToday = caps.supports(FEATURE_NOTES),
    )

    /** Demo set for Fake mode — every surface visible. */
    val DEMO_CAPABILITIES: CapabilitySet = CapabilitySet(
        serverProtocol = 1,
        minimumClientProtocol = 1,
        features = mapOf(
            FEATURE_TODAY to true,
            FEATURE_CHAT to true,
            FEATURE_AGENTS to true,
            "agent_runs" to true,
            "projects" to true,
            "areas" to true,
            "tasks" to true,
            FEATURE_NOTES to true,
            FEATURE_INBOX to true,
            FEATURE_CAPTURE to true,
            "enrollment" to true,
        ),
    )
}
