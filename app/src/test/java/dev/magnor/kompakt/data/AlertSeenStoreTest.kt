package dev.magnor.kompakt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T-020 — persisted alert dedupe: firstSeen transitions, durability
 * across instances (the point of persistence: worker-after-reboot must
 * not re-notify), bounded LRU, clear.
 */
class AlertSeenStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `firstSeen true once then false`() {
        val store = AlertSeenStore(dir = null)
        assertTrue(store.firstSeen("alert:sA:1"))
        assertFalse(store.firstSeen("alert:sA:1"))
        assertTrue(store.seen("alert:sA:1"))
    }

    @Test
    fun `markSeen suppresses later firstSeen`() {
        val store = AlertSeenStore(dir = null)
        store.markSeen("alert:sA:2")
        assertFalse(store.firstSeen("alert:sA:2"))
    }

    @Test
    fun `persists across instances in same dir`() {
        val dir = tmp.newFolder()
        AlertSeenStore(dir).firstSeen("alert:sA:3")

        // Fresh instance (process-death stand-in) must still know it.
        val reborn = AlertSeenStore(dir)
        assertFalse(reborn.firstSeen("alert:sA:3"))
        assertTrue(reborn.seen("alert:sA:3"))
        assertTrue(reborn.firstSeen("alert:sA:4"))
    }

    @Test
    fun `independent dirs do not share state`() {
        val a = AlertSeenStore(tmp.newFolder())
        val b = AlertSeenStore(tmp.newFolder())
        a.firstSeen("alert:sA:5")
        assertTrue(b.firstSeen("alert:sA:5"))
    }

    @Test
    fun `capacity bound evicts oldest`() {
        val store = AlertSeenStore(dir = null)
        repeat(300) { store.firstSeen("alert:x$it") }
        // 256-capacity LRU: the first 44 ids were evicted…
        assertFalse(store.seen("alert:x0"))
        // …recent ones remain.
        assertTrue(store.seen("alert:x299"))
    }

    @Test
    fun `clear forgets everything`() {
        val dir = tmp.newFolder()
        val store = AlertSeenStore(dir)
        store.firstSeen("alert:sA:6")
        store.clear()
        assertFalse(store.seen("alert:sA:6"))
        // …and a fresh instance sees nothing either (file deleted).
        assertFalse(AlertSeenStore(dir).seen("alert:sA:6"))
    }

    @Test
    fun `corrupt file degrades to empty`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "alert-seen.json").writeText("{not json")
        val store = AlertSeenStore(dir)
        assertFalse(store.seen("alert:sA:7"))
        assertTrue(store.firstSeen("alert:sA:7"))
    }
}
