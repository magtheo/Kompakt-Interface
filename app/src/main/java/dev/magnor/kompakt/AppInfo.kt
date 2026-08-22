package dev.magnor.kompakt

/**
 * Protocol identity of this client build (see docs/protocol-and-sync.md).
 * The client sends its protocol version during capability negotiation and
 * hard-stops if it is below the server's minimum_client_protocol.
 */
object AppInfo {
    const val APP_PROTOCOL = 1
    const val MINIMUM_SERVER_PROTOCOL = 1
}
