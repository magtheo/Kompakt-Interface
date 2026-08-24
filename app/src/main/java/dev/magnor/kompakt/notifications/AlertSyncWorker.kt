package dev.magnor.kompakt.notifications

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.magnor.kompakt.KompaktApplication
import dev.magnor.kompakt.data.AlertSeenStore
import dev.magnor.kompakt.data.remote.HttpApi
import dev.magnor.kompakt.domain.InboxItem
import java.util.concurrent.TimeUnit

/**
 * T-020 — protocol §4.2 fallback sync: "cursor sync is the correctness
 * mechanism, push is latency optimization only."
 *
 * When the SSE service is dead (process reclaimed, Doze, app not yet
 * opened since boot) this periodic worker re-derives notifications from
 * the same source of truth: `/v1/inbox` returns exactly the *unread*
 * items, so posting everything not yet in [AlertSeenStore] restores the
 * notification state the live stream would have produced. Alerts the
 * live path already delivered are deduped by the shared store; alerts
 * the user already read server-side simply aren't in the inbox anymore.
 *
 * Runs at most every 30 minutes, network-constrained, KEEP policy —
 * re-enrolling must not reset the cadence. Retries with backoff, gives
 * up quietly after 3 attempts (next periodic pass tries again anyway).
 */
class AlertSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? KompaktApplication ?: return Result.success()
        val container = app.container
        // Fake mode / not enrolled: nothing to poll.
        val api = container.remoteApi() ?: return Result.success()
        return try {
            AlertPoster.ensureChannels(applicationContext)
            fetchAndPostUnseen(api, container.alertSeenStore) {
                AlertPoster.postAlert(applicationContext, it)
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // never swallow cancellation — WorkManager stop must win
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "alert-fallback-sync"
        private const val MAX_ATTEMPTS = 3

        /** Idempotent periodic enqueue — call from Application.onCreate. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AlertSyncWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}

/**
 * Core fallback-sync step, free of Android context so it unit-tests
 * against a mock server: fetch unread inbox, post everything the seen
 * store hasn't observed yet. Returns the number newly posted.
 */
internal suspend fun fetchAndPostUnseen(
    api: HttpApi,
    seenStore: AlertSeenStore,
    post: (InboxItem) -> Unit,
): Int {
    val items = api.decodeList<InboxItem>("/v1/inbox", "inbox_items")
    var posted = 0
    for (item in items) {
        if (seenStore.firstSeen(item.id)) {
            post(item)
            posted++
        }
    }
    return posted
}
