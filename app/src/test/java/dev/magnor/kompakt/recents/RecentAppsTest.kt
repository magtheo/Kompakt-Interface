package dev.magnor.kompakt.recents

import dev.magnor.kompakt.recents.RecentApps.UsageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-042/T-043 — chronology rules for the previous-app jump and the
 * all-apps switcher. Semantics locked by on-device verification
 * 2026-09-02 ("works flawlessly"); regressions here would change which
 * app a keypress lands on.
 */
class RecentAppsTest {

    private val skip = setOf("app.lawnchair", "com.mudita.launcher", "com.android.systemui")

    @Test
    fun `distinct reverse chronological, newest first`() {
        val entries = listOf(
            UsageEntry(1_000, "com.example.a"),
            UsageEntry(2_000, "app.lawnchair"),
            UsageEntry(3_000, "com.example.b"),
            UsageEntry(4_000, "com.example.a"), // revisit → stays first position
            UsageEntry(5_000, "com.android.systemui"),
        )
        assertEquals(
            listOf("com.example.a", "com.example.b"),
            RecentApps.distinctReverseChronological(entries, skip),
        )
    }

    @Test
    fun `previous app targets the app before current foreground`() {
        // a → b → currently in a: newest raw entry is a (foreground),
        // target = the app before it = b.
        val entries = listOf(
            UsageEntry(1_000, "com.example.a"),
            UsageEntry(2_000, "com.example.b"),
            UsageEntry(3_000, "com.example.a"),
        )
        assertEquals("com.example.b", RecentApps.previousAppTarget(entries, skip))
    }

    @Test
    fun `current foreground equals newest distinct app`() {
        // Normal steady state: a → b, currently in b (flushed). Newest raw
        // entry is b = order[0] = foreground → jump to the app before it.
        val entries = listOf(
            UsageEntry(1_000, "com.example.a"),
            UsageEntry(2_000, "com.example.b"),
        )
        assertEquals("com.example.a", RecentApps.previousAppTarget(entries, skip))
    }

    @Test
    fun `single app yields itself`() {
        val entries = listOf(UsageEntry(1_000, "com.example.a"))
        assertEquals("com.example.a", RecentApps.previousAppTarget(entries, skip))
    }

    @Test
    fun `empty input yields null`() {
        assertNull(RecentApps.previousAppTarget(emptyList(), skip))
        assertEquals(emptyList<String>(), RecentApps.distinctReverseChronological(emptyList(), skip))
    }

    @Test
    fun `own package stays in the rotation`() {
        // T-042 self-skip fix: dev.magnor.kompakt must NOT be skipped —
        // the a11y service shares its package with the companion app.
        val entries = listOf(
            UsageEntry(1_000, "com.example.a"),
            UsageEntry(2_000, "dev.magnor.kompakt"),
            UsageEntry(3_000, "com.example.a"),
        )
        assertEquals("dev.magnor.kompakt", RecentApps.previousAppTarget(entries, skip))
    }
}
