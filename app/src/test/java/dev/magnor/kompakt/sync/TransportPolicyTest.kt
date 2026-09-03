package dev.magnor.kompakt.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-044: enrollment identity vs. active transport. Tunnel mode rewrites
 * scheme+host to the wg address (port preserved), non-tunnel mode is a
 * no-op — the stored enrollment URL stays authoritative.
 */
class TransportPolicyTest {

    @Test
    fun `tunnel off returns enrolled url untouched`() {
        val url = "https://dev-server.example.ts.net:8650"
        assertEquals(url, TransportPolicy.resolve(url, tunnelConfigured = false))
    }

    @Test
    fun `tunnel on rewrites to cleartext tunnel host keeping port`() {
        assertEquals(
            "http://10.127.127.1:8650",
            TransportPolicy.resolve(
                "https://dev-server.example.ts.net:8650",
                tunnelConfigured = true,
            ),
        )
    }

    @Test
    fun `tunnel on keeps trailing path`() {
        assertEquals(
            "http://10.127.127.1:8650/api",
            TransportPolicy.resolve("https://example.com:8650/api", tunnelConfigured = true),
        )
    }

    @Test
    fun `unparseable url fails open to stored value`() {
        val weird = "not a url"
        assertEquals(weird, TransportPolicy.resolve(weird, tunnelConfigured = true))
    }
}
