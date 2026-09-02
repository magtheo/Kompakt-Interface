package dev.magnor.kompakt.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * T-043 — system notification read access on the Mudita Kompakt.
 *
 * inkOS SystemUI renders NO notifications anywhere (no shade area, no
 * heads-up, lockscreen = clock) — but posting works (users hear sounds)
 * and the listener pipeline is fully functional (live-verified
 * 2026-09-02: bound instantly, 16 active read, shell-posted test event
 * received with title/text intact). Third-party failures were the apps'
 * own: Lawnchair's listener never binds (beta bug, this device);
 * BubbleNotice binds but fails in its rendering layer.
 *
 * This class mirrors the active set into [NotificationStore] for the
 * hold-HOME notifications screen and owns dismissal (cancel flows through
 * the listener binding). All logs at Log.i — the MediaTek `log.tag=I`
 * default gate silently drops D/ lines device-wide (T-042 lesson).
 */
class NotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        val active = try {
            activeNotifications?.also { sbns ->
                NotificationStore.replaceAll(sbns.map { it.toItem() })
            }?.size ?: -1
        } catch (t: Throwable) {
            Log.w(TAG, "connected: activeNotifications threw $t")
            -2
        }
        NotificationStore.bind { key -> runCatching { cancelNotification(key) } }
        Log.i(TAG, "connected: active=$active")
    }

    override fun onListenerDisconnected() {
        NotificationStore.bind(null)
        Log.i(TAG, "disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationStore.upsert(sbn.toItem())
        val item = sbn.toItem()
        Log.i(TAG, "posted: pkg=${sbn.packageName} id=${sbn.id} title=\"${item.title}\"")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationStore.remove(sbn.key)
        Log.i(TAG, "removed: pkg=${sbn.packageName} id=${sbn.id}")
    }

    private fun StatusBarNotification.toItem(): NotificationStore.Item {
        val extras = notification?.extras
        val flags = notification?.flags ?: 0
        return NotificationStore.Item(
            key = key,
            pkg = packageName,
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            postTimeMs = postTime,
            ongoing = isOngoing,
            dismissable = flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR) == 0,
        )
    }

    companion object {
        private const val TAG = "NotifListener"
    }
}
