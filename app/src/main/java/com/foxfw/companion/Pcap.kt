package com.foxfw.companion

/**
 * Minimal .pcap → hashcat-22000 extractor, on-phone. Turns a capture (e.g. a Esp32 `sniffpwn`
 * .pcap off the board's SD) into crackable `WPA*01` (PMKID) and `WPA*02` (4-way EAPOL) lines, so a
 * captured handshake can be cracked here without a PC/hcxpcapngtool.
 *
 * Handles classic pcap (LE or BE magic) with link types 105 (raw 802.11 — what Esp32 writes),
 * 119 (PRISM, 144-byte header) and 127 (radiotap). Parses beacons/probe-resps for SSID, and EAPOL-Key
 * frames: M1 gives the ANonce (+ any RSN PMKID KDE), M2 gives the SNonce + MIC + the EAPOL frame.
 */
object Pcap {

    fun toHc22000(data: ByteArray): List<String> {
        if (data.size < 24) return emptyList()
        val le = when {
            data[0] == 0xD4.toByte() && data[1] == 0xC3.toByte() -> true  // a1b2c3d4 stored little-endian
            data[0] == 0xA1.toByte() && data[1] == 0xB2.toByte() -> false // big-endian
            else -> return emptyList()
        }
        fun u32(o: Int): Long = if (le)
            (data[o].toLong() and 0xff) or ((data[o + 1].toLong() and 0xff) shl 8) or
                ((data[o + 2].toLong() and 0xff) shl 16) or ((data[o + 3].toLong() and 0xff) shl 24)
        else
            (data[o + 3].toLong() and 0xff) or ((data[o + 2].toLong() and 0xff) shl 8) or
                ((data[o + 1].toLong() and 0xff) shl 16) or ((data[o].toLong() and 0xff) shl 24)

        val dlt = u32(20).toInt()
        // returns bytes to skip to reach the 802.11 frame, or -1 if the link header can't be read
        fun linkSkip(pktOff: Int, end: Int): Int = when (dlt) {
            119 -> 144                                       // PRISM header
            127 -> if (pktOff + 4 <= end)                     // radiotap: length at bytes 2..3 (LE)
                ((data[pktOff + 2].toInt() and 0xff) or ((data[pktOff + 3].toInt() and 0xff) shl 8)) else -1
            else -> 0                                        // 105 raw 802.11
        }

        val ssids = HashMap<String, String>()        // bssid hex -> ssid (utf8)
        val anonces = HashMap<String, ByteArray>()    // "ap|sta" -> ANonce (from M1)
        val lines = ArrayList<String>()
        val seen = HashSet<String>()

        var off = 24
        while (off + 16 <= data.size) {
            val inclLen = u32(off + 8).toInt()
            val pktOff = off + 16
            // Long math: a corrupted huge inclLen must not overflow Int and wrap to a negative offset
            if (inclLen <= 0 || pktOff.toLong() + inclLen > data.size) break
            val end = pktOff + inclLen
            val skip = linkSkip(pktOff, end)
            if (skip >= 0 && pktOff + skip + 24 <= end) {
                parseFrame(data, pktOff + skip, end, ssids, anonces, lines, seen)
            }
            off = end
        }
        return lines
    }

    private fun mac(data: ByteArray, o: Int) = (0 until 6).joinToString("") { "%02x".format(data[o + it]) }

