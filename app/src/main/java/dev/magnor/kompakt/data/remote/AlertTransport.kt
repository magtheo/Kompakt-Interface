package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.KompaktJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * D008 update transport — the swappable push leg.
 *
 * The coordinator exposes `GET /v1/alerts/stream` (SSE): unread alerts
 * replay once on connect, live alerts follow as the watcher records them,
 * comment heartbeats keep intermediaries from reaping the connection.
 * Read-state IS the dedupe mechanism (D009): replays are idempotent
 * because the client keys notifications on the alert id.
 *
 * The app is its own notification client — no FCM, no ntfy app (D008).
 */
interface AlertTransport {
    /** Cold flow of alert items; reconnects forever with backoff. */
    fun alerts(): Flow<InboxItem>
}

/**
 * Incremental SSE parser (RFC 8895 subset used by the coordinator):
 * `event:`/`id:`/`data:` field lines, `:`-prefixed comments (heartbeats),
 * blank line dispatches the block. Pure state machine — unit tested
 * without any socket.
 */
class SseParser {
    private var event: String? = null
    private var id: String? = null
    private val data = StringBuilder()

    /** Feed one line; returns a parsed block when the line closes it. */
    fun line(raw: String): SseBlock? {
        if (raw.isEmpty()) {
            val block = if (data.isNotEmpty()) {
                SseBlock(event = event, id = id, data = data.toString())
            } else null
            event = null
            id = null
            data.clear()
            return block
        }
        when (raw[0]) {
            ':' -> return null // comment / heartbeat
            '\n' -> return null
        }
        val sep = raw.indexOf(':')
        val field = if (sep == -1) raw else raw.substring(0, sep)
        // Per spec one optional leading space after ':' is stripped.
        var value = if (sep == -1) "" else raw.substring(sep + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "id" -> id = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }

    /** Flush a truncated final block (stream ended mid-event). */
    fun finish(): SseBlock? = line("")
}

data class SseBlock(val event: String?, val id: String?, val data: String)

/**
 * OkHttp SSE transport with self-healing reconnect.
 *
 * Timeouts: heartbeats arrive every 15s server-side; a read timeout of
 * 3× that signals a dead connection → exponential backoff reconnect
 * (1s, 2s, 4s … capped 60s, reset once a stream establishes). The
 * server replays unread alerts on every connect, so a reconnect loses
 * nothing — at most it re-emits ids the caller already dedupes.
 */
class SseAlertTransport(
    baseUrl: String,
    private val tokenProvider: () -> String?,
    private val client: OkHttpClient = streamClient(),
) : AlertTransport {

    private val base = baseUrl.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("invalid server URL: $baseUrl")

    override fun alerts(): Flow<InboxItem> = flow {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(base.newBuilder().addEncodedPathSegments("v1/alerts/stream").build())
                .header("Accept", "text/event-stream")
                .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
                .build()
            try {
                // Runs on IO via flowOn below — do NOT wrap in withContext:
                // emitting from a nested context switch violates the flow
                // emission invariant (verified the hard way).
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw HttpError(response.code)
                    val parser = SseParser()
                    val source = response.body?.source() ?: return@use
                    while (true) {
                        // null = EOF (server closed) → reconnect below.
                        // Dead link: readTimeout (3× heartbeat) throws.
                        val rawLine = source.readUtf8Line() ?: break
                        val block = parser.line(rawLine) ?: continue
                        if (block.event == "alert" && block.data.isNotBlank()) {
                            attempt = 0 // stream established
                            // One malformed block skips — never kills the
                            // connection (protocol §9: forward-compat).
                            val item = try {
                                KompaktJson.decodeFromString<InboxItem>(block.data)
                            } catch (e: kotlinx.serialization.SerializationException) {
                                continue
                            }
                            emit(item)
                        }
                    }
                    // Clean EOF (server closed): reconnect below —
                    // the replay-on-connect contract makes a torn
                    // final block harmless to drop.
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (System.getenv("KOMPAKT_SSE_DEBUG") != null) {
                    println("SSE reconnect cause: ${e::class.qualifiedName}: ${e.message}")
                }
                // HttpError, IOException (dead link / read timeout), decode
                // of one malformed block — all recover the same way.
            }
            // 401 is permanent until re-enrollment; still retry slowly —
            // the enrollment flow rotates the token via tokenProvider.
            delay(backoffMs(attempt))
            attempt++
        }
    }.flowOn(Dispatchers.IO)

    private fun backoffMs(attempt: Int): Long =
        (1000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000L)

    private class HttpError(val code: Int) : Exception("alert stream HTTP $code")

    companion object {
        fun streamClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS) // 3× heartbeat
            .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS) // TCP keepalive
            .build()
    }
}
