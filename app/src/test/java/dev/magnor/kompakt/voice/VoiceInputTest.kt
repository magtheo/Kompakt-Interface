package dev.magnor.kompakt.voice

import dev.magnor.kompakt.data.remote.HttpApi
import dev.magnor.kompakt.domain.OfflineException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputTest {

    // ---- appendTranscript: replace-if-empty, append-with-space otherwise ----

    @Test
    fun appendIntoEmptyDraftReplaces() {
        assertEquals("buy milk", appendTranscript("", "buy milk"))
        assertEquals("buy milk", appendTranscript("   ", "buy milk"))
    }

    @Test
    fun appendIntoDraftJoinsWithSpace() {
        assertEquals("buy milk tomorrow", appendTranscript("buy milk", "tomorrow"))
        assertEquals("a b", appendTranscript("a ", " b "))
    }

    // ---- controller state machine ----

    private class FakeRecorder(var failStop: Boolean = false) : VoiceRecorder {
        var started: File? = null
        var cancelled = false
        var stopped = false
        override fun start(outputFile: File) {
            started = outputFile
        }

        override fun stop(): File {
            if (failStop) throw RuntimeException("no valid audio")
            stopped = true
            return requireNotNull(started)
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeTranscriber(
        var result: Transcript = Transcript("hello"),
        var error: Exception? = null,
        /** Suspend point for mid-flight assertions; null = run to completion. */
        var gate: CompletableDeferred<Unit>? = null,
    ) : VoiceTranscriber {
        var received: File? = null
        var receivedLanguage: String? = null
        override suspend fun transcribe(audio: File, language: String?): Transcript {
            gate?.await()
            received = audio
            receivedLanguage = language
            error?.let { throw it }
            return result
        }
    }

    /** Unconfined everything: taps drive the machine synchronously, no
     *  advanceUntilIdle needed (repo convention, see ChatViewModelsTest). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun controller(
        scheduler: TestCoroutineScheduler,
        recorder: FakeRecorder,
        transcriber: FakeTranscriber,
        onTranscript: (String) -> Unit = {},
    ): VoiceInputController {
        val file = File.createTempFile("clip", ".mp4").apply { deleteOnExit() }
        return VoiceInputController(
            scope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
            clipFile = file,
            recorderFactory = { recorder },
            transcriber = transcriber,
            onTranscript = onTranscript,
            ioDispatcher = UnconfinedTestDispatcher(scheduler),
        )
    }

    @Test
    fun happyPathRecordUploadDeliver() = runTest {
        val recorder = FakeRecorder()
        val transcriber = FakeTranscriber(Transcript("hello world", "no", 2.0))
        var delivered: String? = null
        val c = controller(testScheduler, recorder, transcriber) { delivered = it }

        assertEquals(VoiceInputState.Idle, c.state)
        c.onMicTap()
        assertEquals(VoiceInputState.Recording, c.state)
        c.onMicTap()
        // Unconfined: the upload ran to completion inside the tap.

        assertEquals(VoiceInputState.Idle, c.state)
        assertEquals("hello world", delivered)
        assertTrue(recorder.stopped)
        assertNull(transcriber.receivedLanguage)
        // §13 no-retention: the clip is deleted after upload.
        assertTrue(requireNotNull(recorder.started).let { !it.exists() })
    }

    @Test
    fun emptyTranscriptIsAFailureNotADelivery() = runTest {
        val transcriber = FakeTranscriber(Transcript("  "))
        var delivered: String? = null
        val c = controller(testScheduler, FakeRecorder(), transcriber) { delivered = it }

        c.onMicTap()
        c.onMicTap()

        assertTrue(c.state is VoiceInputState.Failed)
        assertNull(delivered)
    }

    @Test
    fun stopFailureSuggestsClipTooShort() = runTest {
        val recorder = FakeRecorder(failStop = true)
        val c = controller(testScheduler, recorder, FakeTranscriber())

        c.onMicTap()
        c.onMicTap()

        val state = c.state as VoiceInputState.Failed
        assertTrue(state.reason.contains("short"))
    }

    @Test
    fun offlineUploadMapsToOfflineMessage() = runTest {
        val transcriber = FakeTranscriber(error = OfflineException(RuntimeException("boom")))
        val c = controller(testScheduler, FakeRecorder(), transcriber)

        c.onMicTap()
        c.onMicTap()

        assertTrue((c.state as VoiceInputState.Failed).reason.contains("Offline"))
    }

    @Test
    fun genericUploadFailureStillDeletesClip() = runTest {
        val recorder = FakeRecorder()
        val transcriber = FakeTranscriber(error = RuntimeException("500"))
        val c = controller(testScheduler, recorder, transcriber)

        c.onMicTap()
        c.onMicTap()

        assertTrue(c.state is VoiceInputState.Failed)
        assertTrue(requireNotNull(recorder.started).let { !it.exists() })
    }

    @Test
    fun permissionDeniedIsFailureAndRetryWorks() = runTest {
        val transcriber = FakeTranscriber(Transcript("ok"))
        var delivered: String? = null
        val c = controller(testScheduler, FakeRecorder(), transcriber) { delivered = it }

        c.permissionDenied()
        assertTrue(c.state is VoiceInputState.Failed)

        // Failed state taps again — recovery without a new screen.
        c.onMicTap()
        assertEquals(VoiceInputState.Recording, c.state)
        c.onMicTap()
        assertEquals("ok", delivered)
    }

    @Test
    fun disposeCancelsLiveRecording() = runTest {
        val recorder = FakeRecorder()
        val c = controller(testScheduler, recorder, FakeTranscriber())

        c.onMicTap()
        c.dispose()

        assertTrue(recorder.cancelled)
        assertTrue(!recorder.stopped)
    }

    @Test
    fun uploadingIgnoresTaps() = runTest {
        val recorder = FakeRecorder()
        val gate = CompletableDeferred<Unit>()
        val transcriber = FakeTranscriber(result = Transcript("ok"), gate = gate)
        val c = controller(testScheduler, recorder, transcriber)

        c.onMicTap()
        c.onMicTap()
        // Upload parked at the gate — taps are no-ops mid-flight.
        assertEquals(VoiceInputState.Uploading, c.state)
        c.onMicTap()
        assertEquals(VoiceInputState.Uploading, c.state)

        gate.complete(Unit)
        assertEquals(VoiceInputState.Idle, c.state)
        assertTrue(!recorder.cancelled)
    }

    // ---- wire format against a real HTTP server ----

    @Test
    fun transcriberSendsMultipartAndParsesResponse() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"hei verden","language":"no","duration_s":2.5}"""),
        )
        server.start()
        try {
            val api = HttpApi(baseUrl = server.url("/").toString(), token = "t")
            val clip = File.createTempFile("clip", ".m4a").apply {
                writeBytes(byteArrayOf(0, 1, 2, 3))
                deleteOnExit()
            }
            val transcriber = HttpVoiceTranscriber(api)

            val result = runBlocking {
                transcriber.transcribe(clip, "no")
            }

            assertEquals("hei verden", result.text)
            assertEquals("no", result.language)
            assertEquals(2.5, result.durationSeconds)

            val recorded = server.takeRequest()
            assertEquals("/v1/voice/transcribe", recorded.path)
            assertEquals("Bearer t", recorded.getHeader("Authorization"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("name=\"audio\""))
            assertTrue(body.contains("name=\"language\""))
            assertTrue(body.contains("audio/mp4"))
        } finally {
            server.shutdown()
        }
    }
}
