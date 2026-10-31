package com.foxfw.companion

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A card captured by a sweep/read, reused by Emulate and Clone. */
class ReadCard(
    val summary: String,
    val hfUid: ByteArray? = null,
    val sak: Int = 0,
    val atqa: ByteArray? = null,
    val blk0: ByteArray? = null,
    val em410x: ByteArray? = null,
)

/** Session state that outlives individual sub-views (last read card, magic mode). */
object ChamSession {
    var lastRead: ReadCard? = null
    var lastEmv: EmvCard? = null
    var magic: Int = 0
}

/** Suspend wrapper over the callback command API. Returns (status, data). */
internal suspend fun ChameleonBle.cmd(cmd: Int, data: ByteArray? = null, timeout: Long = 2500): Pair<Int, ByteArray?> =
    suspendCancellableCoroutine { cont -> command(cmd, data, timeout) { st, d -> if (cont.isActive) cont.resume(st to d) } }

internal val HF_OK = ChameleonProto.STATUS_HF_OK      // 0x00
internal val LF_OK = ChameleonProto.STATUS_LF_OK      // 0x40
private fun hx(b: ByteArray) = ChameleonProto.hex(b)

private fun trailerBlock(s: Int) = if (s < 32) s * 4 + 3 else 128 + (s - 32) * 16 + 15
private fun sectorFirstBlock(s: Int) = if (s < 32) s * 4 else 128 + (s - 32) * 16
private fun sectorBlocks(s: Int) = if (s < 32) 4 else 16
private fun nsecForSak(sak: Int) = when (sak) { 0x18 -> 40; 0x09 -> 5; else -> 16 }

internal suspend fun readerMode(c: ChameleonBle) { c.cmd(ChameleonProto.CHANGE_DEVICE_MODE, byteArrayOf(ChameleonProto.MODE_READER.toByte())) }

/** Fob "read everything": every HF + LF chip type the Chameleon supports. Lines stream to [onLine]. */
suspend fun sweepFob(c: ChameleonBle, onLine: (String) -> Unit, onDone: () -> Unit) {
    readerMode(c)
    var found = false
    // HF14A (MIFARE / NTAG / ISO14443-A)
    c.cmd(ChameleonProto.HF14A_SCAN, null, 3000).let { (st, d) ->
        if (st == HF_OK && d != null) ChameleonProto.parseHf14a(d)?.let { t ->
            found = true
            onLine("HF14A · UID ${hx(t.uid)} · SAK ${"%02X".format(t.sak)} · ATQA ${hx(t.atqa)}")
            ChamSession.lastRead = ReadCard("HF14A UID ${hx(t.uid)} SAK ${"%02X".format(t.sak)}", t.uid, t.sak, t.atqa)
        }
    }
    // LF types
    c.cmd(ChameleonProto.EM410X_SCAN, null, 2500).let { (st, d) -> if (st == LF_OK && d != null) ChameleonProto.parseEm410x(d)?.let { found = true; onLine("EM410x · ${hx(it.id)}"); ChamSession.lastRead = ReadCard("EM410x ${hx(it.id)}", em410x = it.id) } }
    c.cmd(ChameleonProto.EM4X05_SCAN, null, 2500).let { (st, d) -> if (st == LF_OK && d != null) ChameleonProto.parseEm4x05(d)?.let { found = true; onLine("EM4x05 · uid %08X cfg %08X %s".format(it.uid, it.config, if (it.is4x69) "(4x69)" else "")) } }
    c.cmd(ChameleonProto.HIDPROX_SCAN, byteArrayOf(0), 2500).let { (st, d) -> if (st == LF_OK && d != null && d.any { it.toInt() != 0 }) { found = true; onLine("HID Prox · ${hx(d)}") } }
    c.cmd(ChameleonProto.IOPROX_SCAN, null, 2500).let { (st, d) -> if (st == LF_OK && d != null && d.any { it.toInt() != 0 }) { found = true; onLine("ioProx · ${hx(d)}") } }
    c.cmd(ChameleonProto.PAC_SCAN, null, 2500).let { (st, d) -> if (st == LF_OK && d != null && d.any { it.toInt() != 0 }) { found = true; onLine("PAC · ${hx(d)}") } }
    c.cmd(ChameleonProto.VIKING_SCAN, null, 2500).let { (st, d) -> if (st == LF_OK && d != null && d.any { it.toInt() != 0 }) { found = true; onLine("Viking · ${hx(d)}") } }
    c.cmd(ChameleonProto.JABLOTRON_SCAN, null, 2500).let { (st, d) -> if (st == LF_OK && d != null && d.any { it.toInt() != 0 }) { found = true; onLine("Jablotron · ${hx(d)}") } }
    if (!found) ChamSession.lastRead = null
    onDone()
}

