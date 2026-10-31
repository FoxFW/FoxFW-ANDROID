package com.foxfw.companion

/**
 * EMV / contactless bank-card read flow, ported from the proven onyx `emv_core.cpp`
 * (itself from the Flipper EMV poller): PPSE → AID → GPO → READ RECORD (AFL) → GET DATA → log.
 * The ISO-DEP transport is a suspend APDU exchange supplied by the caller (over the Chameleon).
 */
class EmvCard {
    var valid = false
    val aid = ByteArray(16); var aidLen = 0
    val aid2 = ByteArray(16); var aid2Len = 0
    var appLabel = ""; var appName = ""
    val pan = ByteArray(10); var panLen = 0; var panStr = ""
    var panSeq = 0
    var expY = 0; var expM = 0; var expD = 0
    var effY = 0; var effM = 0; var effD = 0
    var cardholder = ""
    var countryCode = 0; var currencyCode = 0; var serviceCode = 0
    val aip = ByteArray(2)
    var atc = 0; var lastOnlineAtc = 0; var pinTry = 0xFF
    val track2 = ByteArray(24); var track2Len = 0
    val track1 = ByteArray(48); var track1Len = 0
    val cvm = ByteArray(48); var cvmLen = 0
    val auc = ByteArray(2); var hasAuc = false
    val appVer = ByteArray(2); var hasAppVer = false
    var lang = ""
    val ffi = ByteArray(8); var ffiLen = 0
    class Txn(var amount: Long = 0, var currency: Int = 0, var country: Int = 0, var date: Long = 0, var time: Long = 0)
    val txn = ArrayList<Txn>()
    // raw captured TLVs (tag -> value), order-preserving, deduped
    val rawTlv = LinkedHashMap<Int, ByteArray>()
}

object EmvCore {
    // CH_HF14A_RAW option bits
    const val RAW_ACTIVATE_FIELD = 0x80
    const val RAW_WAIT_RESP = 0x40
    const val RAW_APPEND_CRC = 0x20
    const val RAW_AUTO_SELECT = 0x10
    const val RAW_KEEP_FIELD = 0x08

    private fun i(b: ByteArray, k: Int) = b[k].toInt() and 0xFF

    // PDOL default terminal values (from the Flipper/onyx port)
    private val PDOL_VALS: Map<Int, ByteArray> = mapOf(
        0x9F59 to byteArrayOf(0xC8.toByte(), 0x80.toByte(), 0x00), 0x9F5A to byteArrayOf(0x00), 0x9F58 to byteArrayOf(0x01),
        0x9F66 to byteArrayOf(0x79, 0x00, 0x40, 0x80.toByte()), 0x9F40 to byteArrayOf(0x79, 0x00, 0x40, 0x80.toByte()),
        0x9F02 to byteArrayOf(0, 0, 0, 0x10, 0, 0), 0x9F03 to ByteArray(6), 0x9F1A to byteArrayOf(0x01, 0x24),
        0x5F2A to byteArrayOf(0x01, 0x24), 0x95 to ByteArray(5), 0x9A to byteArrayOf(0x19, 0x01, 0x01),
        0x9C to byteArrayOf(0x00), 0x98 to ByteArray(20), 0x9F37 to byteArrayOf(0x82.toByte(), 0x3D, 0xDE.toByte(), 0x7A),
    )

    // --- BER-TLV helpers ---
    private fun findTag(b: ByteArray, len: Int, want: Int): ByteArray? {
        var p = 0
        while (p < len) {
            val b0 = i(b, p); if (b0 == 0x00 || b0 == 0xFF) { p++; continue }
            var tag = i(b, p); p++
            if (b0 and 0x1F == 0x1F) { if (p < len) { tag = (tag shl 8) or i(b, p); p++ } }
            if (p >= len) break
            var l = i(b, p); p++
            if (l and 0x80 != 0) { var nb = l and 0x7F; if (nb > 4) break; l = 0; while (nb-- > 0 && p < len) { l = (l shl 8) or i(b, p); p++ } }
            if (l < 0 || l > len - p) break
            if (tag == want) return b.copyOfRange(p, p + l)
            p += l
        }
        return null
    }

