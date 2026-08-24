package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.InboxItem
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-019 transport: the SSE parser state machine, wire decode, and the
 * self-healing loop against a real (mock) HTTP server.
 */
class AlertTransportTest {

    // ─── SseParser ─────────────────────────────────────────────────────

    @Test
    fun `parser assembles event id and data fields`() {
        val p = SseParser()
        assertNull(p.line("event: alert"))
        assertNull(p.line("id: alert:sA:1"))
        assertNull(p.line("data: {\"id\": \"alert:sA:1\"}"))
        val block = p.line("")
        assertNotNull(block)
        assertEquals("alert", block!!.event)
        assertEquals("alert:sA:1", block.id)
        assertEquals("{\"id\": \"alert:sA:1\"}", block.data)
    }

    @Test
    fun `parser skips heartbeat comments`() {
        val p = SseParser()
        assertNull(p.line(": hb"))
        assertNull(p.line(": hb"))
        assertNull(p.line(""))
        // Two heartbeats produced no block — nothing to assert beyond
        // no-throw; a following real block still parses.
        assertNull(p.line("event: alert"))
        assertNull(p.line("data: {}"))
        assertEquals("alert", p.line("")!!.event)
    }

    @Test
    fun `parser strips one optional space after the colon`() {
        val p = SseParser()
        assertNull(p.line("data: x"))
        assertEquals("x", p.line("")!!.data)
        val q = SseParser()
        assertNull(q.line("data:  x"))
        assertEquals(" x", q.line("")!!.data) // only the first space is stripped
    }

    @Test
    fun `parser joins multi-line data`() {
        val p = SseParser()
        assertNull(p.line("data: line1"))
        assertNull(p.line("data: line2"))
        assertEquals("line1\nline2", p.line("")!!.data)
    }

    @Test
    fun `finish flushes a truncated final block`() {
        val p = SseParser()
        assertNull(p.line("event: alert"))
        assertNull(p.line("data: tail"))
        val block = p.finish()
        assertNotNull(block)
        assertEquals("tail", block!!.data)
    }

    @Test
    fun `blocks without data dispatch as null`() {
        val p = SseParser()
        assertNull(p.line("event: alert"))
        assertNull(p.line("")) // no data → no block
        assertNull(p.finish())
    }

    // ─── Transport over a mock server ──────────────────────────────────

    private fun alertJson(id: String, title: String = "build replied: ok") =
        "{\"id\": \"$id\", \"source_type\": \"agent_run\", \"source_id\": \"${id.removePrefix("alert:").substringBefore(':')}\", " +
            "\"title\": \"$title\", \"summary\": \"succeeded\", \"timestamp\": \"2026-08-24T10:00:00Z\", " +
            "\"priority\": \"normal\", \"actions\": [], \"revision\": 1, \"updated_at\": \"2026-08-24T10:00:00Z\"}"

    @Test
    fun `transport decodes replayed and live events and attaches bearer`() = runBlocking {
        val server = MockWebServer()
        val body = "event: alert\nid: alert:sA:1\ndata: ${alertJson("alert:sA:1")}\n\n" +
            "event: alert\nid: alert:sB:1\ndata: ${alertJson("alert:sB:1")}\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
                // keep stream open after the body
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN)
        )
        server.start()

        val transport = SseAlertTransport(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "tok-123" },
            client = OkHttpClient(), // default timeouts fine for local mock
        )

        val items = mutableListOf<InboxItem>()
        try {
            withTimeout(10_000) {
                transport.alerts().collect { items.add(it); if (items.size == 2) throw Done() }
            }
        } catch (_: Done) {
        }
        assertEquals(listOf("alert:sA:1", "alert:sB:1"), items.map { it.id })
        assertEquals("build replied: ok", items[0].title)

        val recorded = server.takeRequest()
        assertEquals("/v1/alerts/stream", recorded.path)
        assertEquals("Bearer tok-123", recorded.getHeader("Authorization"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        server.shutdown()
    }

    @Test
    fun `transport reconnects after the server closes the stream`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: alert\nid: alert:r1:1\ndata: ${alertJson("alert:r1:1")}\n\n")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
        )
        // second connect: replay again (read-state is the dedupe — the
        // SEEN set lives above the transport)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: alert\nid: alert:r2:1\ndata: ${alertJson("alert:r2:1")}\n\n")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN)
        )
        server.start()

        val transport = SseAlertTransport(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { null },
            client = OkHttpClient(),
        )

        val items = mutableListOf<InboxItem>()
        try {
            withTimeout(15_000) {
                transport.alerts().collect { items.add(it); if (items.size == 2) throw Done() }
            }
        } catch (_: Done) {
        }
        assertEquals(listOf("alert:r1:1", "alert:r2:1"), items.map { it.id })
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `malformed alert block is skipped without killing the stream`() = runBlocking {
        val server = MockWebServer()
        val body = "event: alert\nid: alert:bad:1\ndata: {not json}\n\n" +
            "event: alert\nid: alert:good:1\ndata: ${alertJson("alert:good:1")}\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN)
        )
        server.start()

        val transport = SseAlertTransport(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { null },
            client = OkHttpClient(),
        )
        val items = mutableListOf<InboxItem>()
        try {
            withTimeout(10_000) {
                transport.alerts().collect { items.add(it); if (items.size == 1) throw Done() }
            }
        } catch (_: Done) {
        }
        assertEquals(listOf("alert:good:1"), items.map { it.id })
        server.shutdown()
    }

    private class Done : Exception()
}