/** Dump a MIFARE Classic: dictionary-find a key per sector, then read every block. */
suspend fun dumpMifare(c: ChameleonBle, onLine: (String) -> Unit, onStatus: (String) -> Unit, onDone: () -> Unit) {
    readerMode(c)
    val (st, d) = c.cmd(ChameleonProto.HF14A_SCAN, null, 3000)
    val tag = if (st == HF_OK && d != null) ChameleonProto.parseHf14a(d) else null
    if (tag == null) { onStatus("No MIFARE card on the antenna."); onDone(); return }
    val nsec = nsecForSak(tag.sak)
    onStatus("UID ${hx(tag.uid)} · $nsec sectors — finding keys…")
    ChamSession.lastRead = ReadCard("HF14A UID ${hx(tag.uid)} SAK ${"%02X".format(tag.sak)}", tag.uid, tag.sak, tag.atqa)
    var blk0: ByteArray? = null
    for (s in 0 until nsec) {
        // find a key (A then B) via on-device dictionary check
        var key: ByteArray? = null; var kt = ChameleonProto.KEY_A
        for (type in intArrayOf(ChameleonProto.KEY_A, ChameleonProto.KEY_B)) {
            val (cst, cd) = c.cmd(ChameleonProto.MF1_CHECK_KEYS_ON_BLOCK, ChameleonProto.mf1CheckKeysOnBlock(trailerBlock(s), type, MF_DICT), 5000)
            if (cst == HF_OK && cd != null && cd.size >= 7 && cd[0].toInt() != 0) { key = cd.copyOfRange(1, 7); kt = type; break }
        }
        if (key == null) { onLine("S%02d  locked (no dict key)".format(s)); continue }
        onStatus("Sector $s key ${if (kt == ChameleonProto.KEY_A) "A" else "B"} ${hx(key)} — reading…")
        for (bi in 0 until sectorBlocks(s)) {
            val blk = sectorFirstBlock(s) + bi
            val (rst, rd) = c.cmd(ChameleonProto.MF1_READ_ONE_BLOCK, ChameleonProto.mf1Read(kt, blk, key), 1500)
            if (rst == HF_OK && rd != null && rd.size >= 16) {
                onLine("B%03d  ${hx(rd.copyOf(16))}".format(blk))
                if (blk == 0) blk0 = rd.copyOf(16)
            } else onLine("B%03d  --".format(blk))
        }
    }
    if (blk0 != null) ChamSession.lastRead = ReadCard(ChamSession.lastRead!!.summary, tag.uid, tag.sak, tag.atqa, blk0)
    onStatus("Dump complete.")
    onDone()
}

/** Dictionary attack: report which sectors yield a common key. */
suspend fun dictAttack(c: ChameleonBle, onLine: (String) -> Unit, onStatus: (String) -> Unit, onDone: () -> Unit) {
    readerMode(c)
    val (st, d) = c.cmd(ChameleonProto.HF14A_SCAN, null, 3000)
    val tag = if (st == HF_OK && d != null) ChameleonProto.parseHf14a(d) else null
    if (tag == null) { onStatus("No MIFARE card on the antenna."); onDone(); return }
    val nsec = nsecForSak(tag.sak); var hits = 0
    onStatus("UID ${hx(tag.uid)} · checking ${MF_DICT.size} keys on $nsec sectors…")
    for (s in 0 until nsec) {
        for (type in intArrayOf(ChameleonProto.KEY_A, ChameleonProto.KEY_B)) {
            val (cst, cd) = c.cmd(ChameleonProto.MF1_CHECK_KEYS_ON_BLOCK, ChameleonProto.mf1CheckKeysOnBlock(trailerBlock(s), type, MF_DICT), 5000)
            if (cst == HF_OK && cd != null && cd.size >= 7 && cd[0].toInt() != 0) { onLine("S%02d %s %s".format(s, if (type == ChameleonProto.KEY_A) "A" else "B", hx(cd.copyOfRange(1, 7)))); hits++ }
        }
    }
    onStatus("Found $hits / ${nsec * 2} keys.")
    onDone()
}