    private fun walk(b: ByteArray, len: Int, out: EmvCard, depth: Int, g: Scratch) {
        if (depth > 8) return
        var p = 0
        while (p < len) {
            val b0 = i(b, p); if (b0 == 0x00 || b0 == 0xFF) { p++; continue }
            var tag = i(b, p); p++
            if (b0 and 0x1F == 0x1F) { if (p < len) { tag = (tag shl 8) or i(b, p); p++ } }
            if (p >= len) break
            var l = i(b, p); p++
            if (l and 0x80 != 0) { var nb = l and 0x7F; if (nb > 4) break; l = 0; while (nb-- > 0 && p < len) { l = (l shl 8) or i(b, p); p++ } }
            if (l < 0 || l > len - p) break
            val v = b.copyOfRange(p, p + l)
            if (b0 and 0x20 != 0) walk(v, l, out, depth + 1, g)      // constructed -> recurse into value
            else { storeTag(tag, v, l, out, g); if (!out.rawTlv.containsKey(tag)) out.rawTlv[tag] = v.copyOf(minOf(l, 32)) }
            p += l
        }
    }

    private class Scratch { var pdol = ByteArray(0); var afl = ByteArray(0); var logSfi = 0; var logRecs = 0 }

    private fun storeTag(tag: Int, v: ByteArray, l: Int, o: EmvCard, g: Scratch) {
        when (tag) {
            0x4F -> if (o.aidLen == 0 && l in 1..16) { System.arraycopy(v, 0, o.aid, 0, l); o.aidLen = l }
                    else if (o.aidLen != 0 && o.aid2Len == 0 && l in 1..16 && !(l == o.aidLen && v.copyOf(l).contentEquals(o.aid.copyOf(l)))) { System.arraycopy(v, 0, o.aid2, 0, l); o.aid2Len = l }
            0x50 -> if (l > 0) o.appLabel = String(v, 0, l).trim()
            0x9F12 -> if (l > 0) o.appName = String(v, 0, l).trim()
            0x82 -> if (l >= 2) { o.aip[0] = v[0]; o.aip[1] = v[1] }
            0x9F38 -> if (l > 0) g.pdol = v.copyOf(l)
            0x94 -> if (l > 0) g.afl = v.copyOf(l)
            0x80 -> if (l >= 2) { o.aip[0] = v[0]; o.aip[1] = v[1]; g.afl = v.copyOfRange(2, l) }   // GPO format 1
            0x5A -> if (l in 1..10) { System.arraycopy(v, 0, o.pan, 0, l); o.panLen = l }
            0x5F24 -> if (l >= 2) { o.expY = i(v, 0); o.expM = i(v, 1); o.expD = if (l >= 3) i(v, 2) else 0 }
            0x5F34 -> if (l >= 1) o.panSeq = i(v, 0)
            0x5F25 -> if (l >= 2) { o.effY = i(v, 0); o.effM = i(v, 1) }
            0x5F20 -> if (l > 0) o.cardholder = String(v, 0, l).trimEnd()
            0x5F28 -> if (l >= 2) o.countryCode = (i(v, 0) shl 8) or i(v, 1)
            0x9F42 -> if (l >= 2) o.currencyCode = (i(v, 0) shl 8) or i(v, 1)
            0x9F17 -> if (l >= 1) o.pinTry = i(v, 0)
            0x9F36 -> if (l >= 2) o.atc = (i(v, 0) shl 8) or i(v, 1)
            0x9F13 -> if (l >= 2) o.lastOnlineAtc = (i(v, 0) shl 8) or i(v, 1)
            0x9F4D -> if (l >= 2) { g.logSfi = i(v, 0); g.logRecs = i(v, 1) }
            0x57, 0x9F6B -> {
                if (l in 1..24) { System.arraycopy(v, 0, o.track2, 0, l); o.track2Len = l }
                if (o.panLen == 0) decodeTrack2(v, l, o)
            }
            0x9F08 -> if (l >= 2) { o.appVer[0] = v[0]; o.appVer[1] = v[1]; o.hasAppVer = true }
            0x5F2D -> if (l > 0) o.lang = String(v, 0, l).trim()
            0x9F07 -> if (l >= 2) { o.auc[0] = v[0]; o.auc[1] = v[1]; o.hasAuc = true }
            0x9F6E -> if (l in 1..8) { System.arraycopy(v, 0, o.ffi, 0, l); o.ffiLen = l }
            0x8E -> if (l in 1..48) { System.arraycopy(v, 0, o.cvm, 0, l); o.cvmLen = l }
            0x56 -> if (l in 1..48) { System.arraycopy(v, 0, o.track1, 0, l); o.track1Len = l }
        }
    }

