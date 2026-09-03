package dev.magnor.kompakt.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-044: endpoint order — LAN first on WiFi (no router forward needed),
 * public first otherwise; the other candidate is always the fallback.
 */
class EndpointPolicyTest {

    @Test
    fun `wifi prefers LAN with public fallback`() {
        assertEquals(
            listOf(EndpointPolicy.LAN_ENDPOINT, EndpointPolicy.PUBLIC_ENDPOINT),
            EndpointPolicy.candidates(onWifi = true),
        )
    }

    @Test
    fun `cellular prefers public with LAN fallback`() {
        assertEquals(
            listOf(EndpointPolicy.PUBLIC_ENDPOINT, EndpointPolicy.LAN_ENDPOINT),
            EndpointPolicy.candidates(onWifi = false),
        )
    }

    @Test
    fun `ordered puts last-good first even when wifi snapshot is wrong`() {
        // 2026-09-03 11:04: onWifi() raced the e-ink wake and said false
        // at home → public-first → 30 s dead hairpin path. Last-good
        // (LAN) must win regardless of the snapshot.
        assertEquals(
            listOf(EndpointPolicy.LAN_ENDPOINT, EndpointPolicy.PUBLIC_ENDPOINT),
            EndpointPolicy.ordered(onWifi = false, lastGood = EndpointPolicy.LAN_ENDPOINT),
        )
    }

    @Test
    fun `ordered keeps last-good public first away from home`() {
        assertEquals(
            listOf(EndpointPolicy.PUBLIC_ENDPOINT, EndpointPolicy.LAN_ENDPOINT),
            EndpointPolicy.ordered(onWifi = false, lastGood = EndpointPolicy.PUBLIC_ENDPOINT),
        )
    }

    @Test
    fun `ordered with no history falls back to the wifi policy`() {
        assertEquals(
            listOf(EndpointPolicy.PUBLIC_ENDPOINT, EndpointPolicy.LAN_ENDPOINT),
            EndpointPolicy.ordered(onWifi = false, lastGood = null),
        )
    }

    @Test
    fun `ordered ignores a stale or foreign last-good value`() {
        assertEquals(
            listOf(EndpointPolicy.LAN_ENDPOINT, EndpointPolicy.PUBLIC_ENDPOINT),
            EndpointPolicy.ordered(onWifi = true, lastGood = "203.0.113.9:51821"),
        )
    }
}
