package dev.magnor.kompakt

import android.app.Application
import android.os.SystemClock
import dev.magnor.kompakt.data.AppContainer
import dev.magnor.kompakt.data.ConnectivityWatcher
import dev.magnor.kompakt.data.FlushPolicy
import dev.magnor.kompakt.data.security.KeystoreSecretVault

/** Process-lifetime owner of the dependency container (manual DI). */
class KompaktApplication : Application() {
    val container: AppContainer by lazy {
        AppContainer(
            secretVault = KeystoreSecretVault(this),
            captureQueueDir = java.io.File(filesDir, "captures"),
        )
    }

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
    }
}
