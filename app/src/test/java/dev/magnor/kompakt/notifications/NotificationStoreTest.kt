package dev.magnor.kompakt.notifications

import dev.magnor.kompakt.notifications.NotificationStore.Item
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-043 — the notifications screen's backing store: ordering, upsert,
 * removal, and dismiss routing (system rejects flow back via
 * onNotificationRemoved; dismissAll only touches dismissable items).
 */
class NotificationStoreTest {

    @Before
    fun reset() = NotificationStore.reset()

    private fun item(
        key: String,
        pkg: String = "com.example.app",
        time: Long = 1_000,
        ongoing: Boolean = false,
        dismissable: Boolean = true,
    ) = Item(key, pkg, "t-$key", "body", time, ongoing, dismissable)

    @Test
    fun `replaceAll sorts newest first`() = kotlinx.coroutines.test.runTest {
        NotificationStore.replaceAll(listOf(item("old", time = 1), item("new", time = 3), item("mid", time = 2)))
        assertEquals(listOf("new", "mid", "old"), NotificationStore.items.first().map { it.key })
    }

    @Test
    fun `upsert replaces by key and re-sorts`() = kotlinx.coroutines.test.runTest {
        NotificationStore.replaceAll(listOf(item("a", time = 1), item("b", time = 5)))
        NotificationStore.upsert(item("a", time = 9)) // updated → now newest
        val keys = NotificationStore.items.first().map { it.key }
        assertEquals(listOf("a", "b"), keys) // single entry for "a", not two
    }

    @Test
    fun `remove drops by key`() = kotlinx.coroutines.test.runTest {
        NotificationStore.replaceAll(listOf(item("a"), item("b")))
        NotificationStore.remove("a")
        assertEquals(listOf("b"), NotificationStore.items.first().map { it.key })
    }

    @Test
    fun `dismissAll only dismisses dismissable and routes through binder`() = kotlinx.coroutines.test.runTest {
        val dismissed = mutableListOf<String>()
        NotificationStore.bind { dismissed.add(it) }
        NotificationStore.replaceAll(
            listOf(
                item("gone", dismissable = true),
                item("ongoing", ongoing = true, dismissable = false),
            )
        )
        NotificationStore.dismissAll()
        assertEquals(listOf("gone"), dismissed)
        // Store not mutated synchronously — the system's onNotificationRemoved
        // is the single source of removal truth.
        assertEquals(2, NotificationStore.items.first().size)
    }

    @Test
    fun `dismiss without binder is a no-op`() = kotlinx.coroutines.test.runTest {
        NotificationStore.replaceAll(listOf(item("a")))
        NotificationStore.dismiss("a") // must not throw
        assertTrue(NotificationStore.items.first().isNotEmpty())
    }
}
