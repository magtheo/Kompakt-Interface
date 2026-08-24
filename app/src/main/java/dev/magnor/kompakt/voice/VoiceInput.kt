package dev.magnor.kompakt.voice

import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.magnor.kompakt.data.remote.HttpApi
import dev.magnor.kompakt.domain.KompaktJson
import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.UnauthorizedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Phase 11 voice input (T-021, dev plan §13): tap mic → speak → tap stop →
 * server STT (V-059) → transcript lands in the composer as plain editable
 * text. Recording is EXPLICIT and visible — never background; leaving the
 * screen cancels a live recording ([dispose]). The clip is a cache file
 * deleted after every upload attempt; nothing audio persists on device or
 * server beyond the request.
 */

sealed interface VoiceInputState {
    data object Idle : VoiceInputState
    data object Recording : VoiceInputState
    data object Uploading : VoiceInputState
    data class Failed(val reason: String) : VoiceInputState
}

/** Wire shape of POST /v1/voice/transcribe (V-059). */
@Serializable
data class Transcript(
    val text: String,
    val language: String? = null,
    @SerialName("duration_s") val durationSeconds: Double? = null,
)

/** One recording at a time; implementations are single-use per clip. */
interface VoiceRecorder {
    /** Begin recording into [outputFile]. Throws on mic/OS failure. */
    fun start(outputFile: File)

    /** Finalize and return the clip. Throws if the clip is unusable. */
    fun stop(): File

    /** Abort and discard. Never throws. */
    fun cancel()
}

interface VoiceTranscriber {
    /** Upload one clip; empty text means silence. Throws taxonomy errors. */
    suspend fun transcribe(audio: File, language: String?): Transcript
}

/**
 * State machine behind the mic button. Idle/Failed tap → record;
 * Recording tap → stop + upload; Uploading ignores taps. The transcript
 * is delivered via [onTranscript] — the composer owns what happens next
 * (inspect/correct/submit stay explicit user actions, per the object
 * model).
 */
class VoiceInputController(
    private val scope: CoroutineScope,
    private val clipFile: File,
    private val recorderFactory: () -> VoiceRecorder,
    private val transcriber: VoiceTranscriber,
    private val onTranscript: (String) -> Unit,
    /** Injectable so unit tests run the upload path synchronously. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var state by mutableStateOf<VoiceInputState>(VoiceInputState.Idle)
        private set

    private var active: VoiceRecorder? = null

    fun onMicTap() {
        when (state) {
            VoiceInputState.Idle, is VoiceInputState.Failed -> startRecording()
            VoiceInputState.Recording -> stopAndUpload()
            VoiceInputState.Uploading -> Unit
        }
    }

    fun permissionDenied() {
        state = VoiceInputState.Failed("Microphone permission denied")
    }

    /** Leaving the screen mid-recording stops the mic (no hidden audio). */
    fun dispose() {
        active?.let { runCatching { it.cancel() } }
        active = null
    }

    private fun startRecording() {
        val recorder = recorderFactory()
        try {
            recorder.start(clipFile)
        } catch (e: SecurityException) {
            state = VoiceInputState.Failed("Microphone blocked by the OS")
            return
        } catch (e: Exception) {
            state = VoiceInputState.Failed("Could not start recording")
            return
        }
        active = recorder
        state = VoiceInputState.Recording
    }

    private fun stopAndUpload() {
        val recorder = active ?: return
        state = VoiceInputState.Uploading
        scope.launch {
            val file = try {
                withContext(ioDispatcher) { recorder.stop() }
            } catch (e: Exception) {
                active = null
                state = VoiceInputState.Failed("Clip too short — hold the mic a moment")
                return@launch
            }
            active = null
            try {
                val transcript = transcriber.transcribe(file, language = null)
                if (transcript.text.isBlank()) {
                    state = VoiceInputState.Failed("Nothing recognized — try again")
                } else {
                    state = VoiceInputState.Idle
                    onTranscript(transcript.text.trim())
                }
            } catch (e: OfflineException) {
                state = VoiceInputState.Failed("Offline — voice needs the server")
            } catch (e: UnauthorizedException) {
                state = VoiceInputState.Failed("Not authorized (re-enroll)")
            } catch (e: Exception) {
                state = VoiceInputState.Failed("Transcription failed")
            } finally {
                withContext(ioDispatcher) { runCatching { file.delete() } }
            }
        }
    }
}

/** Merge a transcript into a composer draft: replace-if-empty, else append. */
fun appendTranscript(current: String, transcript: String): String =
    if (current.isBlank()) transcript.trim()
    else current.trimEnd() + " " + transcript.trim()

/**
 * MediaRecorder-backed recorder: AAC in an MP4 container, 16 kHz mono —
 * small clips, and a format the server (av + whisper) decodes natively.
 */
class MediaRecorderVoiceRecorder : VoiceRecorder {

    private var recorder: MediaRecorder? = null
    private var current: File? = null

    override fun start(outputFile: File) {
        check(recorder == null) { "recorder already started" }
        outputFile.delete()
        val r = @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(24_000)
            r.setOutputFile(outputFile.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
        current = outputFile
    }

    override fun stop(): File {
        val r = requireNotNull(recorder) { "not recording" }
        val file = requireNotNull(current)
        recorder = null
        current = null
        try {
            r.stop()
        } finally {
            r.release()
        }
        return file
    }

    override fun cancel() {
        val r = recorder ?: return
        recorder = null
        current?.delete()
        current = null
        try {
            r.stop() // may throw if nothing valid was captured — discard anyway
        } catch (_: RuntimeException) {
            // no-op
        } finally {
            r.release()
        }
    }
}

/** Uploads via the shared /v1 plumbing; audio/mp4 matches the recorder. */
class HttpVoiceTranscriber(private val api: HttpApi) : VoiceTranscriber {

    override suspend fun transcribe(audio: File, language: String?): Transcript {
        val bytes = withContext(Dispatchers.IO) { audio.readBytes() }
        val body = api.postMultipart(
            path = "v1/voice/transcribe",
            fileBytes = bytes,
            filename = audio.name,
            contentType = "audio/mp4",
            formFields = if (language.isNullOrBlank()) emptyMap() else mapOf("language" to language),
            // whisper on CPU can take several seconds per clip — extend all
            // per-IO timeouts together (HttpApi lesson from chat T-009).
            timeoutSeconds = 90,
        )
        return KompaktJson.decodeFromString(Transcript.serializer(), body)
    }
}
