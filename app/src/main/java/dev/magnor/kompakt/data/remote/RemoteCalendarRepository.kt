package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.data.repository.CalendarRepository
import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.CalendarInfo
import dev.magnor.kompakt.domain.EventCreateResult
import dev.magnor.kompakt.domain.EventDraft
import dev.magnor.kompakt.domain.EventUpdate
import dev.magnor.kompakt.domain.KompaktJson
import dev.magnor.kompakt.domain.RepositoryException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * T-023: remote calendar repository using the coordinator's /v1 contract.
 *
 * Wire notes (verified against vault-coordinator V-065 source):
 * - POST /v1/events requires `request_id` in the JSON body (idempotency
 *   replay selects on it) — the X-Request-Id header alone is NOT read.
 * - PATCH /v1/events/{id} is HTTP PATCH (not PUT) and returns the updated
 *   event; we only need its success status.
 * - GET /v1/events/{id} resolves occurrence ids (`~slot`) via expansion;
 *   404 maps to null here.
 */
class RemoteCalendarRepository(private val api: HttpApi) : CalendarRepository {

    override fun observeCalendars(): Flow<List<CalendarInfo>> = flow {
        emit(api.decodeList("/v1/calendars", "calendars"))
    }

    override suspend fun fetchWindow(from: String, to: String): List<CalendarEvent> {
        val envelope: ScheduleRangeEnvelope = api.decode(
            "/v1/schedule/range",
            mapOf("from" to from, "to" to to),
        )
        return envelope.events
    }

    override suspend fun fetchEvent(id: String): CalendarEvent? = try {
        api.decode<CalendarEvent>("/v1/events/$id")
    } catch (e: RepositoryException) {
        // Only "not found" maps to null; transport/auth failures propagate.
        if ("HTTP 404" in (e.message ?: "")) null else throw e
    }

    override suspend fun createEvent(requestId: String, draft: EventDraft): EventCreateResult {
        val body = KompaktJson.encodeToString(
            EventCreateBody(
                requestId = requestId,
                calendarId = draft.calendarId,
                title = draft.title,
                startAt = draft.startAt,
                endAt = draft.endAt,
                durationMinutes = draft.durationMinutes,
                allDay = draft.allDay,
                description = draft.description,
                location = draft.location,
            ),
        )
        val response = api.post("/v1/events", body, requestId)
        return KompaktJson.decodeFromString<EventCreateResponse>(response).toDomain()
    }

    override suspend fun updateEvent(id: String, patchBody: EventUpdate): Boolean {
        if (patchBody.isEmpty) return false
        api.patch("/v1/events/$id", KompaktJson.encodeToString(patchBody))
        return true
    }

    override suspend fun deleteEvent(id: String): Boolean {
        // DELETE returns 200 + body; success = no exception
        api.delete("/v1/events/$id")
        return true
    }

    /** Wire → domain. */
    private fun EventCreateResponse.toDomain(): EventCreateResult =
        EventCreateResult(id, status)

    @Serializable
    private data class ScheduleRangeEnvelope(val events: List<CalendarEvent> = emptyList())

    /** POST body: draft fields + the required request_id (server contract). */
    @Serializable
    private data class EventCreateBody(
        @SerialName("request_id") val requestId: String,
        @SerialName("calendar_id") val calendarId: String,
        val title: String,
        @SerialName("start_at") val startAt: String,
        @SerialName("end_at") val endAt: String? = null,
        @SerialName("duration_minutes") val durationMinutes: Int? = null,
        @SerialName("all_day") val allDay: Boolean = false,
        val description: String? = null,
        val location: String? = null,
    )

    @Serializable
    private data class EventCreateResponse(
        @SerialName("id") val id: String,
        @SerialName("status") val status: String,
    )
}
