@file:UseSerializers(InstantIso8601Serializer::class, LocalDateIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.datetime.serializers.LocalDateIso8601Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Calendar event projected by the coordinator (Radicale upstream). */
@Serializable
data class CalendarEvent(
    val id: EntityId,
    val title: String,
    val startAt: Instant,
    val endAt: Instant? = null,
    val location: String? = null,
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
    val agentActivity: List<AgentRun> = emptyList(),
    val recentNote: Note? = null,
)
