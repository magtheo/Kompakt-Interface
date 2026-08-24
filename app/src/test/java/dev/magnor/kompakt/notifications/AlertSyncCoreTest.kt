package dev.magnor.kompakt.notifications

import dev.magnor.kompakt.data.AlertSeenStore
import dev.magnor.kompakt.data.remote.HttpApi
import dev.magnor.kompakt.domain.InboxItem
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-020 — the fallback-sync core against a mock server: unread inbox
 * fetch → post-unseen, deduped by the seen store so live-path and
 * worker-path deliveries never double-notify.
 */
class AlertSyncCoreTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api() = HttpApi(server.url("/").toString(), "test-token")

    private fun itemJson(id: String, title: String = "t") =
        """{"id": "$id", "title": "$title", "timestamp": "2026-08-24T09:00:00Z",
           "updated_at": "2026-08-24T09:00:00Z"}"""

    private fun enqueue(vararg items: String) {
        server.enqueue(
            MockResponse().setBody("""{"inbox_items": [${items.joinToString(",")}]}""")
                .setHeader("Content-Type", "application/json")
        )
    }

    @Test
    fun `posts every unread item first run`() = runBlocking {
        enqueue(itemJson("alert:sA:1"), itemJson("alert:sA:2"))
        val posted = mutableListOf<InboxItem>()
        val n = fetchAndPostUnseen(api(), AlertSeenStore(dir = null)) { posted.add(it) }
        assertEquals(2, n)
        assertEquals(listOf("alert:sA:1", "alert:sA:2"), posted.map { it.id })
    }

    @Test
    fun `second run posts nothing new for same items`() = runBlocking {
        enqueue(itemJson("alert:sA:1"), itemJson("alert:sA:2"))
        enqueue(itemJson("alert:sA:1"), itemJson("alert:sA:2"))
        val seen = AlertSeenStore(dir = null)
        assertEquals(2, fetchAndPostUnseen(api(), seen) {})
        assertEquals(0, fetchAndPostUnseen(api(), seen) {})
    }

    @Test
    fun `mix of seen and new posts only the new`() = runBlocking {
        val seen = AlertSeenStore(dir = null)
        seen.markSeen("alert:sA:1")
        enqueue(itemJson("alert:sA:1"), itemJson("alert:sA:3"))
        val postedIds = mutableListOf<String>()
        assertEquals(1, fetchAndPostUnseen(api(), seen) { postedIds.add(it.id) })
        assertEquals(listOf("alert:sA:3"), postedIds)
    }

    @Test
    fun `empty inbox posts nothing`() = runBlocking {
        enqueue()
        assertEquals(0, fetchAndPostUnseen(api(), AlertSeenStore(dir = null)) {})
    }

    @Test
    fun `bearer token present on request`() = runBlocking {
        enqueue()
        fetchAndPostUnseen(api(), AlertSeenStore(dir = null)) {}
        val recorded = server.takeRequest()
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertTrue(recorded.path!!.startsWith("/v1/inbox"))
    }

    @Test
    fun `server error propagates to worker retry layer`() {
        server.enqueue(MockResponse().setResponseCode(500))
        var threw = false
        try {
            runBlocking { fetchAndPostUnseen(api(), AlertSeenStore(dir = null)) {} }
        } catch (e: Exception) {
            threw = true // worker maps this to Result.retry()
        }
        assertTrue(threw)
    }
}
