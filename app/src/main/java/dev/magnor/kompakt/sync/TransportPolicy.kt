package dev.magnor.kompakt.sync

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * T-044 (D032): enrollment identity vs. transport are separate concerns.
 *
 * The enrollment stores the tailnet URL (https://dev-server…:8650 via
 * tailscale serve). When a tunnel config is present on the device, the
 * ACTIVE transport is the plain-wg spoke: coordinator on the tunnel
 * address, cleartext inside the encrypted tunnel (network security
 * config permits cleartext to 10.127.127.1 only).
 *
 * Pure logic — unit-tested, no Android imports.
 */
object TransportPolicy {

    const val TUNNEL_HOST = "10.127.127.1"

    /**
     * Resolve the effective base URL for the remote stack.
     * Tunnel mode rewrites scheme+host, keeping the port (8650) and path.
     * Anything the enrolled URL carries that we can't parse is returned
     * untouched (fail-open to the stored enrollment URL).
     */
    fun resolve(enrolledBaseUrl: String, tunnelConfigured: Boolean): String {
        if (!tunnelConfigured) return enrolledBaseUrl
        val url = enrolledBaseUrl.toHttpUrlOrNull() ?: return enrolledBaseUrl
        val rewritten = url.newBuilder()
            .scheme("http")
            .host(TUNNEL_HOST)
            .build()
            .toString()
        // okhttp renders an empty path as "/" — enrollment URLs are stored
        // without it, keep the rewrite shape-identical.
        return rewritten.removeSuffix("/")
    }
}
