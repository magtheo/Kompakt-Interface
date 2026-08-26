package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.EventDraft
import dev.magnor.kompakt.domain.EventUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-023 / V-065 wire contract for the calendar endpoints against a
 * MockWebServer speaking the coordinator's shapes:
 * - GET /v1/calendars → {calendars:[{id, display_name, symbol, writable?}]}
 * - GET /v1/schedule/range?from&to → {events:[CalendarEvent-wire]}
 * - POST /v1/events with request_id in the body → {id, status}
 * - PATCH /v1/events/{id} (no all_day) / DELETE returns 200 + body
 * - GET /v1/events/{id} resolves occurrences (`~slot`); 404 → null
 */
class CalendarRemoteContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: RemoteCalendarRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = RemoteCalendarRepository(HttpApi(baseUrl = server.url("/").toString(), token = "test-token"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueJson(body: String, status: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    @Test
    fun `calendars decode display_name and default read-only`() = runTest {
        enqueueJson(
            """
            {"calendars":[
              {"id":"personal","display_name":"Personal","symbol":"●","writable":true},
              {"id":"sa","display_name":"SA registry","symbol":"○"}
            ]}
            """.trimIndent(),
        )
        val calendars = repo.observeCalendars()

        val list = calendars.first()
        assertEquals(2, list.size)
        assertEquals("Personal", list[0].displayName)
        assertTrue(list[0].writable)
        assertEquals(false, list[1].writable) // omitted on the wire → read-only
    }

    @Test
    fun `window fetch queries range and decodes events`() = runTest {
        enqueueJson(
            """
            {"events":[
              {"id":"personal:abc","title":"Dentist","start_at":"2026-08-27T08:00:00Z",
               "end_at":"2026-08-27T08:30:00Z","all_day":false,"calendar_id":"personal"},
              {"id":"sa:def~20260827T100000","title":"SA shift","start_at":"2026-08-27T10:00:00Z","all_day":true}
            ]}
            """.trimIndent(),
        )
        val events = repo.fetchWindow("2026-08-01", "2026-09-11")

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/schedule/range?from=2026-08-01&to=2026-09-11", req.path)

        assertEquals(2, events.size)
        assertEquals(Instant.parse("2026-08-27T08:00:00Z"), events[0].startAt)
        assertEquals(Instant.parse("2026-08-27T08:30:00Z"), events[0].endAt)
        assertEquals("personal", events[0].calendarId)
        assertTrue(events[1].allDay)
        assertNull(events[1].endAt)
        assertTrue(events[1].id.contains("~"))
    }

    @Test
    fun `create posts body with request_id and decodes id status`() = runTest {
        enqueueJson("""{"id":"personal:xyz","status":"created"}""")
        val result = repo.createEvent(
            "req-1",
            EventDraft(
                calendarId = "personal",
                title = "Dentist",
                startAt = "2026-08-27T08:00:00Z",
                endAt = "2026-08-27T08:30:00Z",
            ),
        )

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/events", req.path)
        val body = req.body.readUtf8().filterNot { it.isWhitespace() }
        assertTrue(body.contains("\"request_id\":\"req-1\""))
        assertTrue(body.contains("\"calendar_id\":\"personal\""))
        assertTrue(body.contains("\"title\":\"Dentist\""))
        assertTrue(body.contains("\"start_at\":\"2026-08-27T08:00:00Z\""))
        assertTrue(body.contains("\"end_at\":\"2026-08-27T08:30:00Z\""))

        assertEquals("personal:xyz", result.id)
        assertEquals("created", result.status)
    }

    @Test
    fun `create replay decodes already_exists`() = runTest {
        enqueueJson("""{"id":"personal:xyz","status":"already_exists"}""")
        val result = repo.createEvent("req-1", EventDraft("personal", "Dentist", "2026-08-27T08:00:00Z"))

        assertTrue(result.replayed)
    }

    @Test
    fun `update patches the event id`() = runTest {
        enqueueJson("""{"ok":true}""")
        val ok = repo.updateEvent(
            "personal:xyz",
            EventUpdate(title = "Dentist moved", startAt = "2026-08-27T09:00:00Z"),
        )

        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/v1/events/personal:xyz", req.path)
        // Field-level checks — formatting-agnostic (values may contain spaces).
        val body = req.body.readUtf8()
        assertTrue(body.contains("Dentist moved"))
        assertTrue(body.contains("start_at"))
        assertTrue(body.contains("2026-08-27T09:00:00Z"))
        assertTrue(ok)
    }

    @Test
    fun `delete returns true on 200 body`() = runTest {
        enqueueJson("""{"status":"deleted"}""")
        val ok = repo.deleteEvent("personal:xyz")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/events/personal:xyz", req.path)
        assertTrue(ok)
    }

    @Test
    fun `fetchEvent decodes a single event`() = runTest {
        enqueueJson(
            """
            {"id":"personal:xyz","title":"Dentist","start_at":"2026-08-27T08:00:00Z",
             "end_at":"2026-08-27T08:30:00Z","calendar_id":"personal"}
            """.trimIndent(),
        )
        val event = repo.fetchEvent("personal:xyz")

        assertEquals("/v1/events/personal:xyz", server.takeRequest().path)
        assertNotNull(event)
        assertEquals("Dentist", event?.title)
    }

    @Test
    fun `fetchEvent maps 404 to null`() = runTest {
        enqueueJson("""{"detail":"event not found"}""", status = 404)
        val event = repo.fetchEvent("personal:gone")

        assertNull(event)
    }
}
