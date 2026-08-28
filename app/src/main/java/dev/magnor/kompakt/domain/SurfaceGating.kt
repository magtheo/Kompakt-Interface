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

    // Device-grant capability names (protocol §13; DEFAULT_DEVICE_CAPABILITIES,
    // src/auth.py). A surface needs BOTH its feature flag (server-wide) and,
    // when the device's granted set is known (V-069), its read grant.
    const val CAP_CHAT_READ = "chat.read"
    const val CAP_AGENT_READ = "agent.read"
    const val CAP_NOTE_READ = "note.read"
    const val CAP_INBOX_READ = "inbox.read"
    const val CAP_CAPTURE_INTERPRET = "capture.interpret"

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
        chatTab = caps.supports(FEATURE_CHAT) && caps.grants(CAP_CHAT_READ),
        agentsTab = caps.supports(FEATURE_AGENTS) && caps.grants(CAP_AGENT_READ),
        notesEntry = caps.supports(FEATURE_NOTES) && caps.grants(CAP_NOTE_READ),
        captureFab = caps.supports(FEATURE_CAPTURE) && caps.grants(CAP_CAPTURE_INTERPRET),
        inboxEntry = caps.supports(FEATURE_INBOX) && caps.grants(CAP_INBOX_READ),
        agentsSectionOnToday = caps.supports(FEATURE_AGENTS) && caps.grants(CAP_AGENT_READ),
        recentNoteOnToday = caps.supports(FEATURE_NOTES) && caps.grants(CAP_NOTE_READ),
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
