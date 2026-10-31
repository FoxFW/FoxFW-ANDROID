package com.foxfw.companion

/**
 * Builds NDEF messages (URI / Text) and assembles an NTAG215 page image with the
 * standard Capability Container + NDEF TLV, so a "made" NTAG reads as a real NDEF
 * tag. Byte layout follows the NFC Forum URI/Text record specs and the NTAG215
 * memory map (134 pages of 4 bytes; user data from page 4).
 */
object NdefBuilder {
    // URI identifier abbreviations (NFC Forum URI RTD, table 6).
    private val URI_PREFIX = listOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
    )

    /** NDEF URI record bytes (one short record, MB+ME+SR). */
    fun uriRecord(url: String): ByteArray {
        var code = 0
        var bestLen = 0
        for (i in 1 until URI_PREFIX.size) {
            if (url.startsWith(URI_PREFIX[i]) && URI_PREFIX[i].length > bestLen) {
                code = i; bestLen = URI_PREFIX[i].length
            }
        }
        val payload = byteArrayOf(code.toByte()) + url.substring(bestLen).toByteArray(Charsets.UTF_8)
        return shortRecord(tnf = 0x01, type = "U".toByteArray(), payload = payload)
    }

    /** NDEF MIME record carrying a vCard 3.0 contact (type "text/vcard"). */
    fun vcardRecord(name: String, phone: String, email: String): ByteArray {
        val sb = StringBuilder("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        if (name.isNotBlank()) sb.append("FN:").append(name).append("\r\n")
        if (phone.isNotBlank()) sb.append("TEL:").append(phone).append("\r\n")
        if (email.isNotBlank()) sb.append("EMAIL:").append(email).append("\r\n")
        sb.append("END:VCARD\r\n")
        return shortRecord(tnf = 0x02, type = "text/vcard".toByteArray(), payload = sb.toString().toByteArray(Charsets.UTF_8))
    }

    /** NDEF Text record bytes (UTF-8, language code "en"). */
    fun textRecord(text: String, lang: String = "en"): ByteArray {
        val langBytes = lang.toByteArray(Charsets.US_ASCII)
        val status = langBytes.size and 0x3F // UTF-8 (bit7=0), lang length in low bits
        val payload = byteArrayOf(status.toByte()) + langBytes + text.toByteArray(Charsets.UTF_8)
        return shortRecord(tnf = 0x01, type = "T".toByteArray(), payload = payload)
    }

    /** Single NDEF short record with MB(0x80)+ME(0x40)+SR(0x10) flags + TNF. */
    private fun shortRecord(tnf: Int, type: ByteArray, payload: ByteArray): ByteArray {
        require(payload.size < 256) { "payload too large for a short record" }
        val header = (0x80 or 0x40 or 0x10 or (tnf and 0x07))
        return byteArrayOf(header.toByte(), type.size.toByte(), payload.size.toByte()) + type + payload
    }

    /** Wrap an NDEF message in the type-3 (0x03) TLV with a 0xFE terminator. */
    fun ndefTlv(message: ByteArray): ByteArray {
        require(message.size < 255) { "message too large for 1-byte TLV length" }
        return byteArrayOf(0x03, message.size.toByte()) + message + byteArrayOf(0xFE.toByte())
    }

    /** Per-NTAG geometry: total pages and CC byte-3 (memory size / 8). */
    // reserve = trailing pages NOT usable for NDEF (NTAG dynamic-lock + CFG0/CFG1/PWD/PACK); Ultralight
    // (MF0ICU1) has no such trailer, so all of pages 4..15 are user data.
    private data class NtagGeo(val pages: Int, val ccSize: Int, val reserve: Int)
    private fun geo(type: TagType): NtagGeo = when (type) {
        TagType.NTAG213 -> NtagGeo(45, 0x12, 5)   // 144 user bytes
        TagType.NTAG215 -> NtagGeo(135, 0x3E, 5)  // 496 user bytes
        TagType.NTAG216 -> NtagGeo(231, 0x6D, 5)  // 872 user bytes
        else -> NtagGeo(16, 0x06, 0)              // Ultralight: pages 4..15 (48 user bytes), no trailer
    }

    /**
     * Assemble an NTAG/Ultralight page image carrying [ndefMessage], sized for [type].
     * [uid7] must be 7 bytes. Pages 0–2 = UID/BCC/internal/lock, page 3 = CC, page 4+ =
     * NDEF TLV, remainder zero.
     */
    fun ntagPages(type: TagType, uid7: ByteArray, ndefMessage: ByteArray): List<ByteArray> {
        require(uid7.size == 7) { "NTAG UID must be 7 bytes" }
        val g = geo(type)
        val pages = MutableList(g.pages) { ByteArray(4) }
        val bcc0 = (0x88 xor uid7[0].toInt() xor uid7[1].toInt() xor uid7[2].toInt()).toByte()
        val bcc1 = (uid7[3].toInt() xor uid7[4].toInt() xor uid7[5].toInt() xor uid7[6].toInt()).toByte()
        pages[0] = byteArrayOf(uid7[0], uid7[1], uid7[2], bcc0)
        pages[1] = byteArrayOf(uid7[3], uid7[4], uid7[5], uid7[6])
        pages[2] = byteArrayOf(bcc1, 0x48, 0x00, 0x00) // internal + lock bytes (unlocked)
        pages[3] = byteArrayOf(0xE1.toByte(), 0x10, g.ccSize.toByte(), 0x00) // CC: NDEF v1.0, size, r/w
        val tlv = ndefTlv(ndefMessage)
        var p = 4
        var i = 0
        while (i < tlv.size && p < g.pages - g.reserve) {
            val chunk = ByteArray(4)
            for (j in 0 until 4) if (i + j < tlv.size) chunk[j] = tlv[i + j]
            pages[p] = chunk
            p++; i += 4
        }
        return pages
    }
}
