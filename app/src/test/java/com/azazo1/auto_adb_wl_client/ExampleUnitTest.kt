package com.azazo1.auto_adb_wl_client

import com.azazo1.auto_adb_wl_client.data.DiscoveredService
import com.azazo1.auto_adb_wl_client.data.DiscoverySource
import com.azazo1.auto_adb_wl_client.data.NetworkRelation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun buildBaseUrl_wrapsIpv6Host() {
        assertEquals(
            "http://[fd00::1]:21300/",
            DiscoveredService.buildBaseUrl("fd00::1", 21300)
        )
    }

    @Test
    fun merge_combinesSourcesForSameEndpoint() {
        val services = DiscoveredService.merge(
            listOf(
                DiscoveredService(
                    name = "Desktop",
                    host = "192.168.1.10",
                    port = 21300,
                    addresses = listOf("192.168.1.10"),
                    sources = setOf(DiscoverySource.MDNS)
                ),
                DiscoveredService(
                    name = "Auto ADB",
                    host = "192.168.1.10",
                    port = 21300,
                    addresses = listOf("192.168.1.10"),
                    sources = setOf(DiscoverySource.LND),
                    discoveryDomain = "office-a"
                )
            )
        )

        assertEquals(1, services.size)
        assertEquals("Desktop", services.single().name)
        assertEquals("mDNS + lnd | office-a", services.single().discoveryLabel)
        assertTrue(services.single().sources.containsAll(setOf(DiscoverySource.MDNS, DiscoverySource.LND)))
    }

    @Test
    fun merge_sortsCrossSubnetAfterLocalService() {
        val services = DiscoveredService.merge(
            listOf(
                DiscoveredService(
                    name = "Cross",
                    host = "10.0.0.8",
                    port = 21300,
                    addresses = listOf("10.0.0.8"),
                    sources = setOf(DiscoverySource.LND),
                    networkRelation = NetworkRelation.CROSS_SUBNET
                ),
                DiscoveredService(
                    name = "Local",
                    host = "192.168.1.8",
                    port = 21300,
                    addresses = listOf("192.168.1.8"),
                    sources = setOf(DiscoverySource.LND),
                    networkRelation = NetworkRelation.LOCAL
                )
            )
        )

        assertEquals(listOf("Local", "Cross"), services.map { it.name })
        assertEquals("lnd | cross-subnet", services.last().discoveryLabel)
    }

    @Test
    fun bestHostByReachability_prefersHostMatchingLocalScopeWhenNodeHasMultipleScopes() {
        val host = DiscoveredService.bestHostByReachability(
            hosts = listOf("10.0.0.8", "192.168.1.8"),
            reachabilityScopes = listOf("10.0.0.0/24", "192.168.1.0/24"),
            localReachabilityScopes = listOf("192.168.1.0/24")
        )

        assertEquals("192.168.1.8", host)
    }

    @Test
    fun bestHostByReachability_prefersCurrentWifiScopeOverOtherLocalScopes() {
        val host = DiscoveredService.bestHostByReachability(
            hosts = listOf("172.22.176.1", "172.27.208.1", "192.168.1.112"),
            reachabilityScopes = listOf("172.22.176.0/20", "172.27.208.0/20", "192.168.1.0/24"),
            localReachabilityScopes = listOf("192.168.1.0/24", "172.22.176.0/20", "172.27.208.0/20")
        )

        assertEquals("192.168.1.112", host)
    }

    @Test
    fun preferredHost_usesExplicitHostBeforeAddressListOrder() {
        val service = DiscoveredService(
            name = "Auto ADB",
            host = "192.168.1.112",
            port = 55179,
            addresses = listOf("172.22.176.1", "172.27.208.1", "192.168.1.112"),
            sources = setOf(DiscoverySource.LND)
        )

        assertEquals("192.168.1.112", service.preferredHost)
        assertEquals("192.168.1.112:55179", service.displayAddress)
    }

    @Test
    fun overlappingHostsByReachability_returnsPreferredLocalSubset() {
        val hosts = DiscoveredService.overlappingHostsByReachability(
            hosts = listOf("172.22.176.1", "172.27.208.1", "192.168.1.112"),
            reachabilityScopes = listOf("172.22.176.0/20", "172.27.208.0/20", "192.168.1.0/24"),
            localReachabilityScopes = listOf("192.168.1.0/24", "172.22.176.0/20", "172.27.208.0/20")
        )

        assertEquals(listOf("192.168.1.112"), hosts)
    }
}
