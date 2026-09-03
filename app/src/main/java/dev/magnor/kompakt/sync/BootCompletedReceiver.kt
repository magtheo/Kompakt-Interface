package dev.magnor.kompakt.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * T-044: alarms die at reboot — re-arm the 15-min sync window cadence.
 * Tunnel state itself never persists a reboot; the first window after
 * boot brings everything back.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        SyncWindowScheduler.scheduleNext(context)
    }
}
