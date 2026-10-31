package com.foxfw.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val P = ChameleonProto
private suspend fun activeSlotOf(c: ChameleonBle): Int {
    val (st, d) = c.cmd(P.GET_ACTIVE_SLOT)
    return if (st == P.STATUS_SUCCESS && d != null && d.isNotEmpty()) d[0].toInt() and 0xFF else 0
}

// ---- file export → Android/data/com.foxfw.companion/files/chameleon/ ----
private fun exportDir(): File? = FoxApplication.instance.getExternalFilesDir(null)?.let { File(it, "chameleon").apply { mkdirs() } }
fun exportText(name: String, line: String) { try { exportDir()?.let { File(it, name).appendText(line) } } catch (_: Throwable) {} }
fun exportBytes(name: String, data: ByteArray) { try { exportDir()?.let { File(it, name).writeBytes(data) } } catch (_: Throwable) {} }

// ================= mfkey32 (emulate MIFARE, capture reader auths, crack on-device) =================
suspend fun mfkey32Arm(c: ChameleonBle, onStatus: (String) -> Unit, onDone: () -> Unit) {
    c.cmd(P.CHANGE_DEVICE_MODE, byteArrayOf(P.MODE_TAG.toByte()))
    val (st, _) = c.cmd(P.MF1_SET_DETECTION_EN, byteArrayOf(1))
    onStatus(if (st == P.STATUS_SUCCESS) "Armed (log cleared). Make a MIFARE slot active, present it to a reader so it auths >=2x, then Collect."
             else "Arm failed - set a MIFARE slot first (Slots / Test set).")
    onDone()
}

suspend fun mfkey32Collect(c: ChameleonBle, onLine: (String) -> Unit, onStatus: (String) -> Unit, onDone: () -> Unit) {
    val (cst, cd) = c.cmd(P.MF1_GET_DETECTION_COUNT)
    val count = if (cst == P.STATUS_SUCCESS && cd != null && cd.size >= 4) P.getU32(cd, 0).toInt() else 0
    onStatus("Captured auths: $count")
    if (count < 2) { onStatus("Need >=2 auths on the same sector - present to the reader again."); onDone(); return }
    val logs = ArrayList<ChameleonProto.AuthEntry>(); var index = 0
    while (index < count && logs.size < 48) {
        val (lst, ld) = c.cmd(P.MF1_GET_DETECTION_LOG, P.u32be(index.toLong()), 2500)
        if (lst != P.STATUS_SUCCESS || ld == null) break
        val got = P.parseAuthLog(ld); if (got.isEmpty()) break
        logs.addAll(got); index += got.size
    }
    var pair: Pair<ChameleonProto.AuthEntry, ChameleonProto.AuthEntry>? = null
    outer@ for (i in logs.indices) for (j in i + 1 until logs.size) {
        val a = logs[i]; val b = logs[j]
        if (a.uid != b.uid || a.block != b.block || a.keyB != b.keyB) continue
        if (a.nt == b.nt && a.nr == b.nr && a.ar == b.ar) continue
        pair = a to b; break@outer
    }
    val p = pair ?: run { onStatus("No crackable pair yet - present to the reader again."); onDone(); return }
    if (!ChamCrypto.available) { onStatus("Native crypto unavailable."); onDone(); return }
    exportText("mfkey32.txt", "%08X %08X %08X %08X %08X %08X %08X\n".format(p.first.uid, p.first.nt, p.first.nr, p.first.ar, p.second.nt, p.second.nr, p.second.ar))
    onStatus("Pair captured (saved). Cracking on-device (~1-2 min)...")
    val key = withContext(Dispatchers.Default) {
        ChamCrypto.mfkey32(p.first.uid.toInt(), p.first.nt.toInt(), p.first.nr.toInt(), p.first.ar.toInt(), p.second.nt.toInt(), p.second.nr.toInt(), p.second.ar.toInt())
    }
    if (key >= 0) { onLine("KEY %s block %d: %012X".format(if (p.first.keyB) "B" else "A", p.first.block, key)); onStatus("mfkey32 recovered a key.") }
    else onStatus("No key from the captured pair (try another capture).")
    onDone()
}

