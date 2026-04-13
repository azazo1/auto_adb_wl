package com.azazo1.auto_adb_wl_client.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address

object NetworkUtils {
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun observeWifiIdentity(context: Context): Flow<String?> = callbackFlow {
        val appContext = context.applicationContext
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        if (connectivityManager == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        fun emitCurrentWifiIdentity() {
            trySend(getCurrentWifiIdentity(appContext, connectivityManager))
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                emitCurrentWifiIdentity()
            }

            override fun onLost(network: Network) {
                emitCurrentWifiIdentity()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                emitCurrentWifiIdentity()
            }
        }

        emitCurrentWifiIdentity()
        connectivityManager.registerDefaultNetworkCallback(callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    private fun getCurrentWifiIdentity(
        context: Context,
        connectivityManager: ConnectivityManager
    ): String? {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }

        val wifiManager =
            context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connectionInfo = wifiManager?.connectionInfo

        val ssid = connectionInfo?.ssid
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.takeUnless { it.isNullOrBlank() || it == WifiManager.UNKNOWN_SSID }
        val ipAddress = connectionInfo?.ipAddress
            ?.takeIf { it != 0 }
            ?.let { rawIp ->
                val b1 = rawIp and 0xff
                val b2 = rawIp shr 8 and 0xff
                val b3 = rawIp shr 16 and 0xff
                val b4 = rawIp shr 24 and 0xff
                "$b1.$b2.$b3.$b4"
            }
            ?: connectivityManager.getLinkProperties(activeNetwork)
                ?.linkAddresses
                ?.firstOrNull { it.isIpv4() }
                ?.address
                ?.hostAddress

        return listOfNotNull(ssid, ipAddress)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("|")
            ?: activeNetwork.toString()
    }

    private fun LinkAddress.isIpv4(): Boolean {
        return address is Inet4Address
    }
}
