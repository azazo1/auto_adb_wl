package com.azazo1.auto_adb_wl_client.discovery

import android.content.Context
import com.azazo1.auto_adb_wl_client.data.DiscoveredService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

class MultiSourceDiscovery(context: Context) {
    private val mdnsDiscovery = MdnsDiscovery(context)
    private val lndLocator = LndLocator()

    fun discoverServices(): Flow<List<DiscoveredService>> {
        return combine(
            mdnsDiscovery.discoverServices().onStart { emit(emptyList()) },
            lndLocator.discoverServices().onStart { emit(emptyList()) }
        ) { mdnsServices, lndServices ->
            DiscoveredService.merge(mdnsServices + lndServices)
        }
    }
}
