package kr.mooner510.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the default network is a validated Wi-Fi. The default network is the one HttpURLConnection uses, so
 * "true" means map downloads go over Wi-Fi rather than mobile data.
 */
class WifiMonitor(context: Context) {
    private val _wifi = MutableStateFlow(false)
    val wifi: StateFlow<Boolean> = _wifi.asStateFlow()

    init {
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _wifi.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }

                override fun onLost(network: Network) {
                    _wifi.value = false
                }
            },
        )
    }
}
