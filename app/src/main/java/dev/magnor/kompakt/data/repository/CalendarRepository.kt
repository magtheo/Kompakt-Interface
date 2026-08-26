package dev.magnor.kompakt.data.repository

import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.CalendarInfo
import dev.magnor.kompakt.domain.EventCreateResult
import dev.magnor.kompakt.domain.EventDraft
import dev.magnor.kompakt.domain.EventUpdate
import kotlinx.coroutines.flow.Flow

/**
 * T-023 calendar repository interface.
 * Writes use client request IDs for idempotent replay (protocol §11).
 */
interface CalendarRepository {
    /** The registry of calendars with writability flags. */
    fun observeCalendars(): Flow<List<CalendarInfo>>

    /** Events in a local-date window [from, to] inclusive. */
    suspend fun fetchWindow(from: String, to: String): List<CalendarEvent>

    /** One event by opaque id; occurrence ids resolve server-side. Null = 404. */
    suspend fun fetchEvent(id: String): CalendarEvent?

    /** Create an event; returns id + status (created|already_exists). */
    suspend fun createEvent(requestId: String, draft: EventDraft): EventCreateResult

    /** Series-level update — all-day is NOT changeable post-create v1. */
    suspend fun updateEvent(id: String, patch: EventUpdate): Boolean

    /** Delete by opaque id — returns true on success (200+body semantics). */
    suspend fun deleteEvent(id: String): Boolean
}
