package dev.magnor.kompakt.data

import dev.magnor.kompakt.domain.CaptureProposal
import dev.magnor.kompakt.domain.CaptureType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression (T-007): cold start in remote mode used to crash in
 * AppContainer's init — activateRemote launches flushPendingCaptures on
 * Dispatchers.Default, which read the pendingCaptures store BEFORE its
 * property initializer ran (Kotlin initializes in declaration order; the
 * store used to be declared after the init block). Static Remote mode
 * exercises the same init → activateRemote → flush path that the
 * enrollment restore takes on every enrolled cold start.
 */
class AppContainerColdStartTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `cold start with remote mode flushes queued capture`() {
        val commits = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath ?: ""
                return when {
                    path == "/v1/capture/commit" && request.method == "POST" -> {
                        commits.incrementAndGet()
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """{"replayed":false,"kind":"note_created","note":""" +
                                    """{"id":"note_cold_1","title":"parked","revision":1,""" +
                                    """"updated_at":"2026-08-23T07:00:00Z"}}""",
                            )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        // A capture parked on disk before construction — exactly what the
        // offline queue holds when an enrolled app cold-starts.
        val dir = tmp.newFolder("queue")
        PendingCaptureStore(dir).enqueue(
            CaptureProposal(proposedType = CaptureType.NOTE, title = "parked"),
            requestId = "req-cold-start-1",
        )

        val container = AppContainer(
            mode = ServerMode.Remote(
                baseUrl = server.url("/").toString().trimEnd('/'),
                token = "test-token",
            ),
            captureQueueDir = dir,
        )

        // The flush races construction by design; bounded wait for delivery.
        val deadline = System.currentTimeMillis() + 5_000
        while (PendingCaptureStore(dir).all().isNotEmpty() &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50)
        }

        assertTrue(
            "queued capture never flushed — init-order regression " +
                "(flushPendingCaptures saw a null PendingCaptureStore)",
            PendingCaptureStore(dir).all().isEmpty(),
        )
        assertEquals(
            "commit never reached the server",
            1,
            commits.get(),
        )
        server.shutdown()
    }
}
