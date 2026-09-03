package dev.magnor.kompakt.sync

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * T-044 (D032): owns the embedded WireGuard tunnel — the phone's ONLY
 * path to the server. Windowed by design: up during a sync window or
 * interactive use, down otherwise; wg is silent in between (no
 * keepalive — that silence is the battery fix).
 *
 * Config lives at files/tunnel.conf in wg-quick INI format (pushed via
 * ADB at setup; private key never enters the repo):
 *
 *     [Interface]  Address/PrivateKey
 *     [Peer]       PublicKey/AllowedIPs  (endpoint supplied per-network)
 *
 * The config file's Endpoint line (if any) is IGNORED — [EndpointPolicy]
 * decides per window which endpoint to dial, with fallback rotation.
 */
class TunnelController(private val context: Context) {

    sealed interface State {
        data object Down : State
        data object Up : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Down)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private var backend: GoBackend? = null
    private var parsed: ParsedConfig? = null

    private data class ParsedConfig(
        val privateKeyBase64: String,
        val address: String,
        val serverPublicKeyBase64: String,
        val allowedIps: String,
    )

    /** Tunnel conf present and parseable → windowed mode is armed. */
    val isConfigured: Boolean by lazy { loadParsed() != null }

    fun configFile(): File = File(context.filesDir, CONFIG_NAME)

    /**
     * VPN consent (once, first window): null = already granted, launch
     * the returned intent from a visible Activity.
     */
    fun prepareIntent(): Intent? = GoBackend.VpnService.prepare(context)

    /** True when the current default network is WiFi/Ethernet. */
    fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Bring the tunnel up. Tries endpoint candidates in order; a
     * candidate "succeeds" when the server answers HTTP on the tunnel
     * address (GoBackend UP only proves the TUN exists, not the peer).
     * Returns false (tunnel left down) if no candidate reaches the
     * server within [budgetMs].
     */
    suspend fun up(
        serverBase: String,
        budgetMs: Long = 20_000,
        now: () -> Long = { System.currentTimeMillis() },
    ): Boolean = mutex.withLock {
        val cfg = loadParsed() ?: return false
        val deadline = now() + budgetMs
        for (endpoint in EndpointPolicy.candidates(onWifi())) {
            if (now() >= deadline) break
            val ok = tryUpEndpoint(cfg, endpoint, deadline - now())
            if (ok && serverReachable(serverBase, remaining = deadline - now())) {
                _state.value = State.Up
                return true
            }
            quietlyDown()
        }
        _state.value = State.Down
        false
    }

    /** Tunnel down — always safe to call. */
    suspend fun down() = mutex.withLock {
        quietlyDown()
        _state.value = State.Down
    }

    private suspend fun quietlyDown() {
        val be = backend ?: return
        runCatching { withContext(Dispatchers.Main) { be.setState(null, Tunnel.State.DOWN, null) } }
    }

    private suspend fun tryUpEndpoint(cfg: ParsedConfig, endpoint: String, budgetMs: Long): Boolean {
        val be = backend ?: GoBackend(context).also { backend = it }
        val wgConfig = Config.Builder()
            .setInterface(
                Interface.Builder()
                    .parsePrivateKey(cfg.privateKeyBase64)
                    .parseAddresses(cfg.address)
                    .build(),
            )
            .addPeer(
                Peer.Builder()
                    .parsePublicKey(cfg.serverPublicKeyBase64)
                    .parseEndpoint(endpoint)
                    .parseAllowedIPs(cfg.allowedIps)
                    // PersistentKeepalive deliberately absent: silence
                    // between windows is the entire point (D032).
                    .build(),
            )
            .build()
        return runCatching {
            withContext(Dispatchers.Main) {
                be.setState(null, Tunnel.State.UP, wgConfig)
            }
        }.isSuccess
    }

    /**
     * Any HTTP response (even 404) proves the wg path carries traffic.
     * Handshake + RTT typically complete in <1.5 s on LAN.
     */
    private suspend fun serverReachable(serverBase: String, remaining: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (remaining <= 0) return@withContext false
            val client = OkHttpClient.Builder()
                .callTimeout(minOf(remaining, 4_000), TimeUnit.MILLISECONDS)
                .build()
            val url = runCatching { serverBase.toHttpUrl() }.getOrNull() ?: return@withContext false
            val attempts = 5
            repeat(attempts) { attempt ->
                runCatching {
                    client.newCall(Request.Builder().url(url).head().build()).execute()
                        .use { return@withContext true }
                }
                if (attempt < attempts - 1) kotlinx.coroutines.delay(700)
            }
            false
        }

    private fun loadParsed(): ParsedConfig? {
        parsed?.let { return it }
        val file = configFile()
        if (!file.exists()) return null
        val sections = splitSections(file.readText())
        val iface = sections["Interface"] ?: return null
        val peer = sections["Peer"] ?: return null
        val priv = iface["PrivateKey"]?.trim() ?: return null
        val addr = iface["Address"]?.trim() ?: return null
        val serverPub = peer["PublicKey"]?.trim() ?: return null
        val allowed = peer["AllowedIPs"]?.trim() ?: "10.127.127.1/32"
        return ParsedConfig(priv, addr, serverPub, allowed).also { parsed = it }
    }

    /** Invalidate the cached parse (config file replaced via ADB). */
    fun reload() {
        parsed = null
    }

    private fun splitSections(text: String): Map<String, Map<String, String>> {
        val out = linkedMapOf<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("[") && line.endsWith("]") -> {
                    current = linkedMapOf<String, String>()
                    out[line.removePrefix("[").removeSuffix("]")] = current
                }
                current != null && line.contains('=') -> {
                    val idx = line.indexOf('=')
                    current[line.substring(0, idx).trim()] = line.substring(idx + 1)
                }
            }
        }
        return out
    }

    private companion object {
        const val CONFIG_NAME = "tunnel.conf"
    }
}