    private fun parseFrame(
        d: ByteArray, f: Int, end: Int,
        ssids: HashMap<String, String>, anonces: HashMap<String, ByteArray>,
        lines: ArrayList<String>, seen: HashSet<String>,
    ) {
        val fc = (d[f].toInt() and 0xff) or ((d[f + 1].toInt() and 0xff) shl 8)
        val type = (fc shr 2) and 0x3
        val subtype = (fc shr 4) and 0xf
        val toDs = (fc shr 8) and 1
        val fromDs = (fc shr 9) and 1

        if (type == 0 && (subtype == 8 || subtype == 5)) { // beacon / probe-resp
            val bssid = mac(d, f + 16)
            var p = f + 24 + 12 // mgmt header + fixed params (timestamp/interval/caps)
            while (p + 2 <= end) {
                val tag = d[p].toInt() and 0xff
                val len = d[p + 1].toInt() and 0xff
                if (p + 2 + len > end) break
                if (tag == 0 && len in 1..32) {
                    val ssid = String(d.copyOfRange(p + 2, p + 2 + len), Charsets.UTF_8)
                    if (ssid.isNotEmpty() && ssid.toByteArray().none { it.toInt() == 0 }) ssids[bssid] = ssid
                }
                p += 2 + len
            }
            return
        }
        if (type != 2) return // only data frames carry EAPOL

        var hdr = 24
        if (toDs == 1 && fromDs == 1) hdr += 6          // addr4
        if ((subtype and 0x8) != 0) hdr += 2            // QoS data
        var llc = f + hdr
        // LLC/SNAP: AA AA 03 00 00 00 <ethertype 88 8E = EAPOL>
        if (llc + 8 > end) return
        if ((d[llc + 6].toInt() and 0xff) != 0x88 || (d[llc + 7].toInt() and 0xff) != 0x8e) return
        val e = llc + 8                                 // 802.1X EAPOL header start
        if (e + 99 > end) return
        if ((d[e + 1].toInt() and 0xff) != 0x03) return // EAPOL-Key type

        // AP/STA from direction
        val ap: String; val sta: String
        if (fromDs == 1) { ap = mac(d, f + 10); sta = mac(d, f + 4) }   // AP->STA (M1/M3)
        else { ap = mac(d, f + 4); sta = mac(d, f + 10) }               // STA->AP (M2/M4)

        val keyInfo = ((d[e + 5].toInt() and 0xff) shl 8) or (d[e + 6].toInt() and 0xff)
        val hasMic = (keyInfo and 0x0100) != 0
        val ack = (keyInfo and 0x0080) != 0
        val keyDataLen = ((d[e + 97].toInt() and 0xff) shl 8) or (d[e + 98].toInt() and 0xff)
        val nonce = d.copyOfRange(e + 17, e + 49)
        val pairKey = "$ap|$sta"

        if (ack && !hasMic) {
            // M1 (AP->STA): remember ANonce; try to pull a PMKID KDE from key data
            anonces[pairKey] = nonce
            val kdStart = e + 99
            if (keyDataLen in 1..(end - kdStart)) {
                val pmkid = findPmkid(d, kdStart, kdStart + keyDataLen)
                if (pmkid != null) {
                    val essid = ssids[ap]
                    val key = "01|$ap|$sta"
                    if (essid != null && seen.add(key)) {
                        lines.add("WPA*01*$pmkid*$ap*$sta*${essid.toByteArray().joinToString("") { "%02x".format(it) }}***")
                    }
                }
            }
        } else if (hasMic && !ack) {
            // M2 (STA->AP): SNonce + MIC; combine with the M1 ANonce for this pair
            val anonce = anonces[pairKey] ?: return
            val mic = (81..96).joinToString("") { "%02x".format(d[e + it]) }
            val eapolLen = 4 + 95 + keyDataLen
            if (e + eapolLen > end) return
            val eapol = d.copyOfRange(e, e + eapolLen).copyOf()
            for (i in 81..96) eapol[i] = 0                      // zero the MIC field for hashing
            val essid = ssids[ap] ?: return
            val key = "02|$ap|$sta"
            if (seen.add(key)) {
                val sb = StringBuilder("WPA*02*").append(mic).append('*').append(ap).append('*').append(sta).append('*')
                    .append(essid.toByteArray().joinToString("") { "%02x".format(it) }).append('*')
                    .append(anonce.joinToString("") { "%02x".format(it) }).append('*')
                    .append(eapol.joinToString("") { "%02x".format(it) }).append('*').append("02")
                lines.add(sb.toString())
            }
        }
    }

    /** Scan RSN key-data for a PMKID KDE: DD <len> 00 0F AC 04 <16 bytes>. Returns pmkid hex or null. */
    private fun findPmkid(d: ByteArray, start: Int, end: Int): String? {
        var p = start
        while (p + 2 <= end) {
            val id = d[p].toInt() and 0xff
            val len = d[p + 1].toInt() and 0xff
            if (p + 2 + len > end) break
            if (id == 0xDD && len >= 20 &&
                (d[p + 2].toInt() and 0xff) == 0x00 && (d[p + 3].toInt() and 0xff) == 0x0F &&
                (d[p + 4].toInt() and 0xff) == 0xAC && (d[p + 5].toInt() and 0xff) == 0x04
            ) {
                return (p + 6 until p + 22).joinToString("") { "%02x".format(d[it]) }
            }
            p += 2 + len
        }
        return null
    }
}
