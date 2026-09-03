package dev.magnor.kompakt.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * T-044: fires every WINDOW_MS while enrolled in tunnel mode. One job:
 * start the background sync window, then schedule the next tick.
 *
 * Android-12 FGS-from-receiver note (spike open question): if
 * startForegroundService throws here, we log the verdict loudly and
 * skip — the deploy grants SYSTEM_ALERT_WINDOW via appops, which is on
 * the API-31 FGS-start exemption list. First real window on-device
 * settles it.
 */
class SyncWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val am = context.getSystemService(AlarmManager::class.java)
        val canExact = am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()
        try {
            SyncWindowService.startBackground(context)
            android.util.Log.d(TAG, "window started (exact=$canExact)")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException expected without
            // the SAW exemption — this log line is the on-device verdict.
            android.util.Log.e(TAG, "FGS-from-receiver BLOCKED: ${e.javaClass.simpleName}: ${e.message}")
        }
        SyncWindowScheduler.scheduleNext(context)
    }

    private companion object {
        const val TAG = "SyncWindowRx"
    }
}

/**
 * Alarm bookkeeping. Exact alarms are auto-granted on the Android-12
 * device (SCHEDULE_EXACT_ALARM only became denied-by-default on 33+);
 * degrade to inexact otherwise — Doze stretches a 15-min tick to
 * ~9–15 min maintenance windows, acceptable for notifications.
 */
object SyncWindowScheduler {
    const val WINDOW_MS = 15L * 60 * 1000
    private const val REQUEST_CODE = 4444

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, SyncWindowReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun scheduleNext(context: Context, delayMs: Long = WINDOW_MS) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + delayMs
        val canExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }
}