// ================= nested acquire (weak-PRNG; save nonces, crack on PC) =================
suspend fun nestedAcquire(c: ChameleonBle, onLine: (String) -> Unit, onStatus: (String) -> Unit, onDone: () -> Unit) {
    readerMode(c)
    if (c.cmd(P.HF14A_SCAN, null, 3000).first != HF_OK) { onStatus("No card on the antenna."); onDone(); return }
    val (pst, pd) = c.cmd(P.MF1_DETECT_PRNG, null, 2500)
    val prng = if (pst == HF_OK && pd != null && pd.isNotEmpty()) pd[0].toInt() and 0xFF else 0xFF
    if (prng != P.PRNG_WEAK) { onStatus("PRNG=$prng - nested needs a weak-PRNG card (1)."); onDone(); return }
    val key = ByteArray(6) { 0xFF.toByte() }
    val (dst, dd) = c.cmd(P.MF1_DETECT_NT_DIST, P.mf1NtDist(P.KEY_A, 0, key), 2500)
    val nd = if (dst == HF_OK && dd != null) P.parseNtDist(dd) else null
    if (nd == null) { onStatus("nt-distance failed (need default key FF on sector 0)."); onDone(); return }
    onLine("UID %08X  dist %d".format(nd.uid, nd.dist))
    exportText("nested.txt", "# uid %08X dist %d knownA blk0 targetA blk4\n".format(nd.uid, nd.dist))
    var saved = 0
    for (s in 0 until 4) {
        val (nst, nr) = c.cmd(P.MF1_NESTED_ACQUIRE, P.mf1Nested(P.KEY_A, 0, key, P.KEY_A, 4), 3000)
        if (nst != HF_OK || nr == null) continue
        for (set in P.parseNested(nr)) { exportText("nested.txt", "%08X %08X %02X\n".format(set.nt1, set.nt2, set.par)); saved++ }
    }
    onLine("nested sets: $saved (saved nested.txt)")
    onStatus(if (saved > 0) "Acquired - crack nested.txt on PC (nested solver)." else "Acquire failed.")
    onDone()
}

// ================= NTAG password capture (emulate NTAG215, log PWD_AUTH) =================
suspend fun ntagPwdArm(c: ChameleonBle, onStatus: (String) -> Unit, onDone: () -> Unit) {
    val slot = activeSlotOf(c)
    c.cmd(P.CHANGE_DEVICE_MODE, byteArrayOf(P.MODE_TAG.toByte()))
    c.cmd(P.SET_SLOT_TAG_TYPE, P.setSlotTagType(slot, P.TT_NTAG_215))
    c.cmd(P.SET_SLOT_ENABLE, P.setSlotEnable(slot, P.SENSE_HF, true))
    val (st, _) = c.cmd(P.MF0_NTAG_SET_DETECTION_EN, byteArrayOf(1))
    onStatus(if (st == P.STATUS_SUCCESS) "NTAG215 emulated + PWD log armed. Present to a reader that PWD-auths, then Collect."
             else "Arm failed (firmware NTAG-detect support?).")
    onDone()
}

suspend fun ntagPwdCollect(c: ChameleonBle, onLine: (String) -> Unit, onStatus: (String) -> Unit, onDone: () -> Unit) {
    val (cst, cd) = c.cmd(P.MF0_NTAG_GET_DETECTION_CNT)
    val count = if (cst == P.STATUS_SUCCESS && cd != null && cd.size >= 4) P.getU32(cd, 0).toInt() else 0
    onStatus("Captured PWDs: $count")
    if (count == 0) { onStatus("Present to a reader that authenticates, then Collect."); onDone(); return }
    val (lst, ld) = c.cmd(P.MF0_NTAG_GET_DETECTION_LOG, null, 2500)
    if (lst == P.STATUS_SUCCESS && ld != null && ld.size >= 4) {
        val n = minOf(ld.size / 4, count)
        for (i in 0 until n) { val px = ld.copyOfRange(i * 4, i * 4 + 4); onLine("PWD ${P.hex(px)}"); exportText("ntag_pwds.txt", P.hex(px) + "\n") }
        onStatus("Saved ntag_pwds.txt.")
    } else onStatus("Read log failed.")
    onDone()
}

