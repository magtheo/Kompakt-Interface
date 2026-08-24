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
import dev.magnor.kompakt.R
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.InboxPriority
import dev.magnor.kompakt.ui.navigation.Routes

/**
 * T-020 — the one place notifications get posted, shared by every
 * delivery path (D008 "one internal update layer"):
 *
 *  - AlertStreamService  (foreground SSE, T-019)
 *  - AlertSyncWorker     (periodic fallback, protocol §4.2)
 *  - ReminderReceiver    (local alarms, protocol §5)
 *
 * Alert notification ids are a pure function of the alert id
 * [AlertNotifications.notificationIdFor], so two paths racing to post
 * the same alert silently replace each other — never duplicate.
 */
object AlertPoster {

    const val CHANNEL_CONNECTION = "connection"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_ALERTS_HIGH = "alerts_high"
    const val CHANNEL_REMINDERS = "reminders"

    /** Stable id space for reminder notifications (event-id-derived). */
    fun reminderNotificationId(eventId: String): Int =
        (eventId.hashCode() xor 0x5a5a5a5a) and 0x7FFFFFFF

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONNECTION, "Connection", NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS_HIGH, "Urgent alerts", NotificationManager.IMPORTANCE_HIGH
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    /**
     * Post one alert notification. Never throws when notifications are
     * denied (API 33+ runtime revocation) — background paths must no-op.
     */
    fun postAlert(context: Context, item: InboxItem) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val channel =
            if (item.priority == InboxPriority.HIGH) CHANNEL_ALERTS_HIGH else CHANNEL_ALERTS
        try {
            nm.notify(AlertNotifications.notificationIdFor(item.id), alertNotification(context, item, channel))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between check and post — no-op.
        }
    }

    /** Local reminder (protocol §5) — calendar event starting now. */
    fun postReminder(context: Context, eventId: String, title: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(reminderNotificationId(eventId), reminderNotification(context, eventId, title))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between check and post — no-op.
        }
    }

    private fun tapIntent(context: Context, requestCode: Int, route: String): PendingIntent {
        val tap = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AlertNotifications.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alertNotification(context: Context, item: InboxItem, channel: String): Notification {
        val id = AlertNotifications.notificationIdFor(item.id)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(item.title)
            .setContentText(item.summary ?: "")
            .setPriority(
                if (item.priority == InboxPriority.HIGH) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true) // tap clears it
            .setContentIntent(tapIntent(context, id, AlertNotifications.routeFor(item)))
            .build()
    }

    private fun reminderNotification(context: Context, eventId: String, title: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(title)
            .setContentText("Starting now")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, reminderNotificationId(eventId), Routes.TODAY))
            .build()
}
