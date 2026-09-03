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
}
