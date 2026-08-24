package dev.magnor.kompakt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import dev.magnor.kompakt.notifications.AlertNotifications
import dev.magnor.kompakt.ui.KompaktApp
import dev.magnor.kompakt.ui.theme.KompaktTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * Route requested outside the UI (T-019 notification tap). The UI
     * consumes + clears it after navigating — one-shot by contract.
     */
    private val pendingRoute = MutableStateFlow<String?>(null)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: stream runs regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KompaktApplication).container
        pendingRoute.value = intent?.routeExtra()
        requestNotificationPermissionOnce()
        setContent {
            val polarity by container.themeStore.polarity.collectAsState()
            val route by pendingRoute.collectAsState()
            KompaktTheme(polarity = polarity) {
                KompaktApp(container, launchRoute = route) { pendingRoute.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Activity alive, notification tapped with SINGLE_TOP → route here.
        intent.routeExtra()?.let { pendingRoute.value = it }
    }

    private fun Intent.routeExtra(): String? =
        getStringExtra(AlertNotifications.EXTRA_ROUTE)?.takeIf { it.isNotBlank() }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
