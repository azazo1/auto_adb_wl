package com.azazo1.auto_adb_wl_client.data

import kotlinx.serialization.Serializable

/**
 * ADB 连接请求参数
 */
@Serializable
data class AdbConnectRequest(
    val address: String
)

/**
 * ADB 断连请求
 */
@Serializable
data class AdbDisconnectRequest(
    val target: String
)

/**
 * ADB 配对请求参数
 */
@Serializable
data class AdbPairRequest(
    val address: String,
    val pair_code: String
)

/**
 * Scrcpy 启动模式
 */
@Serializable
data class ScrcpyLaunchRequest(
    val mode: ScrcpyLaunchMode
)

@Serializable
data class ScrcpyLaunchMode(
    val tag: String,
    val content: String? = null
) {
    companion object {
        fun usb() = ScrcpyLaunchMode("Usb")
        fun tcpIp() = ScrcpyLaunchMode("TcpIp")
        fun serial(serial: String) = ScrcpyLaunchMode("Serial", serial)
        fun tcpIpConnect(address: String) = ScrcpyLaunchMode("TcpIpConnect", address)
    }
}

/**
 * 服务端响应
 */
@Serializable
data class ServerResponse(
    val ok: Boolean,
    val message: String
)

/**
 * 发现的服务
 */
data class DiscoveredService(
    val name: String,
    val host: String,
    val port: Int,
    val addresses: List<String> = emptyList()
) {
    val displayAddress: String
        get() = if (addresses.isNotEmpty()) {
            "${addresses.first()}:$port"
        } else {
            "$host:$port"
        }

    val baseUrl: String
        get() = "http://${addresses.firstOrNull() ?: host}:$port"
}
