package dev.magnor.kompakt.data

/**
 * Rate-limits connectivity-triggered offline-queue flushes (Phase 9).
 *
 * Pure logic — ConnectivityWatcher feeds it events from the Android
 * connectivity thread, so the decision rules live here where they are
 * unit-testable:
 *
 *  - never attempt when the queue is empty (callbacks fire constantly
 *    during link flap / VPN switches)
 *  - at most one attempt per [minIntervalMs] (Wi-Fi ↔ cell handovers
 *    burst; flushes are idempotent server-side, so skipping is safe)
 */
class FlushPolicy(
    private val now: () -> Long,
    private val minIntervalMs: Long = 5_000,
) {
    private var lastAttemptAt: Long? = null

    fun shouldAttempt(queueDepth: Int): Boolean {
        if (queueDepth == 0) return false
        val t = now()
        val last = lastAttemptAt
        if (last != null && t - last < minIntervalMs) return false
        lastAttemptAt = t
        return true
    }
}