/** Darkside: acquire NACK rounds from a card, recover a key on-device, validate it. */
suspend fun darksideAttack(c: ChameleonBle, onLine: (String) -> Unit, onStatus: (String) -> Unit, onDone: () -> Unit) {
    if (!ChamCrypto.available) { onStatus("Native crypto unavailable."); onDone(); return }
    readerMode(c)
    val (st, d) = c.cmd(ChameleonProto.HF14A_SCAN, null, 3000)
    if (st != HF_OK || d == null) { onStatus("No MIFARE card on the antenna."); onDone(); return }
    // acquire up to 3 rounds against sector-0 trailer, key A
    val uids = ArrayList<Int>(); val nts = ArrayList<Int>(); val nrs = ArrayList<Int>(); val ars = ArrayList<Int>()
    val pars = ArrayList<Byte>(); val kss = ArrayList<Byte>()
    for (round in 0 until 3) {
        onStatus("Darkside acquire round ${round + 1}/3…")
        val (ast, ad) = c.cmd(ChameleonProto.MF1_DARKSIDE_ACQUIRE, ChameleonProto.mf1Darkside(ChameleonProto.KEY_A, 3, if (round == 0) 1 else 0, 30), 8000)
        if (ast != HF_OK || ad == null) { onStatus("Acquire failed (card not darkside-vulnerable?)."); onDone(); return }
        val ds = ChameleonProto.parseDarkside(ad)
        if (ds == null || !ds.full) { onLine("round ${round + 1}: partial, retrying"); continue }
        uids.add(ds.uid.toInt()); nts.add(ds.nt.toInt()); nrs.add(ds.nr.toInt()); ars.add(ds.ar.toInt())
        ds.par.forEach { pars.add(it) }; ds.ks.forEach { kss.add(it) }
    }
    if (uids.isEmpty()) { onStatus("No full darkside rounds captured."); onDone(); return }
    onStatus("Recovering key on-device…")
    val cands = ChamCrypto.darkside(uids.toIntArray(), nts.toIntArray(), nrs.toIntArray(), ars.toIntArray(), pars.toByteArray(), kss.toByteArray(), uids.size)
    if (cands == null || cands.isEmpty()) { onStatus("No candidate keys from darkside."); onDone(); return }
    onStatus("Validating ${cands.size} candidate(s)…")
    for (cand in cands) {
        val key = ByteArray(6) { ((cand ushr ((5 - it) * 8)) and 0xFF).toByte() }
        val (vst, _) = c.cmd(ChameleonProto.MF1_CHECK_KEYS_ON_BLOCK, ChameleonProto.mf1CheckKeysOnBlock(3, ChameleonProto.KEY_A, listOf(key)), 4000)
        if (vst == HF_OK) { onLine("KEY A (sector 0): ${hx(key)}"); onStatus("Darkside recovered a key."); onDone(); return }
    }
    onStatus("Candidates found but none validated — try again.")
    onDone()
}

