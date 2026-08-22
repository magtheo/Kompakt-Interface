package dev.magnor.kompakt.data.remote

import dev.magnor.kompakt.domain.KompaktJson
import dev.magnor.kompakt.domain.OfflineException
import dev.magnor.kompakt.domain.RepositoryException
import dev.magnor.kompakt.domain.ServerUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Enrollment transport (Phase 4, dev plan §6) — the ONLY unauthenticated
 * calls the app makes. Everything else goes through [HttpApi] with the
 * per-device bearer token this flow produces.
 *
 *   POST /v1/devices/enroll                {name, public_key}
 *   GET  /v1/devices/{id}/challenge        → {nonce}
 *   POST /v1/devices/{id}/activate         {nonce, signature}
 *       200 → device_token+capabilities · 202 pending · 403 revoked · 404 unknown
 */
class EnrollmentApi(
    baseUrl: String,
    private val client: OkHttpClient = HttpApi.defaultClient(),
) {
    val base: HttpUrl = baseUrl.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("invalid server URL: $baseUrl")

    @Serializable
    data class EnrollRequest(val name: String, @SerialName("public_key") val publicKey: String)

    @Serializable
    data class EnrollResponse(
        @SerialName("device_id") val deviceId: String,
        val status: String,
    ) {
        companion object {
            fun from(body: String): EnrollResponse = KompaktJson.decodeFromString(body)
        }
    }

    @Serializable
    private data class ChallengeResponse(val nonce: String)

    @Serializable
    private data class ActivateRequest(val nonce: String, val signature: String)

    @Serializable
    private data class ActivateResponse(
        @SerialName("device_token") val deviceToken: String? = null,
        val capabilities: List<String> = emptyList(),
    )

    sealed interface ActivationResult {
        data class Active(val token: String, val capabilities: List<String>) : ActivationResult
        data object Pending : ActivationResult
        data object Revoked : ActivationResult
        data object UnknownDevice : ActivationResult
    }

    suspend fun enroll(name: String, publicKeyBase64: String): EnrollResponse {
        val body = KompaktJson.encodeToString(EnrollRequest(name, publicKeyBase64))
        val raw = execute(
            Request.Builder()
                .post(body.toRequestBody("application/json".toMediaType()))
                .url(url("v1/devices/enroll"))
                .build()
        )
        // 201 or 200 (re-enroll of a known key) — both carry the record.
        return EnrollResponse.from(raw)
    }

    suspend fun challenge(deviceId: String): String {
        val raw = execute(
            Request.Builder().get().url(url("v1/devices/$deviceId/challenge")).build()
        )
        return KompaktJson.decodeFromString<ChallengeResponse>(raw).nonce
    }

    suspend fun activate(deviceId: String, nonceBase64: String, signatureBase64: String): ActivationResult {
        val body = KompaktJson.encodeToString(ActivateRequest(nonceBase64, signatureBase64))
        val (code, raw) = executeWithStatus(
            Request.Builder()
                .post(body.toRequestBody("application/json".toMediaType()))
                .url(url("v1/devices/$deviceId/activate"))
                .build()
        )
        return when {
            code == 200 -> {
                val parsed = KompaktJson.decodeFromString<ActivateResponse>(raw)
                ActivationResult.Active(
                    token = parsed.deviceToken ?: throw RepositoryException("activate 200 without token"),
                    capabilities = parsed.capabilities,
                )
            }
            code == 202 -> ActivationResult.Pending
            code == 403 -> ActivationResult.Revoked
            code == 404 -> ActivationResult.UnknownDevice
            code == 401 -> throw RepositoryException("activation signature rejected")
            else -> throw RepositoryException("activate failed (HTTP $code): $raw")
        }
    }

    // ---- internals ----

    private fun url(path: String): HttpUrl = base.newBuilder().addEncodedPathSegments(path).build()

    private suspend fun execute(request: Request): String = executeWithStatus(request).second

    private suspend fun executeWithStatus(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        val tagged = request.newBuilder()
            .header("Accept", "application/json")
            .build() // no Authorization — enrollment is the credential-free path
        val response = try {
            client.newCall(tagged).execute()
        } catch (e: IOException) {
            throw OfflineException(e)
        }
        response.use {
            val body = it.body?.string().orEmpty()
            when {
                it.isSuccessful || it.code == 202 -> it.code to body
                it.code in 500..599 -> throw ServerUnavailableException(it.code)
                else -> it.code to body // 401/403/404 handled by callers
            }
        }
    }
}
