package dev.magnor.kompakt.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.magnor.kompakt.MainActivity
import dev.magnor.kompakt.KompaktApplication
import dev.magnor.kompakt.R
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.InboxPriority
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
 * each *alert* posts its own high-visibility notification.
 *
 * Lifecycle: KompaktApplication starts/stops this service as remote
 * mode activates/deactivates; it holds no state of its own beyond the
 * collector job and the seen-id set.
 */
class AlertStreamService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val seen = SeenAlerts()

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            // FOREGROUND_SERVICE_TYPE_SPECIAL_USE — literal 0x40000000.
            // Below 34 the constant doesn't exist and unknown bits throw,
            // so the untyped 2-arg form carries those versions (the
            // manifest attribute is simply ignored there).
            startForeground(QUIET_ID, quietNotification(), 0x40000000)
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
        if (!seen.firstSeen(item.id)) return
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val channel = if (item.priority == InboxPriority.HIGH) CHANNEL_ALERTS_HIGH else CHANNEL_ALERTS
        nm.notify(
            AlertNotifications.notificationIdFor(item.id),
            alertNotification(item, channel),
        )
    }

    private fun alertNotification(item: InboxItem, channel: String): Notification {
        val tap = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AlertNotifications.EXTRA_ROUTE, AlertNotifications.routeFor(item))
        }
        val pending = PendingIntent.getActivity(
            this,
            AlertNotifications.notificationIdFor(item.id),
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(item.title)
            .setContentText(item.summary ?: "")
            .setPriority(
                if (item.priority == InboxPriority.HIGH) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true) // tap clears it
            .setContentIntent(pending)
            .build()
    }

    private fun quietNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_QUIET)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle("Kompakt")
            .setContentText("Listening for alerts")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(null)
            .build()

    private fun startChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, "Connection", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS_HIGH, "Urgent alerts", NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_QUIET = "connection"
        private const val CHANNEL_ALERTS = "alerts"
        private const val CHANNEL_ALERTS_HIGH = "alerts_high"
        private const val QUIET_ID = 1
    }
}