// ================= HF sniff (reader<->card trace -> file) =================
suspend fun hfSniff(c: ChameleonBle, onStatus: (String) -> Unit, onDone: () -> Unit) {
    readerMode(c)
    onStatus("Sniffing 5 s - hold the Chameleon between a reader and a card...")
    val (st, d) = c.cmd(P.HF14A_SNIFF, P.u16(5000), 12000)
    if (st != HF_OK && st != P.STATUS_SUCCESS) { onStatus("Sniff failed (firmware support?)."); onDone(); return }
    val n = d?.size ?: 0
    if (n == 0) { onStatus("No frames seen - hold it between reader and card."); onDone(); return }
    exportBytes("hf_sniff.bin", d!!)
    onStatus("Trace $n bytes - saved hf_sniff.bin.")
    onDone()
}

// ================= LF EM4100 emulate (present an ID to an access reader) =================
suspend fun lfEmulateEm410x(c: ChameleonBle, id5: ByteArray, onStatus: (String) -> Unit, onDone: () -> Unit) {
    val slot = activeSlotOf(c)
    c.cmd(P.CHANGE_DEVICE_MODE, byteArrayOf(P.MODE_TAG.toByte()))
    c.cmd(P.SET_ACTIVE_SLOT, byteArrayOf(slot.toByte()))
    c.cmd(P.SET_SLOT_TAG_TYPE, P.setSlotTagType(slot, P.TT_EM410X))
    val (st, _) = c.cmd(P.EM410X_SET_EMU_ID, id5.copyOf(5))
    c.cmd(P.SET_SLOT_ENABLE, P.setSlotEnable(slot, P.SENSE_LF, true))
    onStatus(if (st == P.STATUS_SUCCESS) "Emulating EM410x ${P.hex(id5.copyOf(5))}." else "Set failed.")
    onDone()
}

// ================= Test-set: program the 8 slots with a varied selection =================
suspend fun loadTestset(c: ChameleonBle, onLine: (String) -> Unit, onStatus: (String) -> Unit, onDone: () -> Unit) {
    val set = listOf(
        Triple(0, P.TT_MIFARE_1K to P.SENSE_HF, "MF 1K"),
        Triple(1, P.TT_MIFARE_4K to P.SENSE_HF, "MF 4K"),
        Triple(2, P.TT_NTAG_215 to P.SENSE_HF, "NTAG215"),
        Triple(3, P.TT_NTAG_213 to P.SENSE_HF, "NTAG213"),
        Triple(4, P.TT_ULTRALIGHT to P.SENSE_HF, "Ultralight"),
        Triple(5, P.TT_MIFARE_MINI to P.SENSE_HF, "MF Mini"),
        Triple(6, P.TT_EM410X to P.SENSE_LF, "EM410x"),
        Triple(7, P.TT_MIFARE_2K to P.SENSE_HF, "MF 2K"),
    )
    var ok = 0
    for ((slot, tt, name) in set) {
        val a = c.cmd(P.SET_SLOT_DATA_DEFAULT, P.setSlotDataDefault(slot, tt.first), 2500).first == P.STATUS_SUCCESS
        c.cmd(P.SET_SLOT_ENABLE, P.setSlotEnable(slot, tt.second, true))
        if (a) ok++
        onLine("S${slot + 1} %-10s %s".format(name, if (a) "ok" else "fail"))
    }
    c.cmd(P.SLOT_DATA_CONFIG_SAVE, null, 3000)
    c.cmd(P.SET_ACTIVE_SLOT, byteArrayOf(0))
    onStatus("$ok/8 tags loaded + saved to flash.")
    onDone()
}

