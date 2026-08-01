package com.castla.mirror.network

import org.junit.Assert.*
import org.junit.Test

class TeslaVirtualIpTest {

    private val sampleIpOutput = """
        1: lo    inet 127.0.0.1/8 scope host lo\       valid_lft forever preferred_lft forever
        2: rmnet_data0    inet 10.166.96.133/28 scope global rmnet_data0\       valid_lft forever preferred_lft forever
        5: swlan0    inet 10.166.96.1/24 brd 10.166.96.255 scope global swlan0\       valid_lft forever preferred_lft forever
    """.trimIndent()

    // ── parseInterfaceAddresses ──

    @Test
    fun `parse extracts ipv4 addresses per interface`() {
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(sampleIpOutput)
        assertEquals(listOf("10.166.96.1"), addrs["swlan0"])
        assertEquals(listOf("10.166.96.133"), addrs["rmnet_data0"])
        assertEquals(listOf("127.0.0.1"), addrs["lo"])
    }

    @Test
    fun `parse keeps multiple addresses on same interface`() {
        val out = "5: ap0    inet 192.168.43.1/24 brd 192.168.43.255 scope global ap0\n" +
                  "5: ap0    inet 100.99.9.9/32 scope global ap0"
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(out)
        assertEquals(listOf("192.168.43.1", "100.99.9.9"), addrs["ap0"])
    }

    @Test
    fun `parse skips ipv6 and malformed lines`() {
        val out = "5: swlan0    inet6 fe80::1/64 scope link\n" +
                  "garbage line\n" +
                  "5: swlan0    inet 10.0.0.1/24 scope global swlan0"
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(out)
        assertEquals(listOf("10.0.0.1"), addrs["swlan0"])
    }

    // ── findHotspotInterface ──

    @Test
    fun `finds swlan0 as hotspot interface`() {
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(sampleIpOutput)
        assertEquals("swlan0", TeslaVirtualIp.findHotspotInterface(addrs))
    }

    @Test
    fun `finds ap0 when swlan0 absent`() {
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(
            "3: ap0    inet 192.168.43.1/24 scope global ap0"
        )
        assertEquals("ap0", TeslaVirtualIp.findHotspotInterface(addrs))
    }

    @Test
    fun `prefers swlan0 over ap0 when both present`() {
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(
            "3: ap0    inet 192.168.43.1/24 scope global ap0\n" +
            "5: swlan0    inet 10.0.0.1/24 scope global swlan0"
        )
        assertEquals("swlan0", TeslaVirtualIp.findHotspotInterface(addrs))
    }

    @Test
    fun `returns null when no hotspot interface`() {
        val addrs = TeslaVirtualIp.parseInterfaceAddresses(
            "1: lo    inet 127.0.0.1/8 scope host lo\n" +
            "2: wlan0    inet 192.168.1.5/24 scope global wlan0"
        )
        assertNull(TeslaVirtualIp.findHotspotInterface(addrs))
    }

    // ── hasVirtualAlias ──

    @Test
    fun `hasVirtualAlias true when alias present`() {
        val addrs = mapOf("swlan0" to listOf("10.166.96.1", "100.99.9.9"))
        assertTrue(TeslaVirtualIp.hasVirtualAlias(addrs, "swlan0"))
    }

    @Test
    fun `hasVirtualAlias false when alias absent or interface unknown`() {
        assertFalse(TeslaVirtualIp.hasVirtualAlias(mapOf("swlan0" to listOf("10.166.96.1")), "swlan0"))
        assertFalse(TeslaVirtualIp.hasVirtualAlias(emptyMap(), "swlan0"))
    }

    // ── virtualUrl ──

    @Test
    fun `virtualUrl uses sslip domain form`() {
        assertEquals("http://100-99-9-9.sslip.io:9090", TeslaVirtualIp.virtualUrl(9090))
    }

    // ── setupSucceeded ──

    @Test
    fun `setupSucceeded true on RESULT OK line`() {
        assertTrue(TeslaVirtualIp.setupSucceeded("=== setup ===\nsome detail\nRESULT: OK"))
    }

    @Test
    fun `setupSucceeded false on RESULT FAIL, garbage, or null`() {
        assertFalse(TeslaVirtualIp.setupSucceeded("=== setup ===\nRESULT: FAIL"))
        assertFalse(TeslaVirtualIp.setupSucceeded("random output"))
        assertFalse(TeslaVirtualIp.setupSucceeded(null))
        assertFalse(TeslaVirtualIp.setupSucceeded(""))
    }

    // ── constants ──

    @Test
    fun `virtual ip is in cgnat range which tesla does not block`() {
        assertEquals("100.99.9.9", TeslaVirtualIp.VIRTUAL_IP)
        assertEquals(32, TeslaVirtualIp.VIRTUAL_PREFIX)
    }
}
