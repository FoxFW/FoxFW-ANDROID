package com.foxfw.companion

/**
 * Richer on-phone analysis of a read HF tag: identity (UID/ATQA/SAK), memory structure, and — for
 * NTAG/Ultralight (NFC Forum Type-2) — the decoded NDEF records (URI / Text / etc.). Works on the
 * [HfTag] the Flipper read parses into. Pure Kotlin, no device needed.
 */
object NfcParse {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun analyze(t: HfTag): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += "Type" to t.type.label
        out += "UID" to "${hex(t.uid)}  (${t.uid.size} bytes)"
        if (t.atqa.isNotEmpty() && t.atqa.any { it.toInt() != 0 }) out += "ATQA" to hex(t.atqa)
        out += "SAK" to "%02X".format(t.sak)
        when (t.type) {
            TagType.MIFARE_CLASSIC_1K -> out += "Memory" to "1K · 16 sectors · ${t.blocks.size}/64 blocks read"
            TagType.MIFARE_CLASSIC_4K -> out += "Memory" to "4K · 40 sectors · ${t.blocks.size}/256 blocks read"
            TagType.MIFARE_ULTRALIGHT, TagType.NTAG213, TagType.NTAG215, TagType.NTAG216 -> {
                out += "Memory" to "${t.blocks.size} pages × 4 B (${t.blocks.size * 4} B)"
                val ndef = decodeNdef(t.blocks)
                if (ndef.isEmpty()) out += "NDEF" to "none / empty"
                else ndef.forEachIndexed { i, r -> out += "NDEF ${i + 1}" to r }
            }
            else -> {}
        }
        return out
    }

    // NFC Forum URI identifier abbreviation table.
    private val URI_PREFIX = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://",
        "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:",
        "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://",
        "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    /** Type-2 memory: user data starts at page 4 (0-2 = UID/BCC, 3 = CC). Walk the TLV, pull the
     *  NDEF message TLV (0x03), parse its records. Bounds-checked for truncated reads. */
    fun decodeNdef(pages: List<ByteArray>): List<String> {
        if (pages.size <= 4) return emptyList()
        val data = ByteArray((pages.size - 4) * 4)
        for (p in 4 until pages.size) {
            val pg = pages[p]
            for (k in 0 until 4) data[(p - 4) * 4 + k] = if (k < pg.size) pg[k] else 0
        }
        val recs = ArrayList<String>()
        var i = 0
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF; i++
            if (tag == 0x00) continue          // NULL TLV
            if (tag == 0xFE) break             // terminator
            if (i >= data.size) break
            var len = data[i].toInt() and 0xFF; i++
            if (len == 0xFF) {                 // 3-byte length
                if (i + 1 >= data.size) break
                len = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF); i += 2
            }
            if (tag == 0x03 && len > 0) {       // NDEF message TLV
                val end = minOf(i + len, data.size)
                recs += parseMessage(data.copyOfRange(i, end))
            }
            i += len
        }
        return recs
    }

    private fun parseMessage(m: ByteArray): List<String> {
        val out = ArrayList<String>()
        var p = 0
        while (p < m.size) {
            val hdr = m[p].toInt() and 0xFF; p++
            val tnf = hdr and 0x07
            val sr = (hdr and 0x10) != 0
            val il = (hdr and 0x08) != 0
            if (p >= m.size) break
            val typeLen = m[p].toInt() and 0xFF; p++
            val payLen: Int
            if (sr) { if (p >= m.size) break; payLen = m[p].toInt() and 0xFF; p++ }
            else {
                if (p + 3 >= m.size) break
                payLen = ((m[p].toInt() and 0xFF) shl 24) or ((m[p + 1].toInt() and 0xFF) shl 16) or
                    ((m[p + 2].toInt() and 0xFF) shl 8) or (m[p + 3].toInt() and 0xFF); p += 4
            }
            val idLen = if (il) { if (p >= m.size) break; val v = m[p].toInt() and 0xFF; p++; v } else 0
            if (p + typeLen > m.size) break
            val type = String(m, p, typeLen, Charsets.US_ASCII); p += typeLen
            p += idLen
            val plEnd = minOf(p + payLen, m.size)
            val payload = m.copyOfRange(p.coerceAtMost(m.size), plEnd); p = plEnd
            out += decodeRecord(tnf, type, payload)
            if ((hdr and 0x40) != 0) break      // ME (message end)
        }
        return out
    }

    private fun decodeRecord(tnf: Int, type: String, payload: ByteArray): String = when {
        tnf == 0x01 && type == "U" && payload.isNotEmpty() -> {
            val idx = payload[0].toInt() and 0xFF
            val prefix = if (idx < URI_PREFIX.size) URI_PREFIX[idx] else ""
            "URI  " + prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
        }
        tnf == 0x01 && type == "T" && payload.isNotEmpty() -> {
            val status = payload[0].toInt() and 0xFF
            val langLen = status and 0x3F
            val lang = if (langLen in 1..payload.size - 1) String(payload, 1, langLen, Charsets.US_ASCII) else "?"
            val txtStart = 1 + langLen
            val text = if (txtStart <= payload.size) String(payload, txtStart, payload.size - txtStart, Charsets.UTF_8) else ""
            "Text ($lang)  $text"
        }
        tnf == 0x02 -> "MIME $type  (${payload.size} B)"
        tnf == 0x03 -> "URI  " + String(payload, Charsets.UTF_8)
        else -> "TNF $tnf ${if (type.isNotEmpty()) "'$type'" else ""} (${payload.size} B)"
    }
}
