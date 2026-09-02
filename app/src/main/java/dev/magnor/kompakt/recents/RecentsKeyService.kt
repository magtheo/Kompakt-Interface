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
import dev.magnor.kompakt.MainActivity
import dev.magnor.kompakt.notifications.AlertNotifications
import dev.magnor.kompakt.ui.navigation.Routes

/**
 * T-042/T-043 — no-root app switching + quick surfaces on the Mudita
 * Kompakt, via the hardware keys.
 *
 * inkOS has NO recents UI (GLOBAL_ACTION_RECENTS is a silent no-op) and
 * renders NO notifications. The two keys we own:
 *
 * - **Settings key** (scancode 244 → daemon-injected keycode 187,
 *   APP_SWITCH — traverses the a11y filter, live-verified 2026-09-02):
 *   *tap* jumps to the previously used app; *hold* (≥ [HOLD_MS]) opens
 *   the all-apps switcher screen. The injected event is consumed in both
 *   cases, so the stock quick-settings panel never opens (it stays
 *   reachable via swipe-from-top).
 * - **Home key** (keycode 3): *tap* is stock home (passed through);
 *   *hold* (≥ [HOLD_MS]) opens the notifications screen — the UP is
 *   consumed so the release does not also act as a home press. If the
 *   system executes home at press-time rather than release, a long hold
 *   briefly visits the launcher before the screen opens (log-observable,
 *   cosmetic).
 *
 * Mudita sends no key repeats — hold duration is only known at release,
 * so every decision fires on key UP. The volume keys are untouched
 * (user rule: stock volume). On the lockscreen both keys pass through
 * with stock behavior. The chronology comes from [UsageStatsManager]
 * (PACKAGE_USAGE_STATS appop, granted via ADB); app visibility via the
 * manifest `<queries>` MAIN/LAUNCHER filter (without it
 * `getLaunchIntentForPackage` returns null on API 30+, verified
 * on-device).
 */
class RecentsKeyService : AccessibilityService() {

    private var settingsArmed = false
    private var settingsDownTime = 0L

    private var homeArmed = false
    private var homeDownTime = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        SETTINGS_KEYCODE -> onSettingsKey(event)
        KeyEvent.KEYCODE_HOME -> onHomeKey(event)
        else -> false // stock passthrough (volume keys, camera, …)
    }

    /**
     * Settings key: consume DOWN+UP while armed (suppresses the stock
     * quick-settings panel); fire tap = previous app, hold = switcher.
     */
    private fun onSettingsKey(event: KeyEvent): Boolean {
        Log.d(TAG, "settings act=${event.action} rep=${event.repeatCount} canc=${event.isCanceled}")
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    settingsArmed = isInteractive() && !isKeyguardLocked()
                    settingsDownTime = event.downTime
                    Log.i(TAG, "settings down: armed=$settingsArmed interactive=${isInteractive()} keyguard=${isKeyguardLocked()}")
                }
                return settingsArmed
            }
            KeyEvent.ACTION_UP -> {
                val held = event.eventTime - settingsDownTime
                if (settingsArmed && !event.isCanceled) {
                    if (held >= HOLD_MS) {
                        openScreen(Routes.APP_SWITCHER)
                    } else {
                        fireAppSwitch()
                    }
                }
                val consumed = settingsArmed
                settingsArmed = false
                return consumed
            }
        }
        return false
    }

    /**
     * Home key: everything passes through (stock home) unless the release
     * ends a hold — only the held UP is consumed, then the notifications
     * screen opens.
     */
    private fun onHomeKey(event: KeyEvent): Boolean {
        Log.d(TAG, "home act=${event.action} rep=${event.repeatCount} canc=${event.isCanceled}")
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    homeArmed = isInteractive() && !isKeyguardLocked()
                    homeDownTime = event.downTime
                    Log.i(TAG, "home down: armed=$homeArmed interactive=${isInteractive()} keyguard=${isKeyguardLocked()}")
                }
                return false
            }
            KeyEvent.ACTION_UP -> {
                val held = event.eventTime - homeDownTime
                if (homeArmed && !event.isCanceled && held >= HOLD_MS) {
                    Log.i(TAG, "home held=${held}ms → notifications")
                    openScreen(Routes.NOTIFICATIONS)
                    homeArmed = false
                    return true // swallow the release
                }
                homeArmed = false
                return false
            }
        }
        return false
    }

    /** Open a companion screen via the T-019 deep-link route extra. */
    private fun openScreen(route: String) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(AlertNotifications.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "openScreen($route) failed: $it") }
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
     * app (ourselves included in the rotation — see T-042 self-skip fix).
     * Returns false when there is no target or no launch intent.
     */
    private fun launchLastApp(): Boolean {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return false
        val now = System.currentTimeMillis()
        val entries = usageEntries(usm, now - USAGE_WINDOW_MS, now) ?: return false
        val targetPkg = RecentApps.previousAppTarget(entries, SKIP_PACKAGES) ?: return false
        return launchPackage(targetPkg)
    }

    private fun launchPackage(targetPkg: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(targetPkg) ?: run {
            Log.i(TAG, "launch: no launch intent for $targetPkg")
            return false
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(launch)
            Log.i(TAG, "launch: -> $targetPkg")
            true
        }.getOrElse { t ->
            Log.w(TAG, "launch: startActivity failed for $targetPkg: $t")
            false
        }
    }

    private fun usageEntries(
        usm: UsageStatsManager,
        fromMs: Long,
        toMs: Long,
    ): List<RecentApps.UsageEntry>? {
        val events = usm.queryEvents(fromMs, toMs) ?: return null
        val seq = ArrayList<RecentApps.UsageEntry>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                seq.add(RecentApps.UsageEntry(e.timeStamp, e.packageName))
            }
        }
        return seq
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
        private const val SETTINGS_KEYCODE = KeyEvent.KEYCODE_APP_SWITCH

        /** Tap/hold threshold for both keys. */
        private const val HOLD_MS = 500L

        private const val USAGE_WINDOW_MS = 12 * 3600_000L
        private const val CURRENT_LAUNCHER = "app.lawnchair"
        private const val STOCK_LAUNCHER = "com.mudita.launcher"

        /** Launchers/SystemUI pollute the chronology (every home visit). */
        val SKIP_PACKAGES = setOf(CURRENT_LAUNCHER, STOCK_LAUNCHER, "com.android.systemui")
    }
}
