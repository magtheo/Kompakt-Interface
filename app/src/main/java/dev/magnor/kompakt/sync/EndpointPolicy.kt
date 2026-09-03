package dev.magnor.kompakt.sync

/**
 * T-044: which wg endpoint(s) to try, in order.
 *
 * Home WiFi reaches the server's LAN address directly (no router forward
 * involved); any other network (cellular, foreign WiFi) goes via the
 * public endpoint. The other candidate is always kept as a fallback —
 * a foreign WiFi can't reach the LAN address, and the public endpoint
 * works from home too whenever the router hairpin allows it.
 *
 * Pure logic — unit-tested.
 */
object EndpointPolicy {

    const val LAN_ENDPOINT = "192.168.0.135:51821"
    const val PUBLIC_ENDPOINT = "203.0.113.10:51821"

    fun candidates(onWifi: Boolean): List<String> =
        if (onWifi) listOf(LAN_ENDPOINT, PUBLIC_ENDPOINT)
        else listOf(PUBLIC_ENDPOINT, LAN_ENDPOINT)
}
