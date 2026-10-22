package kr.mooner510.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kr.mooner510.data.PreferencesStore

data class AutoSyncStatus(val enabled: Boolean, val foreground: Boolean, val wifi: Boolean) {
    val ready get() = enabled && foreground && wifi

    /** Why the auto download is not running, in the words the download log uses. */
    val blockReason: String?
        get() = when {
            !enabled -> "자동 저장을 껐어요"
            !foreground -> "앱이 백그라운드로 전환됨"
            !wifi -> "Wi-Fi 연결이 끊김"
            else -> null
        }
}

/**
 * Keeps the auto region ([OfflineMapManager.syncAuto]) up to date, but only while the app is on screen and
 * the default network is a validated Wi-Fi. Losing either condition stops the download at once; the region
 * remembers what is missing, so the next run continues where this one stopped.
 */
class AutoOfflineSync(
    context: Context,
    preferences: PreferencesStore,
    private val manager: OfflineMapManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val foreground = MutableStateFlow(false)
    private val wifi = MutableStateFlow(false)

    val status: StateFlow<AutoSyncStatus> = combine(
        preferences.settings.map { it.autoOfflineMaps }.distinctUntilChanged(),
        foreground,
        wifi,
    ) { enabled, fg, wf -> AutoSyncStatus(enabled, fg, wf) }
        .stateIn(scope, SharingStarted.Eagerly, AutoSyncStatus(enabled = false, foreground = false, wifi = false))

    init {
        // The default network is the one HttpURLConnection uses, so "default is Wi-Fi" means tiles go over Wi-Fi.
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    wifi.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }

                override fun onLost(network: Network) {
                    wifi.value = false
                }
            },
        )
        scope.launch {
            status.collectLatest { s ->
                if (!s.ready) {
                    s.blockReason?.let(manager::stopAuto)
                    return@collectLatest
                }
                while (true) {
                    // New points keep arriving while the app is open; pick them up periodically.
                    delay(if (manager.syncAuto()) RESYNC_INTERVAL_MS else RETRY_INTERVAL_MS)
                }
            }
        }
    }

    /** Called by the UI host as it starts and stops being visible. */
    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    private companion object {
        const val RESYNC_INTERVAL_MS = 15 * 60_000L
        const val RETRY_INTERVAL_MS = 60_000L
    }
}
