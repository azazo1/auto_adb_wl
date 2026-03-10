package com.azazo1.auto_adb_wl_client.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ext.SdkExtensions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.azazo1.auto_adb_wl_client.data.DiscoveredService

/**
 * mDNS 服务发现管理器
 * 使用 Android NsdManager 发现 Auto ADB 服务
 */
class MdnsDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    companion object {
        // 服务类型，与服务端一致
        private const val SERVICE_TYPE = "_http._tcp."
    }

    /**
     * 开始发现服务
     * 返回 Flow，持续发出发现的服务列表
     */
    fun discoverServices(): Flow<List<DiscoveredService>> = callbackFlow {
        val services = mutableMapOf<String, DiscoveredService>()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                // 发现开始
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // 发现服务，解析详细信息
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        // 解析失败，忽略
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val addresses = mutableListOf<String>()
                        // 获取所有IP地址
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(
                                Build.VERSION_CODES.TIRAMISU) >= 7) {
                            serviceInfo.hostAddresses.forEach {
                                addresses.add(it.toString())
                            }
                        } else {
                            serviceInfo.host?.hostAddress?.let { addresses.add(it) }
                        }

                        val discovered = DiscoveredService(
                            name = serviceInfo.serviceName,
                            host = serviceInfo.host?.hostAddress ?: "",
                            port = serviceInfo.port,
                            addresses = addresses
                        )

                        services[serviceInfo.serviceName] = discovered
                        trySend(services.values.toList())
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // 服务丢失
                services.remove(service.serviceName)
                trySend(services.values.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) {
                // 发现停止
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // 开始发现失败
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                // 停止发现失败
                close()
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                // 忽略停止失败
            }
        }
    }
}