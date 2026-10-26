package vn.onplay.vdubbing.data.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import vn.onplay.vdubbing.domain.repository.ConnectivityRepository

class ConnectivityRepositoryImpl(private val context: Context) : ConnectivityRepository {

    /**
     * VALIDATED matters: a captive portal or a Wi-Fi network with no upstream
     * would otherwise send the cloud request into a 30s timeout.
     */
    override fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
