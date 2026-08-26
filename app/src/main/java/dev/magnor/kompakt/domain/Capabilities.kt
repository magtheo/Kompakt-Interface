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
) {
    fun supports(feature: String): Boolean = features[feature] ?: false
}

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
