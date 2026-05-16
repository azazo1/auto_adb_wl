package com.azazo1.auto_adb_wl_client.data

import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.net.InetAddress

enum class DiscoverySource(val label: String, val sortOrder: Int) {
    MDNS("mDNS", 0),
    LND("lnd", 1)
}

enum class NetworkRelation {
    LOCAL,
    UNKNOWN,
    CROSS_SUBNET
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
    val discoveryDomain: String? = null,
    val networkRelation: NetworkRelation = NetworkRelation.LOCAL
) {
    val normalizedAddresses: List<String>
        get() = buildList {
            add(normalizeHost(host))
            addAll(addresses.map(::normalizeHost))
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
        get() = listOfNotNull(
            sourceLabel,
            discoveryDomain,
            networkRelationLabel()
        ).joinToString(" | ")

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
            discoveryDomain = discoveryDomain ?: other.discoveryDomain,
            networkRelation = mergeNetworkRelation(other)
        )
    }

    private fun networkRelationLabel(): String? {
        return when (networkRelation) {
            NetworkRelation.LOCAL -> null
            NetworkRelation.UNKNOWN -> "subnet-unknown"
            NetworkRelation.CROSS_SUBNET -> "cross-subnet"
        }
    }

    private fun mergeNetworkRelation(other: DiscoveredService): NetworkRelation {
        return when {
            networkRelation == NetworkRelation.LOCAL || other.networkRelation == NetworkRelation.LOCAL -> NetworkRelation.LOCAL
            networkRelation == NetworkRelation.UNKNOWN || other.networkRelation == NetworkRelation.UNKNOWN -> NetworkRelation.UNKNOWN
            else -> NetworkRelation.CROSS_SUBNET
        }
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
                compareBy<DiscoveredService>(
                    { networkRelationOrder(it.networkRelation) },
                    { it.name.lowercase() },
                    { it.displayAddress.lowercase() }
                )
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

        fun bestHostByReachability(
            hosts: List<String>,
            reachabilityScopes: List<String>,
            localReachabilityScopes: List<String>
        ): String? {
            if (hosts.isEmpty()) {
                return null
            }
            return hosts.sortedWith(
                compareBy<String>(
                    { hostReachabilityOrder(it, reachabilityScopes, localReachabilityScopes) },
                    { it }
                )
            ).firstOrNull()
        }

        fun prioritizeHostsByReachability(
            hosts: List<String>,
            reachabilityScopes: List<String>,
            localReachabilityScopes: List<String>
        ): List<String> {
            return hosts.sortedWith(
                compareBy<String>(
                    { hostReachabilityOrder(it, reachabilityScopes, localReachabilityScopes) },
                    { it }
                )
            ).distinct()
        }

        fun overlappingHostsByReachability(
            hosts: List<String>,
            reachabilityScopes: List<String>,
            localReachabilityScopes: List<String>
        ): List<String> {
            val prioritizedHosts = prioritizeHostsByReachability(
                hosts = hosts,
                reachabilityScopes = reachabilityScopes,
                localReachabilityScopes = localReachabilityScopes
            )
            if (prioritizedHosts.isEmpty()) {
                return emptyList()
            }
            val firstOrder = hostReachabilityOrder(
                host = prioritizedHosts.first(),
                reachabilityScopes = reachabilityScopes,
                localReachabilityScopes = localReachabilityScopes
            )
            return prioritizedHosts.filter { host ->
                hostReachabilityOrder(
                    host = host,
                    reachabilityScopes = reachabilityScopes,
                    localReachabilityScopes = localReachabilityScopes
                ) == firstOrder
            }
        }

        fun resolveNetworkRelation(
            reachabilityScopes: List<String>,
            localReachabilityScopes: List<String>
        ): NetworkRelation {
            if (reachabilityScopes.isEmpty() || localReachabilityScopes.isEmpty()) {
                return NetworkRelation.UNKNOWN
            }
            if (reachabilityScopes.any { nodeScope ->
                localReachabilityScopes.any { localScope -> scopesOverlap(nodeScope, localScope) }
            }) {
                return NetworkRelation.LOCAL
            }
            return NetworkRelation.CROSS_SUBNET
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

        private fun networkRelationOrder(networkRelation: NetworkRelation): Int {
            return when (networkRelation) {
                NetworkRelation.LOCAL -> 0
                NetworkRelation.UNKNOWN -> 1
                NetworkRelation.CROSS_SUBNET -> 2
            }
        }

        private fun hostReachabilityOrder(
            host: String,
            reachabilityScopes: List<String>,
            localReachabilityScopes: List<String>
        ): Int {
            val matchingNodeScopes = reachabilityScopes.filter { cidr -> hostInCidr(host, cidr) }
            if (matchingNodeScopes.isNotEmpty()) {
                val bestLocalScopeIndex = localReachabilityScopes.indexOfFirst { localScope ->
                    matchingNodeScopes.any { nodeScope -> scopesOverlap(nodeScope, localScope) }
                }
                if (bestLocalScopeIndex >= 0) {
                    return bestLocalScopeIndex
                }
                return localReachabilityScopes.size
            }
            val fallbackLocalScopeIndex = localReachabilityScopes.indexOfFirst { cidr ->
                hostInCidr(host, cidr)
            }
            if (fallbackLocalScopeIndex >= 0) {
                return localReachabilityScopes.size + fallbackLocalScopeIndex + 1
            }
            return localReachabilityScopes.size * 2 + 1
        }

        private fun scopesOverlap(first: String, second: String): Boolean {
            val firstScope = parseCidr(first) ?: return false
            val secondScope = parseCidr(second) ?: return false
            if (firstScope.addressBytes.size != secondScope.addressBytes.size) {
                return false
            }
            val prefixLength = minOf(firstScope.prefixLength, secondScope.prefixLength)
            return maskedAddress(firstScope.addressBytes, prefixLength) == maskedAddress(secondScope.addressBytes, prefixLength)
        }

        private fun hostInCidr(host: String, cidr: String): Boolean {
            val scope = parseCidr(cidr) ?: return false
            val addressBytes = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return false
            if (addressBytes.size != scope.addressBytes.size) {
                return false
            }
            return maskedAddress(addressBytes, scope.prefixLength) == maskedAddress(scope.addressBytes, scope.prefixLength)
        }

        private fun maskedAddress(addressBytes: ByteArray, prefixLength: Int): BigInteger {
            if (prefixLength <= 0) {
                return BigInteger.ZERO
            }
            val fullBits = addressBytes.size * 8
            val normalizedPrefixLength = prefixLength.coerceIn(0, fullBits)
            val address = BigInteger(1, addressBytes)
            val shift = fullBits - normalizedPrefixLength
            return address.shiftRight(shift).shiftLeft(shift)
        }

        private fun parseCidr(cidr: String): ParsedCidr? {
            val parts = cidr.trim().split("/", limit = 2)
            if (parts.size != 2) {
                return null
            }
            val addressBytes = runCatching { InetAddress.getByName(parts[0].trim()).address }.getOrNull() ?: return null
            val prefixLength = parts[1].trim().toIntOrNull() ?: return null
            val maxPrefixLength = addressBytes.size * 8
            if (prefixLength !in 0..maxPrefixLength) {
                return null
            }
            return ParsedCidr(addressBytes, prefixLength)
        }
    }

    private data class ParsedCidr(
        val addressBytes: ByteArray,
        val prefixLength: Int
    )
}

@Serializable
data class ServerAddressHistory(
    val address: String,
    val port: String
) {
    val displayAddress: String
        get() = "$address:$port"
}
