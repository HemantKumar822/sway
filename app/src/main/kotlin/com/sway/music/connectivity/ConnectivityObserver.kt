package com.sway.music.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide connectivity truth (story 9.4, NFR-1/FR-38 substrate): the banner
 * state and offline launch routing read this; nothing here blocks startup —
 * the initial value is a single synchronous settings-free probe.
 */
class ConnectivityObserver(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _online = MutableStateFlow(initialOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        // Live updates (API 24+ default callback; minSdk 26). Registration is
        // best-effort: a missing callback never blocks launch.
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = true }
                override fun onLost(network: Network) { recompute() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        } catch (_: Exception) {
        }
    }

    private fun initialOnline(): Boolean = try {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        true // assume online rather than wrongly flagging offline (honesty law cuts both ways)
    }

    private fun recompute() {
        _online.value = initialOnline()
    }
}
