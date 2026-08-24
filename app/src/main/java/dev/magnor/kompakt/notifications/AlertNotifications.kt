package dev.magnor.kompakt.notifications

import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.ui.navigation.Routes

/**
 * Pure alert→notification logic (T-019). Everything here is unit-testable
 * without Android: the service layer only wraps it in platform calls.
 *
 * Idempotency contract (D009): the server replays unread alerts on every
 * (re)connect. Replaying the same alert must never re-buzz — notification
 * ids are a pure function of the alert id, so a replay silently replaces
 * the existing notification (or is dropped by [SeenAlerts] once per boot).
 */
object AlertNotifications {
    const val CHANNEL_ID = "alerts"
    const val EXTRA_ROUTE = "dev.magnor.kompakt.EXTRA_ROUTE"

    /** Deep-link route for a notification tap — same mapping the Inbox uses (T-018). */
    fun routeFor(item: InboxItem): String = Routes.fromInboxItem(item)

    /**
     * Stable 31-bit notification id from the alert id. Alert ids are
     * `alert:{run_id}:{episode}` — stable across replays by construction;
     * the hash just squeezes them into Android's Int id space.
     */
    fun notificationIdFor(alertId: String): Int = alertId.hashCode() and 0x7FFFFFFF
}

/**
 * Bounded seen-id memory (one service lifetime). The server's replay is
 * idempotent for *visual* state (same notif id replaces silently) but a
 * re-post can still buzz on some OEMs — the seen-set suppresses that.
 * Bounded so a long-lived process can't grow it forever; after eviction
 * the worst case is one extra silent re-post.
 */
class SeenAlerts(private val capacity: Int = 256) {
    private val seen = LinkedHashSet<String>()

    /** Returns true the first time [id] is observed; false on repeats. */
    fun firstSeen(id: String): Boolean {
        if (id in seen) return false
        seen.add(id)
        if (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}
