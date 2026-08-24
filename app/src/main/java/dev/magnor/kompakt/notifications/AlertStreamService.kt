package dev.magnor.kompakt.notifications

import android.app.Notification
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.magnor.kompakt.KompaktApplication
import dev.magnor.kompakt.R
import dev.magnor.kompakt.domain.InboxItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * T-019 — the app IS the notification client (D008/D009).
 *
 * specialUse foreground service holding the SSE alert stream open.
 * Sideloaded, de-Googled, Tailscale-only: no FCM (and its 6-hour
 * dataSync cap on API 34+) — a persistent socket is the whole point.
 * The foreground notification is deliberately quiet (LOW importance);
 * each *alert* posts its own notification via the shared [AlertPoster].
 *
 * Lifecycle: KompaktApplication starts/stops this service as remote
 * mode activates/deactivates; it holds no state of its own beyond the
 * collector job and the seen-id set.
 */
class AlertStreamService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AlertPoster.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= 34) {
            // Compile-time constant — inlined by kotlinc, so referencing
            // it never touches the (34+-only) field at runtime on 29–33.
            startForeground(QUIET_ID, quietNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(QUIET_ID, quietNotification())
        }
        if (job == null) {
            job = scope.launch {
                (application as KompaktApplication).container.alertTransport
                    ?.alerts()
                    ?.collect { item -> onAlert(item) }
            }
        }
        return START_STICKY
    }

    private fun onAlert(item: InboxItem) {
        // Shared persisted dedupe (T-020): the fallback worker may deliver
        // the same alert — whichever path sees it first wins, the other no-ops.
        val seen = (application as KompaktApplication).container.alertSeenStore
        if (!seen.firstSeen(item.id)) return
        AlertPoster.postAlert(this, item)
    }

    private fun quietNotification(): Notification =
        NotificationCompat.Builder(this, AlertPoster.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle("Kompakt")
            .setContentText("Listening for alerts")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(null)
            .build()

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val QUIET_ID = 1
    }
}