    private fun decodeTrack2(v: ByteArray, l: Int, o: EmvCard) {
        val nib = IntArray(40); var nn = 0
        var idx = 0
        while (idx < l && nn < 40) { nib[nn++] = i(v, idx) shr 4; if (nn < 40) nib[nn++] = i(v, idx) and 0x0F; idx++ }
        var d = -1; for (k in 0 until nn) if (nib[k] == 0x0D) { d = k; break }
        if (d > 0) {
            var pl = d; if (pl > 20) pl = 20; o.panLen = (pl + 1) / 2
            for (k in 0 until o.panLen) o.pan[k] = (((nib[k * 2] shl 4) or (if (k * 2 + 1 < d) nib[k * 2 + 1] else 0x0F))).toByte()
            if (d + 4 < nn) { o.expY = (nib[d + 1] shl 4) or nib[d + 2]; o.expM = (nib[d + 3] shl 4) or nib[d + 4] }
            if (d + 7 < nn) o.serviceCode = nib[d + 5] * 100 + nib[d + 6] * 10 + nib[d + 7]
        }
    }

    private fun buildPdol(g: Scratch): ByteArray {
        val out = ArrayList<Byte>(); var p = 0
        while (p < g.pdol.size) {
            val b0 = i(g.pdol, p); var tag = i(g.pdol, p); p++
            if (b0 and 0x1F == 0x1F && p < g.pdol.size) { tag = (tag shl 8) or i(g.pdol, p); p++ }
            if (p >= g.pdol.size) break
            val need = i(g.pdol, p); p++
            val vv = PDOL_VALS[tag]
            for (k in 0 until need) out.add(if (vv != null && k < vv.size) vv[k] else 0)
        }
        return out.toByteArray()
    }

    private fun formatPan(o: EmvCard) {
        val sb = StringBuilder(); var digits = 0
        for (k in 0 until o.panLen) for (half in 0 until 2) {
            val nib = if (half == 1) (i(o.pan, k) and 0x0F) else (i(o.pan, k) shr 4)
            if (nib == 0x0F) { o.panStr = sb.toString(); return }
            if (digits != 0 && digits % 4 == 0) sb.append(' ')
            sb.append(('0' + nib)); digits++
        }
        o.panStr = sb.toString()
    }

