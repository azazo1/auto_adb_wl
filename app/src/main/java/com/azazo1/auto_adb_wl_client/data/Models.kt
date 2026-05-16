package com.azazo1.auto_adb_wl_client.data

import kotlinx.serialization.Serializable

enum class DiscoverySource(val label: String, val sortOrder: Int) {
    MDNS("mDNS", 0),
    LND("lnd", 1)
}

@Serializable
data class AdbConnectRequest(
    val address: String
)

@Serializable
data class AdbDisconnectRequest(
    val target: String
)

@Serializable
data class AdbPairRequest(
    val address: String,
    val pair_code: String
)

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

@Serializable
data class ServerResponse(
    val ok: Boolean,
    val message: String
)

data class DiscoveredService(
    val name: String,
    val host: String,
    val port: Int,
    val addresses: List<String> = emptyList(),
    val sources: Set<DiscoverySource> = setOf(DiscoverySource.MDNS),
    val discoveryDomain: String? = null
) {
    val normalizedAddresses: List<String>
        get() = buildList {
            addAll(addresses.map(::normalizeHost))
            add(normalizeHost(host))
        }.filter { it.isNotEmpty() }.distinct()

    val preferredHost: String
        get() = normalizedAddresses.firstOrNull().orEmpty()

    val displayAddress: String
        get() = formatHostPort(preferredHost.ifBlank { normalizeHost(host) }, port)

    val baseUrl: String
        get() = buildBaseUrl(preferredHost.ifBlank { normalizeHost(host) }, port)

    val sourceLabel: String
        get() = sources.sortedBy(DiscoverySource::sortOrder).joinToString(" + ") { it.label }

    val discoveryLabel: String
        get() = discoveryDomain?.let { "$sourceLabel | $it" } ?: sourceLabel

    fun mergeWith(other: DiscoveredService): DiscoveredService {
        val mergedSources = linkedSetOf<DiscoverySource>().apply {
            addAll(sources)
            addAll(other.sources)
        }
        val mergedAddresses = (normalizedAddresses + other.normalizedAddresses).distinct()
        val mergedName = when {
            name.isBlank() -> other.name
            other.name.isBlank() -> name
            sources.contains(DiscoverySource.MDNS) -> name
            other.sources.contains(DiscoverySource.MDNS) -> other.name
            else -> name
        }
        return copy(
            name = mergedName,
            host = preferredHost.ifBlank { other.preferredHost.ifBlank { normalizeHost(host) } },
            addresses = mergedAddresses,
            sources = mergedSources,
            discoveryDomain = discoveryDomain ?: other.discoveryDomain
        )
    }

    companion object {
        fun merge(services: Collection<DiscoveredService>): List<DiscoveredService> {
            val merged = LinkedHashMap<String, DiscoveredService>()
            for (service in services) {
                val normalized = service.copy(
                    host = normalizeHost(service.host),
                    addresses = service.normalizedAddresses
                )
                if (normalized.preferredHost.isBlank() || normalized.port <= 0) {
                    continue
                }
                val key = normalized.baseUrl.lowercase()
                val current = merged[key]
                merged[key] = current?.mergeWith(normalized) ?: normalized
            }
            return merged.values.sortedWith(
                compareBy<DiscoveredService>({ it.name.lowercase() }, { it.displayAddress.lowercase() })
            )
        }

        fun buildBaseUrl(host: String, port: Int): String = buildBaseUrl(host, port.toString())

        fun buildBaseUrl(host: String, port: String): String {
            val normalizedHost = normalizeHost(host)
            val normalizedPort = port.trim()
            return "http://${formatHostForUrl(normalizedHost)}:$normalizedPort/"
        }

        fun extractHost(rawAddress: String): String? {
            val trimmed = rawAddress.trim()
            if (trimmed.isEmpty()) {
                return null
            }
            if (trimmed.startsWith("[")) {
                val closing = trimmed.indexOf(']')
                if (closing <= 1) {
                    return null
                }
                return trimmed.substring(1, closing)
            }
            if (trimmed.count { it == ':' } == 1) {
                return trimmed.substringBeforeLast(':').trim().ifEmpty { null }
            }
            return trimmed
        }

        fun normalizeHost(rawHost: String): String {
            return extractHost(rawHost)
                ?.substringBefore('%')
                ?.trim()
                .orEmpty()
        }

        private fun formatHostPort(host: String, port: Int): String {
            val normalizedHost = normalizeHost(host)
            return if (normalizedHost.contains(':')) {
                "[$normalizedHost]:$port"
            } else {
                "$normalizedHost:$port"
            }
        }

        private fun formatHostForUrl(host: String): String {
            val normalizedHost = normalizeHost(host)
            return if (normalizedHost.contains(':')) {
                "[$normalizedHost]"
            } else {
                normalizedHost
            }
        }
    }
}

@Serializable
data class ServerAddressHistory(
    val address: String,
    val port: String
) {
    val displayAddress: String
        get() = "$address:$port"
}
