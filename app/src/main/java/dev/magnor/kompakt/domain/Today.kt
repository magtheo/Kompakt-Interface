@file:UseSerializers(InstantIso8601Serializer::class, LocalDateIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.serializers.LocalDateIso8601Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Calendar event projected by the coordinator (Radicale upstream). */
@Serializable
data class CalendarEvent(
    val id: EntityId,
    val title: String,
    @SerialName("start_at")
    val startAt: Instant,
    @SerialName("end_at")
    val endAt: Instant? = null,
    val location: String? = null,
    val description: String? = null,
    @SerialName("all_day")
    val allDay: Boolean = false,
    // Optional; server includes it on fan-out events via D027 (registry symbols).
    // Clients MAY ignore unknown keys — we opt-in for calendar UI only.
    @SerialName("symbol")
    val symbol: String = "",
    // T-023: owning registry id — present on /v1/events + /v1/schedule/range
    // fan-out; drives write gating (CalendarInfo.writable).
    @SerialName("calendar_id")
    val calendarId: EntityId? = null,
)

/**
 * The Today projection is a *view*, not a source of truth: it combines
 * calendar events, today's tasks, attention items, agent activity, and the
 * most recent note without owning any of them.
 */
@Serializable
data class TodayProjection(
    val date: LocalDate,
    val events: List<CalendarEvent> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val attention: List<InboxItem> = emptyList(),
    @SerialName("agent_activity")
    val agentActivity: List<AgentRun> = emptyList(),
    @SerialName("recent_note")
    val recentNote: Note? = null,
)
