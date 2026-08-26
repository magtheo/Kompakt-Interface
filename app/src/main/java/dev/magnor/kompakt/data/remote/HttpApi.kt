package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.KompaktJson
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.NoteConflictException
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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

    /** Read-only token access for sibling transports (T-019 alert stream). */
    fun token(): String? = tokenProvider()

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

    /** Raw PUT with a JSON body (notes text edit, D028 v2). Same failure
     *  taxonomy as [post]; whole-body upfront so default timeouts apply. */
    suspend fun put(
        path: String,
        bodyJson: String,
    ): String {
        val request = Request.Builder()
            .put(bodyJson.toRequestBody("application/json".toMediaType()))
            .url(url(path))
            .build()
        return execute(request)
    }

    /** Raw PATCH with a JSON body (T-023 events; V-065). Same failure
     *  taxonomy as [put] — occurrence PATCHes create server-side exceptions. */
    suspend fun patch(
        path: String,
        bodyJson: String,
    ): String {
        val request = Request.Builder()
            .patch(bodyJson.toRequestBody("application/json".toMediaType()))
            .url(url(path))
            .build()
        return execute(request)
    }

    /** Raw DELETE with an opaque id segment in the path. Same failure
     *  taxonomy as [post]; we do NOT consume the response here — callers
     *  decide whether to read it or not. For /v1/events/{id} returns 200
     *  + body (not 204); the caller treats success as true and ignores body. */
    suspend fun delete(path: String): String {
        val request = Request.Builder()
            .delete()
            .url(url(path))
            .build()
        return execute(request)
    }

    /** Raw POST with a multipart body (T-021 voice upload). Same failure
     *  taxonomy as [post]; [timeoutSeconds] for long server work. */
    suspend fun postMultipart(
        path: String,
        fileBytes: ByteArray,
        filename: String,
        contentType: String,
        formFields: Map<String, String> = emptyMap(),
        timeoutSeconds: Long? = null,
    ): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                formFields.forEach { (name, value) -> addFormDataPart(name, value) }
            }
            .addFormDataPart(
                "audio", filename,
                fileBytes.toRequestBody(contentType.toMediaType()),
            )
            .build()
        val request = Request.Builder().post(body).url(url(path)).build()
        return execute(request, timeoutSeconds)
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
                // OkHttp's default readTimeout (10s) fires during silent
                // long-latency server work (chat LLM generates before any
                // bytes flow) even when callTimeout is raised — extend the
                // per-IO timeouts as well, not just the whole-call budget.
                client.newBuilder()
                    .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .build()
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
                it.code == 409 -> throw conflict(body)
                it.code in 500..599 -> throw ServerUnavailableException(it.code)
                else -> throw RepositoryException("server rejected (HTTP ${it.code}): $body")
            }
        }
    }

    /**
     * A 409 is a revision conflict only when the server says so
     * (`current_revision` present — sync endpoints). Agents busy-409s
     * (SessionBusyError while a turn runs) carry a plain `detail`;
     * surface that honestly instead of masquerading as a sync conflict.
     * Notes edits (D028 v2) carry `detail.reason = "checksum_mismatch"`
     * with the fresh note attached — decode into NoteConflictException.
     */
    private fun conflict(body: String): Exception {
        val parsed = try {
            KompaktJson.decodeFromString<ConflictBody>(body)
        } catch (_: Exception) {
            null
        }
        if (parsed?.currentRevision != null) {
            return RevisionConflictException(currentRevision = parsed.currentRevision)
        }
        // Notes checksum 409: detail is an OBJECT (reason + fresh note row).
        val detailElement = (KompaktJson.parseToJsonElement(body) as? JsonObject)
            ?.get("detail") as? JsonObject
        if (detailElement?.get("reason")?.jsonPrimitive?.contentOrNull == "checksum_mismatch") {
            val freshNote = detailElement.get("note")?.let {
                try {
                    KompaktJson.decodeFromJsonElement<Note>(it)
                } catch (_: Exception) {
                    null
                }
            }
            if (freshNote != null) return NoteConflictException(fresh = freshNote)
        }
        val detail = try {
            KompaktJson.decodeFromString<ErrorBody>(body).detail
        } catch (_: Exception) {
            null
        }
        return RepositoryException(detail ?: "conflict: $body")
    }

    @Serializable
    private data class ErrorBody(val detail: String? = null)

    @Serializable
    private data class ConflictBody(@SerialName("current_revision") val currentRevision: Long? = null)

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
