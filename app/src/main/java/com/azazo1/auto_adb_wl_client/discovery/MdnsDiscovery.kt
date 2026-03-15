package com.azazo1.auto_adb_wl_client.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ext.SdkExtensions
import com.azazo1.auto_adb_wl_client.data.DiscoveredService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.run


/**
 * mDNS 服务发现管理器
 * 使用 Android NsdManager 发现 Auto ADB 服务
 */
class MdnsDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val services = ConcurrentHashMap<String, DiscoveredService>()

    companion object {
        // 服务类型，与服务端一致
        private const val SERVICE_TYPE = "_http._tcp."
    }

    /**
     * 开始发现服务
     * 返回 Flow，持续发出发现的服务列表
     */
    fun discoverServices(): Flow<List<DiscoveredService>> =
        callbackFlow {
            // 用于旧版本：解析队列
            val resolveQueue = ArrayDeque<NsdServiceInfo>()
            var isResolving = false

            // 辅助函数：处理解析结果并发送
            fun handleResolvedService(serviceInfo: NsdServiceInfo) {
                val addresses = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(
                        Build.VERSION_CODES.TIRAMISU
                    ) >= 7
                ) {
                    // Android 11+ 获取多地址
                    serviceInfo.hostAddresses.forEach { addresses.add(it.hostAddress ?: "") }
                } else {
                    serviceInfo.host?.hostAddress?.let { addresses.add(it) }
                }

                val discovered = DiscoveredService(
                    name = serviceInfo.serviceName,
                    host = serviceInfo.host?.hostAddress ?: "",
                    port = serviceInfo.port,
                    addresses = addresses.filter { it.isNotEmpty() }
                )

                // 使用 serviceName 作为 Key，如果同名但 Host 不同，建议用 name + host
                services[serviceInfo.serviceName] = discovered
                trySend(services.values.toList())
            }

            // --- 旧版本解析逻辑 (API < 31) ---
            fun processNextInQueue() {
                if (isResolving || resolveQueue.isEmpty()) return
                isResolving = true
                val nextService = resolveQueue.poll()

                nsdManager.resolveService(nextService, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        isResolving = false
                        processNextInQueue() // 继续下一个
                    }

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        handleResolvedService(si)
                        isResolving = false
                        processNextInQueue() // 继续下一个
                    }
                })
            }

            // --- 定义 DiscoveryListener ---
            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}

                override fun onServiceFound(service: NsdServiceInfo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(
                            Build.VERSION_CODES.TIRAMISU
                        ) >= 7
                    ) {
                        nsdManager.registerServiceInfoCallback(
                            service,
                            { it.run() },
                            object : NsdManager.ServiceInfoCallback {
                                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                                override fun onServiceUpdated(si: NsdServiceInfo) =
                                    handleResolvedService(si)

                                override fun onServiceLost() {
                                    services.remove(service.serviceName)
                                    trySend(services.values.toList())
                                }

                                override fun onServiceInfoCallbackUnregistered() {}
                            })
                    } else {
                        // 旧版本：加入队列手动解析
                        resolveQueue.add(service)
                        processNextInQueue()
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    services.remove(service.serviceName)
                    trySend(services.values.toList())
                }

                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(st: String, err: Int) {
                    close()
                }

                override fun onStopDiscoveryFailed(st: String, err: Int) {
                    close()
                }
            }

            // 启动发现
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
                } catch (e: Exception) { /* 忽略 */
                }
            }
        }

}
