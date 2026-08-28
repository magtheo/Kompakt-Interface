@file:UseSerializers(InstantIso8601Serializer::class)

package dev.magnor.kompakt.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Capability negotiation payload — GET /v1/capabilities (protocol §8).
 * Features the server marks false must not be shown or enabled (§9).
 */
@Serializable
data class CapabilitySet(
    @SerialName("server_protocol") val serverProtocol: Int,
    @SerialName("minimum_client_protocol") val minimumClientProtocol: Int,
    val features: Map<String, Boolean> = emptyMap(),
    /**
     * T-024 / V-069: the device's OWN granted capabilities, served only
     * when the caller is a device principal. Null on older servers (and
     * demo/local sets) — treated as "grants unknown", i.e. fail-open:
     * surface gating falls back to feature flags alone (§9 behavior).
     */
    val granted: GrantedCapabilities? = null,
) {
    fun supports(feature: String): Boolean = features[feature] ?: false

    /**
     * True when `capability` is within this device's granted set. A null
     * [granted] (old server, demo, local) grants everything — capability
     * hiding must never brick a client on an unupgraded server.
     */
    fun grants(capability: String): Boolean = granted?.capabilities?.contains(capability) ?: true
}

/** Device-scoped grants block of /v1/capabilities (V-069). */
@Serializable
data class GrantedCapabilities(
    @SerialName("device_id") val deviceId: String,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class ServerStatus(
    val healthy: Boolean = true,
    val serverTime: Instant? = null,
    val version: String? = null,
)

sealed interface ProtocolVerdict {
    /** Compatible — proceed. */
    data object Ok : ProtocolVerdict

    /** client_protocol < minimum_client_protocol → hard-stop, explicit error. */
    data class ClientTooOld(val minimumClientProtocol: Int) : ProtocolVerdict

    /** Server older than anything this client can talk to. */
    data class ServerTooOld(val serverProtocol: Int, val minimumServerProtocol: Int) : ProtocolVerdict
}

/**
 * Pure compatibility check (protocol §9). Kept free of Android imports so
 * it is trivially testable; the client passes AppInfo values in.
 */
object ProtocolNegotiation {
    fun evaluate(
        clientProtocol: Int,
        minimumServerProtocol: Int,
        caps: CapabilitySet,
    ): ProtocolVerdict = when {
        clientProtocol < caps.minimumClientProtocol ->
            ProtocolVerdict.ClientTooOld(caps.minimumClientProtocol)
        caps.serverProtocol < minimumServerProtocol ->
            ProtocolVerdict.ServerTooOld(caps.serverProtocol, minimumServerProtocol)
        else -> ProtocolVerdict.Ok
    }
}
