package com.azazo1.auto_adb_wl_client.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import android.os.Build
import android.os.ext.SdkExtensions
import com.azazo1.auto_adb_wl_client.data.DiscoveredService
import com.azazo1.auto_adb_wl_client.data.DiscoverySource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MdnsDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val services = ConcurrentHashMap<String, DiscoveredService>()

    fun discoverServices(): Flow<List<DiscoveredService>> =
        callbackFlow {
            services.clear()
            val resolveQueue = ArrayDeque<NsdServiceInfo>()
            var isResolving = false
            val discoveryStarted = AtomicBoolean(false)
            Log.i(TAG, "mDNS discovery flow opened: serviceType=$SERVICE_TYPE")

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
                val merged = DiscoveredService.merge(services.values)
                Log.i(
                    TAG,
                    "mDNS resolved service: name=${serviceInfo.serviceName}, host=${discovered.host}, port=${discovered.port}, addresses=${discovered.addresses.joinToString(",")}, merged=${merged.size}"
                )
                trySend(merged)
            }

            fun processNextInQueue() {
                if (isResolving || resolveQueue.isEmpty()) {
                    return
                }
                isResolving = true
                val nextService = resolveQueue.poll()
                Log.i(TAG, "mDNS resolving queued service: name=${nextService.serviceName}")

                nsdManager.resolveService(nextService, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS resolve failed: name=${si.serviceName}, errorCode=$errorCode")
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
                override fun onDiscoveryStarted(regType: String) {
                    discoveryStarted.set(true)
                    Log.i(TAG, "mDNS discovery started: regType=$regType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.i(TAG, "mDNS service found: name=${service.serviceName}, type=${service.serviceType}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
                    ) {
                        nsdManager.registerServiceInfoCallback(
                            service,
                            { it.run() },
                            object : NsdManager.ServiceInfoCallback {
                                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                    Log.w(
                                        TAG,
                                        "mDNS service info callback registration failed: name=${service.serviceName}, errorCode=$errorCode"
                                    )
                                }

                                override fun onServiceUpdated(si: NsdServiceInfo) {
                                    handleResolvedService(si)
                                }

                                override fun onServiceLost() {
                                    services.remove(service.serviceName)
                                    val merged = DiscoveredService.merge(services.values)
                                    Log.i(TAG, "mDNS service lost via callback: name=${service.serviceName}, merged=${merged.size}")
                                    trySend(merged)
                                }

                                override fun onServiceInfoCallbackUnregistered() {
                                    Log.i(TAG, "mDNS service info callback unregistered: name=${service.serviceName}")
                                }
                            }
                        )
                    } else {
                        resolveQueue.add(service)
                        processNextInQueue()
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    services.remove(service.serviceName)
                    val merged = DiscoveredService.merge(services.values)
                    Log.i(TAG, "mDNS service lost: name=${service.serviceName}, merged=${merged.size}")
                    trySend(merged)
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    discoveryStarted.set(false)
                    Log.i(TAG, "mDNS discovery stopped: serviceType=$serviceType")
                }

                override fun onStartDiscoveryFailed(st: String, err: Int) {
                    Log.e(TAG, "mDNS discovery start failed: serviceType=$st, errorCode=$err")
                    close(IllegalStateException("mDNS discovery start failed: errorCode=$err"))
                }

                override fun onStopDiscoveryFailed(st: String, err: Int) {
                    Log.e(TAG, "mDNS discovery stop failed: serviceType=$st, errorCode=$err")
                    discoveryStarted.set(false)
                }
            }

            try {
                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                )
            } catch (e: Exception) {
                Log.e(TAG, "mDNS discoverServices threw an exception", e)
                close(e)
            }

            awaitClose {
                Log.i(TAG, "mDNS discovery flow closing")
                if (discoveryStarted.get()) {
                    try {
                        nsdManager.stopServiceDiscovery(discoveryListener)
                    } catch (e: Exception) {
                        Log.w(TAG, "mDNS stopServiceDiscovery threw an exception", e)
                    }
                }
            }
        }

    companion object {
        private const val TAG = "MdnsDiscovery"
        private const val SERVICE_TYPE = "_http._tcp."
    }
}
