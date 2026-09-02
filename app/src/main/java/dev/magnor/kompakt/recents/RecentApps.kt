package dev.magnor.kompakt.recents

/**
 * T-042/T-043 — launch chronology over UsageStats events, shared by the
 * settings-key previous-app jump (RecentsKeyService) and the all-apps
 * switcher screen. Pure functions, unit-testable.
 */
object RecentApps {

    data class UsageEntry(val timeStamp: Long, val packageName: String)

    /**
     * Reverse-chronological distinct package order (newest first),
     * excluding [skip] (launchers/SystemUI — they pollute the chronology
     * with every home-screen visit). Our own package is NOT skipped by
     * callers: the a11y service shares it with the companion app.
     */
    fun distinctReverseChronological(entries: List<UsageEntry>, skip: Set<String>): List<String> {
        val order = ArrayList<String>(8)
        for (i in entries.indices.reversed()) {
            val p = entries[i].packageName
            if (p !in skip && p !in order) order.add(p)
        }
        return order
    }

    /**
     * The package to jump to: the app *before* the current foreground app.
     * Assumes the newest distinct entry is the current foreground (the
     * normal, on-device-verified steady state) and jumps to the next one
     * in the rotation. If the true foreground's usage event has not
     * flushed yet, the jump lands one app earlier — acceptable, and
     * exactly the behavior verified on-device 2026-09-02.
     */
    fun previousAppTarget(entries: List<UsageEntry>, skip: Set<String>): String? {
        val order = distinctReverseChronological(entries, skip)
        if (order.isEmpty()) return null
        val lastEventPkg = entries.lastOrNull()?.packageName
        return if (order.size >= 2 && lastEventPkg == order[0]) order[1] else order[0]
    }
}
