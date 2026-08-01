package com.castla.mirror.network

/**
 * Pure logic for the Tesla virtual-IP compatibility mode.
 *
 * Tesla's browser refuses connections to RFC1918 private ranges
 * (10/8, 172.16/12, 192.168/16) with ERR_CONNECTION_REFUSED. The
 * workaround (same approach as TeslaMirror): give the phone's hotspot
 * interface an alias inside the CGNAT range 100.64.0.0/10, which Tesla
 * does not block. Everything here is pure and unit-testable; the
 * privileged netd/shell calls live in PrivilegedService.
 */
object TeslaVirtualIp {

    const val VIRTUAL_IP = "100.99.9.9"
    const val VIRTUAL_PREFIX = 32

    const val RESULT_OK = "RESULT: OK"
    const val RESULT_FAIL = "RESULT: FAIL"

    /** Hotspot interface names, highest priority first. */
    private val HOTSPOT_IFACE_CANDIDATES = listOf("swlan0", "wlan1", "ap0", "softap0")

    /**
     * Parse `ip -o addr show` output into ifName -> IPv4 addresses (without prefix).
     * In `-o` mode each record is one line like:
     *   5: swlan0    inet 10.166.96.1/24 brd 10.166.96.255 scope global swlan0\       valid_lft ...
     */
    fun parseInterfaceAddresses(ipOutput: String): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for (line in ipOutput.lineSequence()) {
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size < 4) continue
            val ifName = tokens[1].removeSuffix(":")
            if (tokens[2] != "inet") continue
            val ip = tokens[3].substringBefore('/')
            if (ip.isEmpty()) continue
            result.getOrPut(ifName) { mutableListOf() }.add(ip)
        }
        return result
    }

    /** First hotspot interface present in the parsed map, or null (WiFi mode). */
    fun findHotspotInterface(addrs: Map<String, List<String>>): String? =
        HOTSPOT_IFACE_CANDIDATES.firstOrNull { addrs.containsKey(it) }

    /** True when the virtual alias is already present on the interface (idempotency check). */
    fun hasVirtualAlias(addrs: Map<String, List<String>>, ifName: String): Boolean =
        addrs[ifName]?.contains(VIRTUAL_IP) == true

    /**
     * URL to display when the alias is active. sslip.io domain form avoids the
     * Tesla browser's HTTPS-first upgrade applied to bare IPs.
     */
    fun virtualUrl(port: Int): String =
        "http://${VIRTUAL_IP.replace('.', '-')}.sslip.io:$port"

    /** True when a setupTeslaNetworking diagnostic log reports success. */
    fun setupSucceeded(diagnosticLog: String?): Boolean =
        diagnosticLog?.lineSequence()?.any { it.trim() == RESULT_OK } == true
}
