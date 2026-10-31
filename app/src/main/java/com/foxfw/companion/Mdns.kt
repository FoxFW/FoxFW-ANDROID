package com.foxfw.companion

import android.content.Context
import android.net.wifi.WifiManager
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress

/**
 * Minimal mDNS (multicast-DNS) resolver for *.local hostnames — Android's HTTP resolver doesn't do
 * mDNS, so HttpURLConnection can't reach "hue-proxy.local". Sends a unicast-response A query to
 * 224.0.0.251:5353 and parses the A record. Self-contained, no deps. The packet build/parse is pure
 * (unit-tested); resolve() does the socket I/O.
 */
object Mdns {
    private const val MDNS_ADDR = "224.0.0.251"
    private const val MDNS_PORT = 5353

    fun buildQuery(hostname: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // id, flags, QD=1, AN=NS=AR=0
        for (label in hostname.trim('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size); out.write(b)
        }
        out.write(0)                                 // name terminator
        out.write(byteArrayOf(0, 1))                 // QTYPE = A
        out.write(byteArrayOf(0x80.toByte(), 1))     // QCLASS = IN, unicast-response (QU) bit set
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)

    private fun skipName(b: ByteArray, start: Int): Int {
        var p = start
        while (p < b.size) {
            val len = b[p].toInt() and 0xff
            if (len == 0) return p + 1
            if (len and 0xC0 == 0xC0) return p + 2   // a compression pointer (2 bytes) ends the name
            p += 1 + len
        }
        return p
    }

    /** First A-record IPv4 in an mDNS response, or null. */
    fun parseA(resp: ByteArray): String? {
        if (resp.size < 12) return null
        val qd = u16(resp, 4); val an = u16(resp, 6)
        var p = 12
        repeat(qd) { p = skipName(resp, p) + 4 }     // QNAME + QTYPE + QCLASS
        repeat(an) {
            p = skipName(resp, p)
            if (p + 10 > resp.size) return null
            val type = u16(resp, p); val rdlen = u16(resp, p + 8)
            val rdata = p + 10
            if (type == 1 && rdlen == 4 && rdata + 4 <= resp.size) {
                return "${resp[rdata].toInt() and 0xff}.${resp[rdata + 1].toInt() and 0xff}." +
                    "${resp[rdata + 2].toInt() and 0xff}.${resp[rdata + 3].toInt() and 0xff}"
            }
            p = rdata + rdlen
        }
        return null
    }

    /** Resolve [hostname] (e.g. "hue-proxy.local") to an IPv4, or null. Blocking — call off the main thread.
     *  Responders (e.g. ESP32 mDNS) often reply by MULTICAST, so we bind the mDNS port + join the group
     *  to catch both multicast and unicast replies, and only accept a packet that names our host. */
    fun resolve(ctx: Context, hostname: String, timeoutMs: Int = 2500): String? {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching {
            wifi?.createMulticastLock("fox-mdns")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val label = hostname.substringBefore('.')               // "hue-proxy"
        val group = InetAddress.getByName(MDNS_ADDR)
        val q = buildQuery(hostname)
        fun scan(sock: DatagramSocket): String? {
            sock.soTimeout = 400
            sock.send(DatagramPacket(q, q.size, group, MDNS_PORT))
            val buf = ByteArray(1500)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try { sock.receive(pkt) } catch (_: Throwable) { continue }
                val data = buf.copyOf(pkt.length)
                if (String(data, Charsets.ISO_8859_1).contains(label, ignoreCase = true)) {
                    parseA(data)?.let { return it }
                }
            }
            return null
        }
        return try {
            // Primary: multicast socket bound to 5353 (joins the group → receives multicast replies).
            val ms = MulticastSocket(null as SocketAddress?)
            try {
                ms.reuseAddress = true
                ms.bind(InetSocketAddress(MDNS_PORT))
                runCatching { ms.joinGroup(group) }
                scan(ms)?.let { return it }
            } finally { runCatching { ms.leaveGroup(group) }; runCatching { ms.close() } }
            // Fallback: ephemeral unicast socket (catches QU unicast replies if 5353 was unavailable).
            DatagramSocket().use { scan(it) }
        } catch (_: Throwable) { null } finally { runCatching { lock?.release() } }
    }
}
