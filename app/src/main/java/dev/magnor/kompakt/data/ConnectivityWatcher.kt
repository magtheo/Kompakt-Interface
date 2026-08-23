package dev.magnor.kompakt.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Phase 9 — fires when the default network looks usable again.
 *
 * Triggers on both [ConnectivityManager.NetworkCallback.onAvailable] and
 * NET_CAPABILITY_VALIDATED (captive-portal check passed): on VPN setups
 * (Tailscale) either may arrive first, and a premature trigger is
 * harmless — the flush stops at the first transport failure and the
 * FlushPolicy rate-limits repeats.
 *
 * Callbacks arrive on a connectivity thread; [onValidated] must hop
 * threads itself. Registered for the process lifetime by design — the
 * watcher outlives every screen, so there is no unregister path.
 */
class ConnectivityWatcher(context: Context) {

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    fun start(onValidated: () -> Unit) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onValidated()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    onValidated()
                }
            }
        }
        connectivityManager?.registerDefaultNetworkCallback(callback)
    }
}
