package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.KompaktJson
import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.RepositoryException
import dev.magnor.kompakt.domain.RevisionConflictException
import dev.magnor.kompakt.domain.ServerUnavailableException
import dev.magnor.kompakt.domain.UnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP plumbing for the /v1/ server contract (protocol §7–§15).
 * All repositories live above this; it knows endpoints and error mapping,
 * nothing about domain semantics beyond the failure taxonomy.
 *
 * Wire rules honored here:
 * - unknown JSON fields are ignored (KompaktJson),
 * - 401 → UnauthorizedException (re-enroll), 409 → RevisionConflictException,
 * - 5xx → ServerUnavailableException, transport failure → OfflineException.
 *
 * The bearer comes from a provider function so enrollment can rotate
 * the token without rebuilding the stack (Phase 4).
 */
class HttpApi(
    baseUrl: String,
    private val tokenProvider: () -> String?,
    private val client: OkHttpClient = defaultClient(),
) {
    constructor(baseUrl: String, token: String) : this(baseUrl, { token })

    val base: HttpUrl = baseUrl.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("invalid server URL: $baseUrl")

    /** Raw GET returning the body, mapped into the failure taxonomy. */
    suspend fun get(path: String, query: Map<String, String?> = emptyMap()): String =
        execute(Request.Builder().get().url(url(path, query)).build())

    /** Raw POST with a JSON body. [timeoutSeconds] overrides the default
     *  call timeout for endpoints with long-latency server work (chat LLM). */
    suspend fun post(
        path: String,
        bodyJson: String,
        requestId: String? = null,
        timeoutSeconds: Long? = null,
    ): String {
        val builder = Request.Builder()
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .url(url(path))
        requestId?.let { builder.header("X-Request-Id", it) }
        return execute(builder.build(), timeoutSeconds)
    }

    suspend inline fun <reified T> decode(
        path: String,
        query: Map<String, String?> = emptyMap(),
    ): T = KompaktJson.decodeFromString(get(path, query))

    /**
     * Envelope list decode: `{"<key>": [ ... ], ...extra }`. Tolerates
     * unknown envelope fields (future pagination) and a missing key
     * (→ empty list) per protocol §9 forward-compat rules.
     */
    suspend inline fun <reified T> decodeList(
        path: String,
        key: String,
        query: Map<String, String?> = emptyMap(),
    ): List<T> {
        val element = KompaktJson.parseToJsonElement(get(path, query))
        val array = (element as? JsonObject)?.get(key) as? JsonArray ?: return emptyList()
        return array.map { KompaktJson.decodeFromJsonElement(it) }
    }

    // ---- internals ----

    private fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = base.newBuilder().addEncodedPathSegments(path.trimStart('/'))
        query.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private suspend fun execute(request: Request, timeoutSeconds: Long? = null): String =
        withContext(Dispatchers.IO) {
            val callClient = if (timeoutSeconds != null) {
                client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
            } else client
            val builder = request.newBuilder()
                .header("Accept", "application/json")
            tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
            val tagged = builder.build()
            val response: Response = try {
                callClient.newCall(tagged).execute()
            } catch (e: IOException) {
                throw OfflineException(e)
            }
        response.use {
            val body = it.body?.string().orEmpty()
            when {
                it.isSuccessful -> body
                it.code == 401 -> throw UnauthorizedException()
                it.code == 409 -> throw RevisionConflictException(
                    currentRevision = parseRevision(body),
                )
                it.code in 500..599 -> throw ServerUnavailableException(it.code)
                else -> throw RepositoryException("server rejected (HTTP ${it.code}): $body")
            }
        }
    }

    private fun parseRevision(body: String): Long = try {
        KompaktJson.decodeFromString<ConflictBody>(body).currentRevision ?: 0L
    } catch (_: Exception) {
        0L
    }

    @Serializable
    private data class ConflictBody(@SerialName("current_revision") val currentRevision: Long? = null)

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