/** Program the active slot from a captured read so the Chameleon emulates it. */
suspend fun emulate(c: ChameleonBle, read: ReadCard, onStatus: (String) -> Unit, onDone: () -> Unit) {
    if (read.em410x != null) {
        c.cmd(ChameleonProto.SET_SLOT_TAG_TYPE, ChameleonProto.setSlotTagType(activeSlot(c), ChameleonProto.TT_EM410X))
        val (st, _) = c.cmd(ChameleonProto.EM410X_SET_EMU_ID, ChameleonProto.em410xSetEmuId(read.em410x))
        onStatus(if (st == ChameleonProto.STATUS_SUCCESS) "Emulating EM410x ${hx(read.em410x)}." else "Set emu id failed.")
    } else if (read.hfUid != null) {
        val slot = activeSlot(c)
        c.cmd(ChameleonProto.SET_SLOT_DATA_DEFAULT, ChameleonProto.setSlotDataDefault(slot, ChameleonProto.TT_MIFARE_1K)) // MIFARE 1K
        c.cmd(ChameleonProto.HF14A_SET_ANTI_COLL, ChameleonProto.setAntiColl(read.hfUid, read.atqa ?: byteArrayOf(0, 4), read.sak))
        if (read.blk0 != null) c.cmd(ChameleonProto.MF1_WRITE_EMU_BLOCK, ChameleonProto.writeEmuBlock(0, read.blk0))
        val (st, _) = c.cmd(ChameleonProto.SLOT_DATA_CONFIG_SAVE)
        onStatus(if (st == ChameleonProto.STATUS_SUCCESS) "Emulating UID ${hx(read.hfUid)} (MIFARE 1K)." else "Emulate configured (save status ${"%02X".format(st)}).")
    } else onStatus("Nothing to emulate.")
    onDone()
}

/** Write a captured MIFARE UID (block 0) onto a gen1a magic card via the backdoor sequence. */
suspend fun writeGen1aUid(c: ChameleonBle, read: ReadCard, onStatus: (String) -> Unit, onDone: () -> Unit) {
    val uid = read.hfUid ?: run { onStatus("Read a MIFARE card first."); onDone(); return }
    readerMode(c)
    val blk0 = read.blk0 ?: ByteArray(16).also {
        val ul = minOf(4, uid.size); System.arraycopy(uid, 0, it, 0, ul)
        it[4] = (uid[0].toInt() xor uid[1].toInt() xor uid[2].toInt() xor uid[3].toInt()).toByte()
        it[5] = read.sak.toByte(); val a = read.atqa ?: byteArrayOf(0, 4); it[6] = a[0]; it[7] = a[1]
    }
    suspend fun raw(opts: Int, bitlen: Int, data: ByteArray): Boolean {
        val (st, rd) = c.cmd(ChameleonProto.HF14A_RAW, ChameleonProto.hf14aRaw(opts, 100, bitlen, data), 2000)
        return st == HF_OK && rd != null && rd.isNotEmpty() && (rd[0].toInt() and 0xFF) == 0x0A
    }
    var okw = false
    for (attempt in 0 until 5) {
        c.cmd(ChameleonProto.HF14A_RAW, ChameleonProto.hf14aRaw(0xF4, 100, 8, byteArrayOf(0x00)), 2000) // field reset
        if (!raw(0xC8, 7, byteArrayOf(0x40))) continue                     // WUPC1
        if (!raw(0xC8, 8, byteArrayOf(0x43))) continue                     // WUPC2
        if (!raw(0xE8, 16, byteArrayOf(0xA0.toByte(), 0x00))) continue      // write addr (block 0)
        if (!raw(0xE8, 128, blk0)) continue                                // write 16 data bytes
        okw = true; break
    }
    onStatus(if (okw) "Block 0 written — UID cloned to gen1a card." else "gen1a unlock failed (magic card on coil?)")
    onDone()
}

private suspend fun activeSlot(c: ChameleonBle): Int {
    val (st, d) = c.cmd(ChameleonProto.GET_ACTIVE_SLOT)
    return if (st == ChameleonProto.STATUS_SUCCESS && d != null && d.isNotEmpty()) d[0].toInt() and 0xFF else 0
}

// ---- ISO-DEP (ISO14443-4) over CH_HF14A_RAW, for EMV bank-card reads (ported from onyx cham_apdu) ----
private class IsoDep(val c: ChameleonBle) { var bn = 0 }

private suspend fun rawXcv(c: ChameleonBle, opts: Int, bitlen: Int, data: ByteArray): ByteArray? {
    val (_, d) = c.cmd(ChameleonProto.HF14A_RAW, ChameleonProto.hf14aRaw(opts, 500, bitlen, data), 2500)
    return if (d != null && d.isNotEmpty()) d else null   // trust the card bytes regardless of device status
}

