package com.azazo1.auto_adb_wl_client.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ext.SdkExtensions
import com.azazo1.auto_adb_wl_client.data.DiscoveredService
import com.azazo1.auto_adb_wl_client.data.DiscoverySource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class MdnsDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val services = ConcurrentHashMap<String, DiscoveredService>()

    fun discoverServices(): Flow<List<DiscoveredService>> =
        callbackFlow {
            services.clear()
            val resolveQueue = ArrayDeque<NsdServiceInfo>()
            var isResolving = false

            fun handleResolvedService(serviceInfo: NsdServiceInfo) {
                val addresses = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
                ) {
                    serviceInfo.hostAddresses.forEach { addresses.add(it.hostAddress ?: "") }
                } else {
                    serviceInfo.host?.hostAddress?.let { addresses.add(it) }
                }

                val discovered = DiscoveredService(
                    name = serviceInfo.serviceName,
                    host = serviceInfo.host?.hostAddress ?: "",
                    port = serviceInfo.port,
                    addresses = addresses.filter { it.isNotEmpty() },
                    sources = setOf(DiscoverySource.MDNS)
                )

                services[serviceInfo.serviceName] = discovered
                trySend(DiscoveredService.merge(services.values))
            }

            fun processNextInQueue() {
                if (isResolving || resolveQueue.isEmpty()) {
                    return
                }
                isResolving = true
                val nextService = resolveQueue.poll()

                nsdManager.resolveService(nextService, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        isResolving = false
                        processNextInQueue()
                    }

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        handleResolvedService(si)
                        isResolving = false
                        processNextInQueue()
                    }
                })
            }

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}

                override fun onServiceFound(service: NsdServiceInfo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
                    ) {
                        nsdManager.registerServiceInfoCallback(
                            service,
                            { it.run() },
                            object : NsdManager.ServiceInfoCallback {
                                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}

                                override fun onServiceUpdated(si: NsdServiceInfo) {
                                    handleResolvedService(si)
                                }

                                override fun onServiceLost() {
                                    services.remove(service.serviceName)
                                    trySend(DiscoveredService.merge(services.values))
                                }

                                override fun onServiceInfoCallbackUnregistered() {}
                            }
                        )
                    } else {
                        resolveQueue.add(service)
                        processNextInQueue()
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    services.remove(service.serviceName)
                    trySend(DiscoveredService.merge(services.values))
                }

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(st: String, err: Int) {
                    close()
                }

                override fun onStopDiscoveryFailed(st: String, err: Int) {
                    close()
                }
            }

            try {
                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                )
            } catch (e: Exception) {
                close(e)
            }

            awaitClose {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                } catch (_: Exception) {
                }
            }
        }

    companion object {
        private const val SERVICE_TYPE = "_http._tcp."
    }
}
