package dev.magnor.kompakt.data

import dev.magnor.kompakt.data.remote.EnrollmentApi
import dev.magnor.kompakt.data.security.DeviceKeyProvider
import dev.magnor.kompakt.data.security.SoftwareDeviceKeyProvider
import dev.magnor.kompakt.data.security.SecretVault
import dev.magnor.kompakt.domain.RepositoryException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64

/**
 * Enrollment state machine + persistence (Phase 4). Owns the device
 * identity lifecycle so nothing else in the app has to care how auth
 * happened:
 *
 *     NotEnrolled ──requestEnrollment──▶ AwaitingApproval ──poll──▶ Active
 *            ▲                                                    │
 *            └────────────── forget / revoke ─────────────────────┘
 *
 * Persisted in the [SecretVault]: server base URL, device name, device
 * id, device token (secret). The Ed25519 seed persists via the
 * [DeviceKeyProvider]'s own vault slot. Re-enrollment with the same key
 * is idempotent server-side (returns the existing record).
 *
 * [tokenProvider] is the bearer source for [dev.magnor.kompakt.data.remote.HttpApi].
 */
class EnrollmentManager(
    private val vault: SecretVault,
    private val keys: DeviceKeyProvider = SoftwareDeviceKeyProvider(vault),
    private val apiFactory: (baseUrl: String) -> EnrollmentApi = { EnrollmentApi(it) },
) {
    sealed interface State {
        data object NotEnrolled : State
        data class AwaitingApproval(val baseUrl: String, val deviceId: String, val name: String) : State
        data class Active(
            val baseUrl: String,
            val deviceId: String,
            val name: String,
            val capabilities: List<String>,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.NotEnrolled)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        restore()
    }

    /** Current bearer token, or null (→ HttpApi sends no Authorization header). */
    fun tokenProvider(): () -> String? = {
        when (val s = _state.value) {
            is State.Active -> vault.get(KEY_TOKEN)
            else -> null
        }
    }

    /** Active server base URL (for building the remote stack), or null. */
    fun activeBaseUrl(): String? = (state.value as? State.Active)?.baseUrl

    /**
     * Step 1: generate the device key (if needed) and POST the public
     * key. Leaves the manager in AwaitingApproval — an admin must
     * approve before activation can succeed.
     */
    suspend fun requestEnrollment(baseUrl: String, name: String): State {
        val api = apiFactory(baseUrl)
        val publicKey = keys.generateIfAbsent()
        val response = api.enroll(name, publicKey)
        vault.put(KEY_BASE_URL, baseUrl)
        vault.put(KEY_DEVICE_ID, response.deviceId)
        vault.put(KEY_NAME, name)
        vault.remove(KEY_TOKEN)
        val newState = State.AwaitingApproval(baseUrl, response.deviceId, name)
        _state.value = newState
        return newState
    }

    /**
     * Step 2 (repeatable): challenge → sign → activate.
     * Transitions to Active on success and persists the token.
     */
    suspend fun poll(): State {
        val current = _state.value
        val baseUrl = when (current) {
            is State.AwaitingApproval -> current.baseUrl
            is State.Active -> current.baseUrl // re-activation rotates the token
            State.NotEnrolled -> throw RepositoryException("not enrolled")
        }
        val deviceId = vault.get(KEY_DEVICE_ID)
            ?: throw RepositoryException("no device id stored")
        val api = apiFactory(baseUrl)
        val nonceB64 = api.challenge(deviceId)
        val signature = keys.sign(Base64.getDecoder().decode(nonceB64))
            ?: throw RepositoryException("device key missing")
        val result = api.activate(
            deviceId = deviceId,
            nonceBase64 = nonceB64,
            signatureBase64 = Base64.getEncoder().encodeToString(signature),
        )
        return when (result) {
            is EnrollmentApi.ActivationResult.Active -> {
                vault.put(KEY_TOKEN, result.token)
                val newState = State.Active(
                    baseUrl = baseUrl,
                    deviceId = deviceId,
                    name = vault.get(KEY_NAME) ?: deviceId,
                    capabilities = result.capabilities,
                )
                _state.value = newState
                newState
            }
            EnrollmentApi.ActivationResult.Pending -> current
            EnrollmentApi.ActivationResult.Revoked -> {
                forget()
                State.NotEnrolled
            }
            EnrollmentApi.ActivationResult.UnknownDevice -> {
                // Server lost the record (fresh DB) — back to square one.
                forget()
                State.NotEnrolled
            }
        }
    }

    /** Drop all enrollment state locally (device key is kept — reuse on re-enroll). */
    fun forget() {
        vault.remove(KEY_BASE_URL)
        vault.remove(KEY_DEVICE_ID)
        vault.remove(KEY_NAME)
        vault.remove(KEY_TOKEN)
        _state.value = State.NotEnrolled
    }

    /**
     * A 401 from an authenticated call means the token is gone server-side
     * (revoked or rotated elsewhere): clear state, force re-enrollment.
     * Called by the remote stack's error handler.
     */
    fun onUnauthorized() {
        if (_state.value is State.Active) forget()
    }

    private fun restore() {
        val baseUrl = vault.get(KEY_BASE_URL) ?: return
        val deviceId = vault.get(KEY_DEVICE_ID) ?: return
        val name = vault.get(KEY_NAME) ?: deviceId
        val token = vault.get(KEY_TOKEN)
        _state.value = if (token != null) {
            State.Active(baseUrl, deviceId, name, capabilities = emptyList())
        } else {
            State.AwaitingApproval(baseUrl, deviceId, name)
        }
    }

    private companion object {
        const val KEY_BASE_URL = "enrollment.base_url"
        const val KEY_DEVICE_ID = "enrollment.device_id"
        const val KEY_NAME = "enrollment.name"
        const val KEY_TOKEN = "enrollment.token"
    }
}
