package dev.magnor.kompakt

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.magnor.kompakt.data.AppContainer
import dev.magnor.kompakt.data.ConnectivityWatcher
import dev.magnor.kompakt.data.FlushPolicy
import dev.magnor.kompakt.data.ThemeStore
import dev.magnor.kompakt.data.security.KeystoreSecretVault
import dev.magnor.kompakt.notifications.AlertStreamService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Process-lifetime owner of the dependency container (manual DI). */
class KompaktApplication : Application() {
    val container: AppContainer by lazy {
        AppContainer(
            secretVault = KeystoreSecretVault(this),
            captureQueueDir = java.io.File(filesDir, "captures"),
            themeStore = ThemeStore(java.io.File(filesDir, "theme.txt")),
        )
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
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
        appScope.launch {
            container.remoteActiveFlow.collect { active ->
                val intent = Intent(this@KompaktApplication, AlertStreamService::class.java)
                if (active) {
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
