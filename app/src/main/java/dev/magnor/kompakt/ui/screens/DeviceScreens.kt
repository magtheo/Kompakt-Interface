package dev.magnor.kompakt.ui.screens

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import dev.magnor.kompakt.notifications.NotificationStore
import dev.magnor.kompakt.recents.RecentsKeyService
import dev.magnor.kompakt.recents.RecentApps
import dev.magnor.kompakt.ui.relativeTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

/**
 * T-043 — the notification surface inkOS never had. Hold HOME opens this.
 *
 * Pull-not-push by design (quiet-phone philosophy): the list is read from
 * the live listener snapshot; a row tap opens the source app, "Clear"
 * dismisses every dismissable notification. Ongoing/system notifications
 * (Tailscale VPN, our own SSE "listening" notice, …) stay until their
 * owner removes them. No VM: the store is a process-wide StateFlow and
 * the row logic is trivial — state lives in [NotificationStore] and the
 * pure parts are unit-tested there.
 */
@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    val items by NotificationStore.items.collectAsState()
    val context = LocalContext.current
    val labels = remember(items.map { it.pkg }.toSet()) {
        items.map { it.pkg }.associateWith { context.appLabel(it) }
    }
    val nowMs = remember(items) { System.currentTimeMillis() }

    AppScreen(
        title = "Notifications",
        onBack = onBack,
        actions = {
            if (items.any { it.dismissable }) {
                OutlinedButtonMMD(onClick = { NotificationStore.dismissAll() }) {
                    TextMMD("Clear")
                }
            }
        },
    ) {
        if (items.isEmpty()) {
            TextMMD("No notifications")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    ListRow(
                        title = item.title ?: labels[item.pkg] ?: item.pkg,
                        subtitle = listOfNotNull(
                            labels[item.pkg],
                            Instant.fromEpochMilliseconds(item.postTimeMs)
                                .relativeTo(Instant.fromEpochMilliseconds(nowMs)),
                            if (item.ongoing) "ongoing" else null,
                        ).joinToString(" · "),
                        onClick = { context.launchApp(item.pkg) },
                    )
                }
            }
        }
    }
}

/**
 * T-043 — the app switcher inkOS never had. Hold the settings key opens
 * this. Same chronology as the previous-app jump ([RecentsKeyService]),
 * rendered as a list: newest-used first, tap a row → that app. E-ink
 * rules: static list, no motion, monochrome.
 */
@Composable
fun AppSwitcherScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<SwitcherRow>?>(null) }

    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.Default) { loadSwitcherRows(context) }
    }

    AppScreen(title = "Apps", onBack = onBack) {
        val current = rows
        when {
            current == null -> TextMMD("Loading…")
            current.isEmpty() ->
                TextMMD(
                    "No recent apps. " +
                        "(Empty list usually means the usage-access grant was lost — " +
                        "re-run the ADB appops grant.)"
                )
            else ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    current.forEach { row ->
                        ListRow(
                            title = row.label,
                            trailing = Instant.fromEpochMilliseconds(row.lastUsedMs)
                                .relativeTo(Instant.fromEpochMilliseconds(System.currentTimeMillis())),
                            onClick = { context.launchApp(row.pkg) },
                        )
                    }
                }
        }
    }
}

private data class SwitcherRow(val pkg: String, val label: String, val lastUsedMs: Long)

private fun loadSwitcherRows(context: Context): List<SwitcherRow> = runCatching {
    val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
    val now = System.currentTimeMillis()
    val events = usm.queryEvents(now - 12 * 3600_000L, now) ?: return emptyList()
    val entries = ArrayList<RecentApps.UsageEntry>()
    val e = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(e)
        if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
            entries.add(RecentApps.UsageEntry(e.timeStamp, e.packageName))
        }
    }
    val lastUsed = entries.groupBy({ it.packageName }, { it.timeStamp })
        .mapValues { (_, ts) -> ts.max() }
    RecentApps
        .distinctReverseChronological(entries, RecentsKeyService.SKIP_PACKAGES)
        .mapNotNull { pkg ->
            lastUsed[pkg]?.let { SwitcherRow(pkg, context.appLabel(pkg), it) }
        }
}.getOrElse { emptyList() }

private fun Context.appLabel(pkg: String): String = runCatching {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

private fun Context.launchApp(pkg: String) {
    val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(launch) }
}
