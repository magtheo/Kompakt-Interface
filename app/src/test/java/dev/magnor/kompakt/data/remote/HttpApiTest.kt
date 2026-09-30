package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.ServerUnavailableException
import kotlinx.datetime.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-051: [HttpApi.execute] feeds an app-scoped [TransportStatus] —
 * Ok on any successful call, Degraded on transport-class failures
 * (offline / 5xx) — WITHOUT touching the request's own result
 * (Sept-2 invariant: status plumbing must never break request handling).
 */
class HttpApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HttpApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HttpApi(baseUrl = server.url("/").toString(), token = "test-token")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Start-then-shutdown listener: connection refused → OfflineException. */
    private fun deadApi(): HttpApi {
        val deadServer = MockWebServer()
        deadServer.start()
        val url = deadServer.url("/").toString()
        deadServer.shutdown()
        return HttpApi(baseUrl = url, token = "t")
    }

    @Test
    fun `transport starts Idle before any request`() {
        assertEquals(TransportStatus.Idle, api.transport.value)
    }

    @Test
    fun `successful request marks transport Ok and returns body untouched`() = runTest {
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        val body = api.get("/v1/ping")
        assertEquals("{\"ok\":true}", body)
        assertEquals(TransportStatus.Ok, api.transport.value)
    }

    @Test
    fun `offline failure marks transport Degraded and still throws OfflineException`() = runTest {
        val dead = deadApi()
        val thrown = runCatching { dead.get("/v1/ping") }.exceptionOrNull()
        assertTrue("request result unaffected by status plumbing", thrown is OfflineException)
        val status = dead.transport.value
        assertTrue(status is TransportStatus.Degraded)
        status as TransportStatus.Degraded
        assertTrue("cause is a short human line", status.cause.isNotBlank())
        assertTrue(
            "timestamp is plausible",
            status.at > Instant.parse("2026-01-01T00:00:00Z"),
        )
    }

    @Test
    fun `5xx marks transport Degraded and still throws ServerUnavailableException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val thrown = runCatching { api.get("/v1/ping") }.exceptionOrNull()
        assertTrue(thrown is ServerUnavailableException)
        val status = api.transport.value
        assertTrue(status is TransportStatus.Degraded)
        assertTrue(
            "cause names the server failure",
            (status as TransportStatus.Degraded).cause.contains("503"),
        )
    }

    @Test
    fun `recovery - success after Degraded flips transport back to Ok`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        runCatching { api.get("/v1/ping") }
        assertTrue(api.transport.value is TransportStatus.Degraded)
        server.enqueue(MockResponse().setBody("{}"))
        api.get("/v1/ping")
        assertEquals(TransportStatus.Ok, api.transport.value)
    }
}