// ================= Reader fuzz (present malformed emulated cards to a reader) =================
class FuzzCase(val label: String, val uid: ByteArray, val atqa: ByteArray, val sak: Int, val ats: ByteArray, val tt: Int, val extra: Int)
val FUZZ_CASES = listOf(
    FuzzCase("baseline MF1K", byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), byteArrayOf(0x04, 0x00), 0x08, ByteArray(0), P.TT_MIFARE_1K, 0),
    FuzzCase("UID len 10", byteArrayOf(0x04, 1, 2, 3, 4, 5, 6, 7, 8, 9), byteArrayOf(0x44, 0x00), 0x00, ByteArray(0), P.TT_ULTRALIGHT, 0),
    FuzzCase("UID len 3 (bad)", byteArrayOf(0x11, 0x22, 0x33), byteArrayOf(0x04, 0x00), 0x08, ByteArray(0), P.TT_MIFARE_1K, 0),
    FuzzCase("ISO-DEP bad ATS", byteArrayOf(0x12, 0x34, 0x56, 0x78), byteArrayOf(0x04, 0x03), 0x20, byteArrayOf(0x05, 0x78, 0x80.toByte(), 0x70, 0x02), P.TT_MIFARE_1K, 0),
    FuzzCase("ATS TL=FF", byteArrayOf(0x12, 0x34, 0x56, 0x78), byteArrayOf(0x04, 0x03), 0x20, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), P.TT_MIFARE_1K, 0),
    FuzzCase("ATQA FFFF", byteArrayOf(0x01, 0x02, 0x03, 0x04), byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 0x08, ByteArray(0), P.TT_MIFARE_1K, 0),
    FuzzCase("ATQA 0000", byteArrayOf(0x01, 0x02, 0x03, 0x04), byteArrayOf(0x00, 0x00), 0x00, ByteArray(0), P.TT_MIFARE_1K, 0),
    FuzzCase("MFC bad trailer", byteArrayOf(0x01, 0x02, 0x03, 0x04), byteArrayOf(0x04, 0x00), 0x08, ByteArray(0), P.TT_MIFARE_1K, 1),
    FuzzCase("NTAG215 base", byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66), byteArrayOf(0x44, 0x00), 0x00, ByteArray(0), P.TT_NTAG_215, 0),
)
suspend fun rfuzzApply(c: ChameleonBle, idx: Int, onStatus: (String) -> Unit, onDone: () -> Unit) {
    val rc = FUZZ_CASES.getOrNull(idx) ?: run { onDone(); return }
    val slot = activeSlotOf(c)
    c.cmd(P.CHANGE_DEVICE_MODE, byteArrayOf(P.MODE_TAG.toByte()))
    c.cmd(P.SET_ACTIVE_SLOT, byteArrayOf(slot.toByte()))
    c.cmd(P.SET_SLOT_TAG_TYPE, P.setSlotTagType(slot, rc.tt))
    val ok = c.cmd(P.HF14A_SET_ANTI_COLL, P.setAntiColl(rc.uid, rc.atqa, rc.sak, rc.ats)).first == P.STATUS_SUCCESS
    if (rc.extra == 1) {   // malformed MFC sector trailer at block 3
        val blk = ByteArray(17); for (i in 1..6) blk[i] = 0xFF.toByte(); blk[7] = 0xFF.toByte(); blk[8] = 0xFF.toByte(); blk[9] = 0xFF.toByte(); blk[10] = 0xFF.toByte()
        c.cmd(P.MF1_WRITE_EMU_BLOCK, P.writeEmuBlock(3, blk.copyOfRange(1, 17)))
    }
    onStatus(if (ok) "Presenting '${rc.label}' to a reader." else "Apply failed.")
    onDone()
}

