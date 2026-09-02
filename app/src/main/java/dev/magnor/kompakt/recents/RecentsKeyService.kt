package dev.magnor.kompakt.recents

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * T-042 — no-root app switching on the Mudita Kompakt, via the settings key.
 *
 * inkOS has NO recents UI at all: `GLOBAL_ACTION_RECENTS` is a silent no-op
 * (returns true, nothing renders) and SystemUI has no overview component
 * (dumpsys-verified 2026-09-02). The settings key (scancode 244) never
 * becomes an Android keycode; Mudita's system daemon reads it below the key
 * layer and re-injects KEYCODE_APP_SWITCH (187) from system space — and,
 * unlike shell injections, that event *does* traverse the accessibility key
 * filter (live-verified 2026-09-02).
 *
 * So this service owns the injected 187. On press it consumes the event —
 * the quick-settings panel never opens (it stays reachable via
 * swipe-from-top) — and jumps to the previously used app. The launch
 * chronology comes from [UsageStatsManager] (PACKAGE_USAGE_STATS appop,
 * granted via ADB); app visibility via the manifest `<queries>` MAIN/LAUNCHER
 * filter (without it `getLaunchIntentForPackage` returns null on API 30+,
 * verified on-device).
 *
 * Volume keys are untouched: every event that is not 187 passes through
 * with stock behavior. On the lockscreen the settings key is also passed
 * through (stock behavior) — app switching only when interactive + unlocked.
 *
 * If no switch target is found the press falls back to GLOBAL_ACTION_RECENTS
 * (dead on inkOS — kept forward-compatible for firmware updates).
 */
class RecentsKeyService : AccessibilityService() {

    /** True when we consumed the DOWN of the current 187 press, so the
     * matching UP is swallowed too and the system never sees a stray UP. */
    private var swallowUp = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != TRIGGER_KEYCODE) return false // stock passthrough
        Log.d(TAG, "key=${event.keyCode} act=${event.action} rep=${event.repeatCount} canc=${event.isCanceled}")
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    val fire = isInteractive() && !isKeyguardLocked()
                    Log.i(TAG, "settings-key down: interactive=${isInteractive()} keyguard=${isKeyguardLocked()} fire=$fire")
                    if (fire) {
                        swallowUp = true
                        fireAppSwitch()
                    }
                }
                return swallowUp
            }
            KeyEvent.ACTION_UP -> {
                val consumed = swallowUp
                swallowUp = false
                return consumed
            }
        }
        return false
    }

    private fun fireAppSwitch() {
        val switched = launchLastApp()
        if (!switched) {
            // Dead on inkOS (no overview exists) — kept as forward-compatible
            // fallback in case a firmware update implements it.
            val ok = performGlobalAction(GLOBAL_ACTION_RECENTS)
            Log.i(TAG, "fireAppSwitch: no-target recentsReturned=$ok")
        } else {
            Log.i(TAG, "fireAppSwitch: switched=true")
        }
    }

    /**
     * Jump to the most recently used app other than the current foreground
     * app, ourselves, and launchers. Returns false when there is no target
     * or no launch intent.
     */
    private fun launchLastApp(): Boolean {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return false
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - USAGE_WINDOW_MS, now) ?: return false
        val seq = ArrayList<Pair<Long, String>>() // (timeStamp, pkg) of every resume
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                seq.add(e.timeStamp to e.packageName)
            }
        }
        // Launchers/SystemUI pollute the chronology (every home-screen visit).
        // Our own package must NOT be skipped: the a11y service shares it with
        // the companion app, and excluding it made the app unreachable via the
        // key (user-reported 2026-09-02). The "newest == current foreground"
        // branch below already prevents switching to the app you are in.
        val skip = setOf(CURRENT_LAUNCHER, STOCK_LAUNCHER, "com.android.systemui")
        // Reverse-chronological distinct app order, excluding infrastructure.
        val order = ArrayList<String>(4)
        for (i in seq.indices.reversed()) {
            val p = seq[i].second
            if (p !in skip && p !in order) order.add(p)
        }
        if (order.isEmpty()) return false
        // The newest entry is the *current* foreground app — the user wants
        // the one before it. If the last usage event has not flushed yet the
        // newest entry may already be the target; both branches land on a
        // sane choice for a two-app flip.
        val lastEventPkg = seq.lastOrNull()?.second
        val targetPkg =
            if (order.size >= 2 && lastEventPkg == order[0]) order[1] else order[0]
        val launch = packageManager.getLaunchIntentForPackage(targetPkg) ?: run {
            Log.i(TAG, "launchLastApp: no launch intent for $targetPkg")
            return false
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(launch)
            Log.i(TAG, "launchLastApp: -> $targetPkg")
            true
        }.getOrElse { t ->
            Log.w(TAG, "launchLastApp: startActivity failed for $targetPkg: $t")
            false
        }
    }

    private fun isInteractive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun isKeyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "RecentsKeyService"
        /** The daemon-injected form of the physical settings key (scancode
         *  244): APP_SWITCH crosses the a11y filter, so we can own it. */
        private const val TRIGGER_KEYCODE = KeyEvent.KEYCODE_APP_SWITCH
        private const val USAGE_WINDOW_MS = 12 * 3600_000L
        private const val CURRENT_LAUNCHER = "app.lawnchair"
        private const val STOCK_LAUNCHER = "com.mudita.launcher"
    }
}
