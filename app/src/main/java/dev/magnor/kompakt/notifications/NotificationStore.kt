package dev.magnor.kompakt.notifications

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * T-043 — in-process snapshot of the system notification stream.
 *
 * inkOS renders no notifications anywhere (T-042 forensics), but the
 * NotificationListenerService pipeline is fully functional (verified
 * 2026-09-02: bound instantly, read 16 active, live-posted test event).
 * [NotificationListener] feeds this store; the Notifications screen
 * collects it. Process-wide object by design: the listener is
 * system-instantiated (no-arg constructor) and the UI reads the same
 * snapshot — an object avoids plumbing a singleton through both.
 *
 * Dismissal flows back through the bound listener
 * ([dismiss] / [dismissAll] → `cancelNotification`); removal then arrives
 * again via onNotificationRemoved, which keeps the store consistent even
 * when the system rejects a cancel.
 */
object NotificationStore {

    data class Item(
        /** StatusBarNotification key — the cancel handle. */
        val key: String,
        val pkg: String,
        val title: String?,
        val text: String?,
        val postTimeMs: Long,
        val ongoing: Boolean,
        val dismissable: Boolean,
    )

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    /** Set by the live listener; null when unbound (dismiss then no-ops). */
    @Volatile
    private var dismisser: ((key: String) -> Unit)? = null

    fun bind(dismisser: ((key: String) -> Unit)?) {
        this.dismisser = dismisser
    }

    fun replaceAll(all: List<Item>) {
        _items.value = all.sortedByDescending { it.postTimeMs }
    }

    fun upsert(item: Item) {
        _items.value = (_items.value.filterNot { it.key == item.key } + item)
            .sortedByDescending { it.postTimeMs }
    }

    fun remove(key: String) {
        _items.value = _items.value.filterNot { it.key == key }
    }

    fun dismiss(key: String) {
        runCatching { dismisser?.invoke(key) }
            .onFailure { Log.w(TAG, "dismiss($key) failed: $it") }
    }

    fun dismissAll() {
        _items.value.filter { it.dismissable }.forEach { dismiss(it.key) }
    }

    /** Tests only. */
    fun reset() {
        _items.value = emptyList()
        dismisser = null
    }

    private const val TAG = "NotifStore"
}
