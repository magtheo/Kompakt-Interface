package dev.magnor.kompakt

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.magnor.kompakt.data.AppContainer
import dev.magnor.kompakt.data.ConnectivityWatcher
import dev.magnor.kompakt.data.FlushPolicy
import dev.magnor.kompakt.data.ThemeStore
import dev.magnor.kompakt.data.security.KeystoreSecretVault
import dev.magnor.kompakt.sync.SyncWindowScheduler
import dev.magnor.kompakt.sync.TunnelController
import dev.magnor.kompakt.notifications.AlertStreamService
import dev.magnor.kompakt.notifications.AlertSyncWorker
import dev.magnor.kompakt.notifications.installReminderPlanning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Process-lifetime owner of the dependency container (manual DI). */
class KompaktApplication : Application() {

    /**
     * T-044 (D032): the embedded wg tunnel — the phone's only path to
     * the server. Present in every process; only the main process ever
     * calls up()/down().
     */
    val tunnelController: TunnelController by lazy { TunnelController(this) }

    val container: AppContainer by lazy {
        AppContainer(
            secretVault = KeystoreSecretVault(this),
            captureQueueDir = java.io.File(filesDir, "captures"),
            themeStore = ThemeStore(java.io.File(filesDir, "theme.txt")),
            tunnelConfigured = tunnelController.isConfigured,
        )
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * T-045: the a11y key service runs in its own process (:keys). The
     * application shell — fallback sync worker, reminder planning,
     * connectivity watcher, alert FGS — must boot exactly once, in the
     * main process only.
     */
    private val isMainProcess: Boolean by lazy {
        val manager = getSystemService(ActivityManager::class.java) ?: return@lazy true
        val pid = android.os.Process.myPid()
        manager.runningAppProcesses?.firstOrNull { it.pid == pid }
            ?.processName == packageName
    }

    override fun onCreate() {
        super.onCreate()
        // T-045 diagnostics: every uncaught exception gets a full logcat
        // trace (tag KompaktCrash) before the system handler runs —
        // coroutine stacks otherwise hide the launching call site.
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            android.util.Log.e("KompaktCrash", "uncaught on thread ${t.name}", e)
            systemHandler?.uncaughtException(t, e)
        }
        if (!isMainProcess) return
        val tunnelMode = tunnelController.isConfigured
        if (tunnelMode) {
            // T-044 (D032): windowed sync replaces the always-on stack —
            // 15-min exact-alarm windows + interactive windows on app use.
            // The SSE fallback worker is retired in tunnel mode (windows
            // ARE the fallback path now).
            SyncWindowScheduler.scheduleNext(this)
            androidx.work.WorkManager.getInstance(this)
                .cancelUniqueWork(AlertSyncWorker.UNIQUE_NAME)
        } else {
            // T-020 — protocol §4.2: periodic fallback sync whenever the live
            // SSE path is down (KEEP = enrollment flips never reset cadence).
            AlertSyncWorker.schedule(this)
        }
        // T-020 — protocol §5: every Today projection refresh re-derives
        // local reminders (remote mode only, guarded inside the hook).
        installReminderPlanning(this)
        // Phase 9 — auto-flush parked captures the moment the default
        // network is usable again; no app restart needed. Callbacks hit
        // a connectivity thread; tryFlush hops to the container scope.
        val policy = FlushPolicy(now = { SystemClock.elapsedRealtime() })
        ConnectivityWatcher(this).start {
            if (policy.shouldAttempt(container.pendingCaptures.count.value)) {
                container.tryFlushPendingCaptures()
            }
        }
        // T-019 — the alert stream (FGS) lives exactly as long as remote
        // mode does: enroll/deactivate flips this flow, we follow it.
        // T-044: in tunnel mode the SSE stream is retired — windows are
        // the alert path (live pull happens inside interactive windows).
        appScope.launch {
            container.remoteActiveFlow.collect { active ->
                val intent = Intent(this@KompaktApplication, AlertStreamService::class.java)
                if (active && !tunnelMode) {
                    ContextCompat.startForegroundService(this@KompaktApplication, intent)
                } else {
                    stopService(intent)
                }
            }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }
}