private suspend fun isoRats(c: ChameleonBle): IsoDep? {
    val opts = EmvCore.RAW_ACTIVATE_FIELD or EmvCore.RAW_WAIT_RESP or EmvCore.RAW_AUTO_SELECT or EmvCore.RAW_KEEP_FIELD
    repeat(3) {
        val (st, _) = c.cmd(ChameleonProto.HF14A_RAW, ChameleonProto.hf14aRaw(opts, 500, 0, ByteArray(0)), 2500)
        if (st == HF_OK) return IsoDep(c)
    }
    return null
}

private suspend fun isoApdu(iso: IsoDep, apdu: ByteArray): ByteArray? {
    var tx = ByteArray(apdu.size + 1); tx[0] = (0x02 or iso.bn).toByte(); System.arraycopy(apdu, 0, tx, 1, apdu.size)
    var txlen = tx.size
    val got = ArrayList<Byte>()
    val opt = EmvCore.RAW_ACTIVATE_FIELD or EmvCore.RAW_WAIT_RESP or EmvCore.RAW_APPEND_CRC or EmvCore.RAW_KEEP_FIELD
    repeat(60) {
        val rx = rawXcv(iso.c, opt, txlen * 8, tx.copyOf(txlen)) ?: return if (got.isNotEmpty()) got.toByteArray() else null
        val pcb = rx[0].toInt() and 0xFF
        when {
            pcb and 0xC0 == 0xC0 -> {                                  // S-block
                if (pcb and 0x30 == 0x30) { val wtxm = if (rx.size >= 2) rx[1].toInt() and 0x3F else 1; tx = byteArrayOf(0xF2.toByte(), wtxm.toByte()); txlen = 2; return@repeat }
                return if (got.isNotEmpty()) got.toByteArray() else null
            }
            pcb and 0xC0 == 0x00 -> {                                  // I-block
                val dlen = rx.size - 3                                 // strip PCB(1) + CRC(2)
                if (dlen > 0) for (k in 0 until dlen) got.add(rx[1 + k])
                iso.bn = iso.bn xor 1
                if (pcb and 0x10 != 0) { tx = byteArrayOf((0xA2 or (iso.bn and 1)).toByte()); txlen = 1; return@repeat }   // chaining -> R(ACK)
                return got.toByteArray()
            }
            else -> return if (got.isNotEmpty()) got.toByteArray() else null
        }
    }
    return if (got.isNotEmpty()) got.toByteArray() else null
}

private suspend fun isoRelease(c: ChameleonBle) {
    rawXcv(c, EmvCore.RAW_ACTIVATE_FIELD or EmvCore.RAW_WAIT_RESP or EmvCore.RAW_APPEND_CRC, 8, byteArrayOf(0xC2.toByte()))  // S(DESELECT), field dropped
}

/** EMV / contactless bank-card read: activate ISO-DEP, run the EMV flow, render the plaintext fields. */
suspend fun emvRead(c: ChameleonBle, onLine: (String) -> Unit, onStatus: (String) -> Unit, onDone: () -> Unit) {
    readerMode(c)
    val (st, d) = c.cmd(ChameleonProto.HF14A_SCAN, null, 3000)
    val tag = if (st == HF_OK && d != null) ChameleonProto.parseHf14a(d) else null
    if (tag == null) { onStatus("No card on the antenna."); onDone(); return }
    if (tag.sak and 0x20 == 0) { onStatus("Not an ISO-DEP card (SAK %02X) — bank cards are ISO14443-4.".format(tag.sak)); onDone(); return }
    onStatus("ISO-DEP card — reading EMV…")
    val iso = isoRats(c) ?: run { onStatus("ISO-DEP activation failed (hold the card still)."); onDone(); return }
    val card = EmvCore.run { apdu -> isoApdu(iso, apdu) }
    isoRelease(c)
    if (!card.valid) { onStatus("ISO-DEP OK but no payment app parsed (not an EMV card?)."); onDone(); return }
    ChamSession.lastEmv = card
    EmvCore.describe(card).forEach(onLine)
    onStatus("EMV read complete.")
    onDone()
}