// ================= hardnested / enc-nested / static-nested acquire (save for PC crack) =================
suspend fun hardnestedAcquire(c: ChameleonBle, onLine: (String) -> Unit, onStatus: (String) -> Unit, onDone: () -> Unit) {
    readerMode(c)
    val (st, d) = c.cmd(P.HF14A_SCAN, null, 3000)
    val tag = if (st == HF_OK && d != null) P.parseHf14a(d) else null
    if (tag == null) { onStatus("No MIFARE card on the antenna."); onDone(); return }
    var known = ByteArray(6) { 0xFF.toByte() }
    val (cst, cd) = c.cmd(P.MF1_CHECK_KEYS_ON_BLOCK, P.mf1CheckKeysOnBlock(3, P.KEY_A, MF_DICT), 5000)
    if (cst == HF_OK && cd != null && cd.size >= 7 && cd[0].toInt() != 0) known = cd.copyOfRange(1, 7)
    onStatus("Hardnested acquire - this can take a while...")
    val (ast, ad) = c.cmd(P.MF1_HARDNESTED_ACQUIRE, P.mf1Hardnested(0, P.KEY_A, 0, known, P.KEY_A, 4), 20000)
    if (ast != HF_OK || ad == null || ad.isEmpty()) { onStatus("Acquire failed (need a known key on blk0 - run Dictionary)."); onDone(); return }
    val name = "hardnested-${P.hex(tag.uid)}.bin"; exportBytes(name, ad)
    onLine("nonces: ${ad.size} bytes")
    onStatus("Acquired - saved $name. Crack on PC (hardnested).")
    onDone()
}

suspend fun encNestedAcquire(c: ChameleonBle, onLine: (String) -> Unit, onStatus: (String) -> Unit, onDone: () -> Unit) {
    readerMode(c)
    val (st, d) = c.cmd(P.HF14A_SCAN, null, 3000)
    val tag = if (st == HF_OK && d != null) P.parseHf14a(d) else null
    if (tag == null) { onStatus("No card on the antenna."); onDone(); return }
    onStatus("Enc-nested acquire (Fudan FM11RF08S backdoor)...")
    val (ast, ad) = c.cmd(P.MF1_ENC_NESTED_ACQUIRE, P.mf1EncNested(P.FM_BACKDOOR, 16, 0), 10000)
    if (ast != HF_OK || ad == null || ad.isEmpty()) { onStatus("Not a backdoor card (Fudan FM11RF08S only)."); onDone(); return }
    val name = "encnested-${P.hex(tag.uid)}.bin"; exportBytes(name, ad)
    onLine("nonces: ${ad.size} bytes")
    onStatus("Acquired - saved $name. Crack on PC.")
    onDone()
}

suspend fun staticNestedAcquire(c: ChameleonBle, onLine: (String) -> Unit, onStatus: (String) -> Unit, onDone: () -> Unit) {
    readerMode(c)
    if (c.cmd(P.HF14A_SCAN, null, 3000).first != HF_OK) { onStatus("No card on the antenna."); onDone(); return }
    val (pst, pd) = c.cmd(P.MF1_DETECT_PRNG, null, 2500)
    val prng = if (pst == HF_OK && pd != null && pd.isNotEmpty()) pd[0].toInt() and 0xFF else 0xFF
    if (prng != P.PRNG_STATIC) { onStatus("PRNG=$prng - static-nested needs a static-PRNG card (0)."); onDone(); return }
    val key = ByteArray(6) { 0xFF.toByte() }
    val (ast, ad) = c.cmd(P.MF1_STATIC_NESTED_ACQUIRE, P.mf1Nested(P.KEY_A, 0, key, P.KEY_A, 4), 4000)
    if (ast != HF_OK || ad == null || ad.size < 4) { onStatus("Acquire failed (need default key FF on sector 0)."); onDone(); return }
    exportText("staticnested.txt", "# uid ${P.hex(ad.copyOf(4))}\n")
    var off = 4; var sets = 0
    while (off + 8 <= ad.size) { exportText("staticnested.txt", "%08X %08X\n".format(P.getU32(ad, off), P.getU32(ad, off + 4))); off += 8; sets++ }
    onLine("UID ${P.hex(ad.copyOf(4))} - static sets: $sets (saved)")
    onStatus(if (sets > 0) "Acquired - crack on PC (staticnested)." else "No sets captured.")
    onDone()
}
