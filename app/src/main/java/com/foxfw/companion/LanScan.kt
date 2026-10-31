package com.foxfw.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket

/**
 * On-phone LAN recon: enumerate the local subnet and TCP-probe each host for common services.
 * Pure Kotlin + java.net sockets — no root, no extra hardware. Discovery (connect-scan) only.
 */
object LanScan {
    data class Host(val ip: String, val openPorts: List<Int>, val name: String?)

    /** High-signal services worth probing on a typical LAN. */
    val COMMON_PORTS = listOf(22, 23, 53, 80, 139, 443, 445, 515, 554, 1883, 3389, 5000, 5555, 8080, 8443, 8888, 9100, 62078)

    private val SERVICE = mapOf(
        22 to "ssh", 23 to "telnet", 53 to "dns", 80 to "http", 139 to "netbios", 443 to "https",
        445 to "smb", 515 to "printer", 554 to "rtsp", 1883 to "mqtt", 3389 to "rdp", 5000 to "upnp",
        5555 to "adb", 8080 to "http-alt", 8443 to "https-alt", 8888 to "http-alt", 9100 to "jetdirect",
        62078 to "iphone-sync",
    )

    fun serviceName(port: Int): String = SERVICE[port] ?: port.toString()

    /** Every usable host address in the ipv4 subnet containing [ip] with CIDR [prefix] (network +
     *  broadcast excluded). Capped at 1024 hosts so a wide prefix can't run away. */
    fun hostsForCidr(ip: String, prefix: Int): List<String> {
        val base = ipToInt(ip) ?: return emptyList()
        val p = prefix.coerceIn(0, 32)
        if (p >= 31) return listOf(ip) // /31 /32: just this host
        val mask = if (p == 0) 0 else (-1 shl (32 - p))
        val network = base and mask
        val broadcast = network or mask.inv()
        val first = network + 1
        val last = broadcast - 1
        val count = (last.toLong() and 0xffffffffL) - (first.toLong() and 0xffffffffL) + 1
        if (count <= 0) return emptyList()
        val capped = minOf(count, 1024L).toInt()
        return (0 until capped).map { intToIp(first + it) }
    }

    /** Connect-scan [hosts] on [ports]; emits each responsive host via [onHost] and returns them all. */
    suspend fun scan(
        hosts: List<String>,
        ports: List<Int> = COMMON_PORTS,
        timeoutMs: Int = 300,
        parallelism: Int = 64,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
        onHost: (Host) -> Unit = {},
    ): List<Host> = coroutineScope {
        val gate = Semaphore(parallelism.coerceIn(8, 256))
        var done = 0
        val results = hosts.map { host ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val open = ports.filter { port -> tcpOpen(host, port, timeoutMs) }
                    synchronized(this@LanScan) { done++ }
                    onProgress(done, hosts.size)
                    if (open.isEmpty()) null
                    else Host(host, open, runCatching { java.net.InetAddress.getByName(host).canonicalHostName.takeIf { it != host } }.getOrNull())
                        .also { onHost(it) }
                }
            }
        }.awaitAll()
        results.filterNotNull()
    }

    private fun tcpOpen(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    }.getOrDefault(false)

    fun ipToInt(ip: String): Int? {
        val p = ip.trim().split('.')
        if (p.size != 4) return null
        var v = 0
        for (o in p) { val n = o.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n }
        return v
    }

    fun intToIp(v: Int): String =
        "${(v ushr 24) and 0xff}.${(v ushr 16) and 0xff}.${(v ushr 8) and 0xff}.${v and 0xff}"
}