    /** Run the EMV flow. [raw] = one raw ISO-DEP APDU exchange (returns resp incl. SW1/SW2, or null). */
    suspend fun run(raw: suspend (ByteArray) -> ByteArray?): EmvCard {
        val out = EmvCard(); val g = Scratch()
        suspend fun apdu(cmd: ByteArray): ByteArray? {
            var r = raw(cmd) ?: return null
            if (r.size == 2 && i(r, 0) == 0x6C && cmd.size >= 5) { val c2 = cmd.copyOf(); c2[c2.size - 1] = r[1]; r = raw(c2) ?: return r }
            if (r.size == 2 && i(r, 0) == 0x61) { r = raw(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, r[1])) ?: return r }
            return r
        }
        // 1) SELECT PPSE
        apdu(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x0E, 0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53, 0x2E, 0x44, 0x44, 0x46, 0x30, 0x31, 0x00))
            ?.let { if (it.size >= 2) walk(it, it.size - 2, out, 0, g) }
        if (out.aidLen == 0) return out
        // 2) SELECT AID
        val cAid = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, out.aidLen.toByte()) + out.aid.copyOf(out.aidLen) + byteArrayOf(0x00)
        apdu(cAid)?.let { if (it.size >= 2) walk(it, it.size - 2, out, 0, g) }
        // 3) GPO (with PDOL)
        val pd = buildPdol(g)
        val cGpo = byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, (2 + pd.size).toByte(), 0x83.toByte(), pd.size.toByte()) + pd + byteArrayOf(0x00)
        apdu(cGpo)?.let { if (it.size >= 2) walk(it, it.size - 2, out, 0, g) }
        if (g.afl.isEmpty()) apdu(byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, 0x02, 0x83.toByte(), 0x00, 0x00))?.let { if (it.size >= 2) walk(it, it.size - 2, out, 0, g) }
        // 4) READ RECORD across AFL
        var ai = 0
        while (ai + 3 < g.afl.size) {
            val sfi = i(g.afl, ai) shr 3; val r0 = i(g.afl, ai + 1); val r1 = i(g.afl, ai + 2)
            if (sfi != 0 && r0 != 0) for (r in r0..r1) {
                apdu(byteArrayOf(0x00, 0xB2.toByte(), r.toByte(), ((sfi shl 3) or 0x04).toByte(), 0x00))?.let { if (it.size >= 2) walk(it, it.size - 2, out, 0, g) }
            }
            ai += 4
        }
        // fallback: brute SFI/record for PAN
        if (out.panLen == 0) outer@ for (sfi in 1..6) for (r in 1..8) {
            val rr = apdu(byteArrayOf(0x00, 0xB2.toByte(), r.toByte(), ((sfi shl 3) or 0x04).toByte(), 0x00))
            if (rr != null && rr.size >= 2 && i(rr, 0) != 0x6A && i(rr, 0) != 0x69) walk(rr, rr.size - 2, out, 0, g)
            if (out.panLen != 0) break@outer
        }
        // 5) GET DATA extras
        for (t in listOf(0x9F17, 0x9F13, 0x9F36)) apdu(byteArrayOf(0x80.toByte(), 0xCA.toByte(), (t shr 8).toByte(), t.toByte(), 0x00))?.let { if (it.size >= 2) walk(it, it.size - 2, out, 0, g) }
        // 6) transaction log
        if (g.logRecs != 0 && g.logSfi != 0) {
            val fmt = apdu(byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x9F.toByte(), 0x4F, 0x00))?.let { if (it.size >= 2) findTag(it, it.size - 2, 0x9F4F) else null }
            if (fmt != null) for (r in 1..minOf(g.logRecs, 12)) {
                val rec = apdu(byteArrayOf(0x00, 0xB2.toByte(), r.toByte(), ((g.logSfi shl 3) or 0x04).toByte(), 0x00)) ?: continue
                if (rec.size < 2) continue
                val t = EmvCard.Txn(); var p = 0; var fi = 0; var any = false
                while (fi < fmt.size && p <= rec.size - 2) {
                    val b0 = i(fmt, fi); var tag = i(fmt, fi); fi++
                    if (b0 and 0x1F == 0x1F && fi < fmt.size) { tag = (tag shl 8) or i(fmt, fi); fi++ }
                    if (fi >= fmt.size) break
                    val fl = i(fmt, fi); fi++; if (p + fl > rec.size - 2) break
                    var acc = 0L; for (k in 0 until fl) acc = (acc shl 8) or i(rec, p + k).toLong()
                    when (tag) { 0x9F02 -> { t.amount = acc; any = true }; 0x5F2A -> { t.currency = acc.toInt(); any = true }; 0x9F1A -> { t.country = acc.toInt(); any = true }; 0x9A -> { t.date = acc; any = true }; 0x9F21 -> { t.time = acc; any = true } }
                    p += fl
                }
                if (any && out.txn.size < 12) out.txn.add(t)
            }
        }
        formatPan(out)
        out.valid = out.panLen > 0 || out.aidLen != 0
        return out
    }

    // ---- summary renderer (port of nfc_emv_describe, key fields) ----
    private fun hex(b: ByteArray, n: Int) = (0 until n).joinToString("") { "%02X".format(b[it].toInt() and 0xFF) }
    private fun scheme(aid: ByteArray, n: Int): String? {
        val h = hex(aid, n)
        return when {
            h.startsWith("A000000003") -> "Visa"; h.startsWith("A000000004") -> "Mastercard"
            h.startsWith("A000000025") -> "Amex"; h.startsWith("A000000065") -> "JCB"
            h.startsWith("A000000152") -> "Discover"; h.startsWith("A000000277") -> "Interac"
            h.startsWith("A0000003330101") -> "UnionPay"; else -> null
        }
    }
    private val CURRENCIES = mapOf(0x826 to "GBP", 0x978 to "EUR", 0x840 to "USD", 0x392 to "JPY", 0x036 to "AUD", 0x124 to "CAD", 0x756 to "CHF")
    private val COUNTRIES = mapOf(0x826 to "GB", 0x978 to "EU", 0x840 to "US", 0x372 to "IE", 0x250 to "FR", 0x276 to "DE")

    fun describe(e: EmvCard): List<String> {
        val out = ArrayList<String>()
        out.add(e.appLabel.ifEmpty { e.appName.ifEmpty { "EMV card" } })
        scheme(e.aid, e.aidLen)?.let { out.add("Scheme $it") }
        if (e.appName.isNotEmpty() && e.appLabel.isNotEmpty() && e.appName != e.appLabel) out.add("App ${e.appName}")
        if (e.aidLen > 0) out.add("AID ${hex(e.aid, e.aidLen)}")
        if (e.aid2Len > 0) out.add("AID2 ${hex(e.aid2, e.aid2Len)}")
        if (e.panStr.isNotEmpty()) out.add("PAN ${e.panStr}")
        if (e.panSeq != 0) out.add("Card seq ${e.panSeq}")
        if (e.expM != 0 || e.expY != 0) out.add("Expires %02X/%02X".format(e.expM, e.expY))
        if (e.effM != 0 || e.effY != 0) out.add("Effective %02X/%02X".format(e.effM, e.effY))
        if (e.cardholder.any { it != '/' && it != ' ' }) out.add("Name ${e.cardholder}")
        if (e.countryCode != 0) out.add("Country ${COUNTRIES[e.countryCode]?.let { "$it (%04X)".format(e.countryCode) } ?: "%04X".format(e.countryCode)}")
        if (e.currencyCode != 0) out.add("Currency ${CURRENCIES[e.currencyCode]?.let { "$it (%04X)".format(e.currencyCode) } ?: "%04X".format(e.currencyCode)}")
        if (e.serviceCode != 0) {
            val d1 = e.serviceCode / 100
            val m = when (d1) { 1 -> "intl"; 2 -> "intl chip"; 5 -> "national"; 6 -> "national chip"; 7 -> "private"; else -> "" }
            out.add("Service %03d %s".format(e.serviceCode, m))
        }
        if (e.aip[0].toInt() != 0 || e.aip[1].toInt() != 0) {
            val a0 = e.aip[0].toInt(); val cap = buildString {
                if (a0 and 0x40 != 0) append("SDA "); if (a0 and 0x20 != 0) append("DDA "); if (a0 and 0x01 != 0) append("CDA ")
                if (a0 and 0x10 != 0) append("CVM "); if (a0 and 0x04 != 0) append("IssAuth "); if (e.aip[1].toInt() and 0x40 != 0) append("Mobile ")
            }
            if (cap.isNotEmpty()) out.add("Auth ${cap.trim()}")
        }
        if (e.track2Len > 0) out.add("Trk2 ${hex(e.track2, e.track2Len)}")
        if (e.hasAppVer) out.add("App ver %02X%02X".format(e.appVer[0], e.appVer[1]))
        if (e.lang.isNotEmpty()) out.add("Lang ${e.lang}")
        if (e.ffiLen > 0) out.add("Form ${if (e.ffi[0].toInt() and 0x20 != 0) "phone/wearable" else "card"} (%02X)".format(e.ffi[0]))
        if (e.atc != 0 || e.pinTry != 0xFF) out.add("ATC ${e.atc}" + if (e.pinTry != 0xFF) "  PIN left ${e.pinTry}" else "")
        for (t in e.txn) out.add("Txn %d.%02d %04X  d%06X".format(t.amount / 100, t.amount % 100, t.currency, t.date))
        return out
    }

    fun rawLines(e: EmvCard): List<String> = e.rawTlv.entries.map { (tag, v) -> "%04X  %s".format(tag, hex(v, v.size)) }
}
