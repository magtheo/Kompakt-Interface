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

    /**
     * T-044: the onWifi() snapshot loses the wake race on cold e-ink
     * opens (WiFi caps not reported yet → PUBLIC first at home → ~30 s
     * burned on the hairpin-dead path; observed 2026-09-03 11:04).
     * The last endpoint that actually carried traffic goes first,
     * whatever the WiFi snapshot said.
     */
    fun ordered(onWifi: Boolean, lastGood: String?): List<String> {
        val base = candidates(onWifi)
        if (lastGood == null || lastGood !in base) return base
        return listOf(lastGood) + base.filter { it != lastGood }
    }
}
