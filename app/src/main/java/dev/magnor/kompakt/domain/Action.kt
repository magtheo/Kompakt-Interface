package dev.magnor.kompakt.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Known action types (docs/technical-architecture.md — Generic Action
 * Model). The server may attach actions to any object; the client renders
 * only actions whose type it knows. Unknown types map to [ActionType.UNKNOWN]
 * and must be filtered out before rendering — the client must validate
 * that actions belong to the known set (no arbitrary remote-code UI).
 */
@Serializable(with = ActionType.Serializer::class)
enum class ActionType(val wire: String, val defaultLabel: String) {
    COMPLETE("complete", "Complete"),
    POSTPONE("postpone", "Postpone"),
    ARCHIVE("archive", "Archive"),
    RETRY("retry", "Retry"),
    APPROVE("approve", "Approve"),
    REJECT("reject", "Reject"),
    OPEN_RESULT("open_result", "Open result"),
    ASK_AGENT("ask_agent", "Ask agent"),
    SEND_TO_AGENT("send_to_agent", "Send to agent"),
    SAVE_AS_NOTE("save_as_note", "Save as note"),
    CREATE_TASK("create_task", "Create task"),
    DISCUSS_IN_CHAT("discuss_in_chat", "Discuss in chat"),
    STOP("stop", "Stop"),
    UNKNOWN("unknown", "—");

    object Serializer : SafeEnumSerializer<ActionType>(UNKNOWN, entries, ActionType::wire)
}

@Serializable(with = ActionStyle.Serializer::class)
enum class ActionStyle(val wire: String) {
    PRIMARY("primary"),
    NORMAL("normal"),
    DESTRUCTIVE("destructive"),
    UNKNOWN("unknown");

    object Serializer : SafeEnumSerializer<ActionStyle>(UNKNOWN, entries, ActionStyle::wire)
}

/**
 * A server-provided action button. Wire example:
 * `{ "id": "approve", "label": "Approve", "style": "primary" }`
 */
@Serializable
data class Action(
    @SerialName("id") val type: ActionType,
    val label: String = type.defaultLabel,
    val style: ActionStyle = ActionStyle.NORMAL,
) {
    /** True when the client recognizes this action and may render it. */
    val isKnown: Boolean get() = type != ActionType.UNKNOWN

    companion object {
        /** Drop actions the client cannot safely render (unknown types). */
        fun List<Action>.knownOnly(): List<Action> = filter { it.isKnown }
    }
}
