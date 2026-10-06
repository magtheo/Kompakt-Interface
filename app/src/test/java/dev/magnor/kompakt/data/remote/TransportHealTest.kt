package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.OfflineException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * T-052 (D037 residual, found in the T-051 device smoke 2026-10-05): a
 * degraded emission from `degradeTransport` used to complete the flow, so
 * the collecting VM's stateIn cache held the fallback forever — after the
 * transport healed (a sibling screen's fetch marking Ok at the HttpApi
 * choke point), tri-states fell to the cached tier and rendered a false
 * "No X configured" until the next navigation. The flow must instead stay
 * alive while subscribed, refetch once the shared status shows Ok, and
 * complete on the first success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportHealTest {

    private val transport = MutableStateFlow<TransportStatus>(TransportStatus.Idle)

    /**
     * Scripted fetch outcomes, mirroring HttpApi.execute's contract: a
     * transport-class failure marks the shared status Degraded before
     * throwing; Ok arrives from OUTSIDE (a sibling request), so the test
     * flips it explicitly to simulate the heal.
     */
    private class ScriptedFetch(
        private val transport: MutableStateFlow<TransportStatus>,
        vararg outcomes: Any,
    ) {
        var calls = 0
        private val remaining = outcomes.toMutableList()

        suspend fun next(): String {
            calls++
            val outcome = remaining.removeAt(0)
            if (outcome is Exception) {
                transport.value = TransportStatus.Degraded(
                    Instant.parse("2026-01-01T00:00:00Z"),
                    outcome.message ?: "offline",
                )
                throw outcome
            }
            return outcome as String
        }
    }

    @Test
    fun `degraded emission waits for heal then refetches and completes`() = runTest {
        val script = ScriptedFetch(transport, OfflineException(RuntimeException("offline")), "real")
        val emissions = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            degradeTransport(transport, "fallback") { script.next() }.toList(emissions)
        }
        runCurrent()
        assertEquals(listOf("fallback"), emissions)
        assertEquals(1, script.calls)

        // No busy polling while degraded: virtual time moves, nothing fetches.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, script.calls)

        transport.value = TransportStatus.Ok // sibling screen's fetch landed
        runCurrent()
        assertEquals(listOf("fallback", "real"), emissions)
        assertEquals(2, script.calls)
    }

    @Test
    fun `heal retry that fails again waits for the next genuine heal`() = runTest {
        val script = ScriptedFetch(
            transport,
            OfflineException(RuntimeException("offline")),
            OfflineException(RuntimeException("still flapping")),
            "real",
        )
        val emissions = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            degradeTransport(transport, "fallback") { script.next() }.toList(emissions)
        }
        runCurrent()
        assertEquals(listOf("fallback"), emissions)

        transport.value = TransportStatus.Ok // first heal
        runCurrent()
        assertEquals(2, script.calls) // retried, failed again (marks Degraded)
        assertEquals(listOf("fallback"), emissions) // fallback NOT re-emitted

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, script.calls) // Degraded again → waits, no spin

        transport.value = TransportStatus.Ok // second heal sticks
        runCurrent()
        assertEquals(listOf("fallback", "real"), emissions)
        assertEquals(3, script.calls)
    }

    @Test
    fun `first success still completes without consulting transport`() = runTest {
        val script = ScriptedFetch(transport, "fresh")
        val value = degradeTransport(transport, "fallback") { script.next() }.first()
        assertEquals("fresh", value)
        assertEquals(1, script.calls)
        assertEquals(TransportStatus.Idle, transport.value) // never touched
    }

    /** The T-051 smoke scenario, end to end over the wire. */
    @Test
    fun `offline threads flow refetches after sibling heal - E2E`() = runBlocking {
        val chatsJson = """
            {"chats":[{"id":"chat_1","title":"General","created_at":"2026-08-23T10:00:00Z",
             "updated_at":"2026-08-23T11:30:00Z","revision":3,"project_id":null,
             "is_temporary":false,"last_message_preview":"hello"}]}
        """.trimIndent()
        val server = MockWebServer()
        val chatsCalls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.path) {
                    "/v1/chats" ->
                        if (chatsCalls.incrementAndGet() == 1) MockResponse().setResponseCode(503)
                        else MockResponse().setResponseCode(200).setBody(chatsJson)
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
        }
        server.start()
        val api = HttpApi(server.url("/").toString(), "t")
        val repo = RemoteChatRepository(api)

        val emissions = mutableListOf<List<ChatThread>>()
        val job = launch { repo.observeThreads().toList(emissions) }
        withTimeout(5_000) { while (emissions.size < 1) delay(25) }
        assertEquals(listOf<ChatThread>(), emissions[0]) // degraded fallback rendered

        api.get("/v1/ping") // sibling screen's fetch → transport Ok (the heal)
        withTimeout(5_000) { while (emissions.size < 2) delay(25) }
        assertEquals(1, emissions[1].size) // real list self-corrected
        assertEquals("General", emissions[1][0].title)

        job.cancel()
        server.shutdown()
    }
}
