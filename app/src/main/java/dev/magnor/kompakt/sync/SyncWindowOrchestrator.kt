package dev.magnor.kompakt.sync

import kotlinx.coroutines.withTimeoutOrNull

/**
 * T-044: one sync window, as a pure, context-free orchestration so it
 * unit-tests without Android:
 *
 *     tunnel up → burst (alerts + captures; interactive use keeps the
 *     window open instead) → tunnel down (ALWAYS, success or not)
 *
 * Missed/failed windows are fine by design: state is cursors and
 * queues, the next window catches up. No exception escapes — a window
 * either Syncs or reports Offline, and the tunnel never outlives its
 * window (battery rule, D032).
 */
class SyncWindowOrchestrator(
    private val tunnelUp: suspend () -> Boolean,
    private val burst: suspend () -> Unit,
    private val down: suspend () -> Unit,
) {

    sealed interface Result {
        /** Tunnel up, server reachable, burst ran. */
        data object Synced : Result

        /** No endpoint reached the server / burst failed within budget. */
        data object Offline : Result
    }

    suspend fun runWindow(totalBudgetMs: Long = 60_000): Result {
        val up = runCatching {
            withTimeoutOrNull(totalBudgetMs / 2) { tunnelUp() }
        }.getOrNull() == true
        if (!up) {
            withTimeoutOrNull(10_000) { runCatching { down() } }
            return Result.Offline
        }
        try {
            val burstDone = runCatching {
                withTimeoutOrNull(totalBudgetMs / 2) { burst(); true }
            }.getOrNull() == true
            return if (burstDone) Result.Synced else Result.Offline
        } finally {
            withTimeoutOrNull(10_000) { runCatching { down() } }
        }
    }
}
