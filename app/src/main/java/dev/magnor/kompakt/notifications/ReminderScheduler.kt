package dev.magnor.kompakt.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.magnor.kompakt.KompaktApplication
import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.KompaktJson
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * T-020 — protocol §5 executor: turns a Today projection's calendar
 * events into AlarmManager alarms ([ReminderPlanner] decides *what* and
 * *when*; this class only *does*).
 *
 * Full-replan semantics: each replan re-derives the complete plan and
 * cancels alarms for events that dropped out of it. PendingIntent
 * request codes are [AlertPoster.reminderNotificationId]-derived, so a
 * replan for the same event replaces its pending alarm (never stacks).
 *
 * Exact alarms need SCHEDULE_EXACT_ALARM (denied by default on API 33+
 * for sideloaded apps); we degrade to inexact setAndAllowWhileIdle —
 * Doze defers it a few minutes at worst, acceptable for a 10-minute
 * lead reminder.
 *
 * Persisted state is just the planned event-id set (stale-cancel
 * bookkeeping across process death).
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    @Serializable
    private data class Planned(val ids: List<String>)

    fun replan(events: List<CalendarEvent>, now: Instant = Clock.System.now()) {
        val plan = ReminderPlanner.plan(events, now).associateBy { it.eventId }
        plan.values.forEach { arm(it.eventId, it.title, it.triggerAt) }
        // Cancel alarms whose event left the plan (deleted/cancelled/moved past).
        (readPlanned() - plan.keys).forEach(::cancel)
        writePlanned(plan.keys)
    }

    private fun arm(eventId: String, title: String, triggerAt: Instant) {
        val at = triggerAt.toEpochMilliseconds()
        val pi = pendingIntent(eventId, title)
        val exactAllowed =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()
        if (exactAllowed) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancel(eventId: String) {
        alarmManager.cancel(pendingIntent(eventId, ""))
    }

    private fun pendingIntent(eventId: String, title: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            AlertPoster.reminderNotificationId(eventId),
            Intent(context, ReminderReceiver::class.java)
                .putExtra(ReminderReceiver.EXTRA_EVENT_ID, eventId)
                .putExtra(ReminderReceiver.EXTRA_TITLE, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stateFile(): File = File(context.filesDir, STATE_NAME)

    private fun readPlanned(): Set<String> {
        val file = stateFile()
        if (!file.exists()) return emptySet()
        return runCatching {
            KompaktJson.decodeFromString<Planned>(file.readText()).ids.toSet()
        }.getOrDefault(emptySet())
    }

    private fun writePlanned(ids: Set<String>) {
        val file = stateFile()
        val tmp = File(context.filesDir, "$STATE_NAME.tmp")
        tmp.writeText(KompaktJson.encodeToString(Planned(ids.toList())))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val STATE_NAME = "planned-reminders.json"
    }
}

/**
 * Wiring hook: KompaktApplication installs this on the container so the
 * Today projection refresh (any source) re-derives local reminders —
 * but only for remote mode; fake-mode projections must not touch
 * system alarm state.
 */
fun installReminderPlanning(app: KompaktApplication) {
    val scheduler = ReminderScheduler(app)
    app.container.onTodayLoaded = { projection ->
        if (app.container.remoteActive) {
            scheduler.replan(projection.events)
        }
    }
}
