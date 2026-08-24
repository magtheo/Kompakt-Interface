package dev.magnor.kompakt.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * T-020 — protocol §5 receiver: an AlarmManager delivery becomes a
 * local notification. No state, no goAsync (posting is fast); may run
 * in a cold process, so channels are ensured here, not assumed.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        AlertPoster.ensureChannels(context)
        AlertPoster.postReminder(context, eventId, title)
    }

    companion object {
        const val EXTRA_EVENT_ID = "dev.magnor.kompakt.EXTRA_EVENT_ID"
        const val EXTRA_TITLE = "dev.magnor.kompakt.EXTRA_EVENT_TITLE"
    }
}
