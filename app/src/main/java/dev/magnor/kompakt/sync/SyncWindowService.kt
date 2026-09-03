package dev.magnor.kompakt.sync

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.magnor.kompakt.KompaktApplication
import dev.magnor.kompakt.R
import dev.magnor.kompakt.notifications.AlertPoster
import dev.magnor.kompakt.notifications.fetchAndPostUnseen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * T-044 (D032): the sync-window foreground service — the ONLY thing that
 * brings the tunnel up.
 *
 * Two modes:
 * - BACKGROUND (from the 15-min alarm): one window — up, burst, down,
 *   stopSelf. Notifications that arrived since the last window are
 *   posted here (the user reads them on the hold-HOME screen).
 * - INTERACTIVE (from MainActivity onStart while enrolled): tunnel up
 *   and HELD for interactive use (chat needs round-trips); onStop sends
 *   ACTION_STOP → tunnel down, service ends.
 *
 * Quiet by design: the foreground notification is LOW priority, no
 * sound/vibration (e-ink, D008/D009 — the app is its own client).
 */
class SyncWindowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AlertPoster.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(QUIET_ID, quietNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(QUIET_ID, quietNotification())
        }
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    (application as KompaktApplication).tunnelController.down()
                    stopSelf()
                }
            }
            ACTION_INTERACTIVE -> {
                // Interactive window: hold the tunnel while the app is used.
                scope.launch { (application as KompaktApplication).tunnelController.up(effectiveBase()) }
            }
            else -> {
                // Background window: one orchestrated burst.
                scope.launch {
                    val app = application as KompaktApplication
                    val container = app.container
                    val result = SyncWindowOrchestrator(
                        tunnelUp = { app.tunnelController.up(effectiveBase()) },
                        burst = { runBurst(container) },
                        down = { app.tunnelController.down() },
                    ).runWindow()
                    android.util.Log.d(TAG, "window result: $result")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun effectiveBase(): String =
        (application as KompaktApplication).container.let { c ->
            c.enrollment.activeBaseUrl()?.let { base ->
                TransportPolicy.resolve(base, tunnelConfigured = true)
            } ?: "http://${TransportPolicy.TUNNEL_HOST}:8650"
        }

    /** Window burst: pull unseen inbox + flush parked captures. */
    private suspend fun runBurst(container: dev.magnor.kompakt.data.AppContainer) {
        val api = container.remoteApi() ?: return
        fetchAndPostUnseen(
            api,
            container.alertSeenStore,
            post = { AlertPoster.postAlert(applicationContext, it) },
        )
        container.flushPendingCaptures()
    }

    private fun quietNotification(): Notification =
        NotificationCompat.Builder(this, AlertPoster.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle("Kompakt")
            .setContentText("Sync window")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(null)
            .build()

    override fun onDestroy() {
        // Process death mid-window: the alarm re-arms the next one; a
        // stuck-up tunnel is cleaned by wg timeout + next window's down().
        (application as? KompaktApplication)?.let { app ->
            scope.launch { runCatching { app.tunnelController.down() } }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SyncWindow"
        const val QUIET_ID = 2
        const val ACTION_INTERACTIVE = "dev.magnor.kompakt.sync.INTERACTIVE"
        const val ACTION_STOP = "dev.magnor.kompakt.sync.STOP"

        fun startBackground(context: Context) {
            context.startForegroundService(
                Intent(context, SyncWindowService::class.java),
            )
        }

        fun startInteractive(context: Context) {
            context.startForegroundService(
                Intent(context, SyncWindowService::class.java).setAction(ACTION_INTERACTIVE),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SyncWindowService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
