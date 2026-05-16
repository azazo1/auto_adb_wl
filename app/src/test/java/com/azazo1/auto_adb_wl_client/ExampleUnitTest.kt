package com.azazo1.auto_adb_wl_client

import com.azazo1.auto_adb_wl_client.data.DiscoveredService
import com.azazo1.auto_adb_wl_client.data.DiscoverySource
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
}
