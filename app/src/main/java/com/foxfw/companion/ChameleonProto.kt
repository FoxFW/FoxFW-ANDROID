package com.foxfw.companion

/**
 * ChameleonUltra host protocol — frame codec + command table + parsers. Ported from the proven
 * onyx `chameleon_proto.h` (RRG/nieldk firmware). Frame on the wire:
 *   [0x11][0xEF][cmd:u16 BE][status:u16 BE][len:u16 BE][LRC2][data…][LRC3]
 * LRC = two's-complement of the byte sum. 0xEF is just LRC(0x11), always fixed.
 */
object ChameleonProto {
    const val SOF = 0x11
    const val FRAME_OVH = 10
    const val MAX_DATA = 4096

    // command codes (decimal)
    const val GET_APP_VERSION = 1000
    const val CHANGE_DEVICE_MODE = 1001   // req u8: 1=reader, 0=tag
    const val GET_DEVICE_MODE = 1002      // resp u8: 1=reader, 0=tag
    const val SET_ACTIVE_SLOT = 1003      // req u8 slot(0..7)
    const val SET_SLOT_TAG_TYPE = 1004    // req u8 slot, u16 tagtype
    const val GET_GIT_VERSION = 1017      // resp string
    const val GET_ACTIVE_SLOT = 1018      // resp u8
    const val GET_SLOT_INFO = 1019        // resp 8x{u16 hf, u16 lf}
    const val GET_ENABLED_SLOTS = 1023    // resp 8x{u8 hf_en, u8 lf_en}
    const val GET_BATTERY_INFO = 1025     // resp u16 mV, u8 percent
    const val GET_DEVICE_MODEL = 1033     // resp u8: 0=Ultra, 1=Lite
    const val SET_SLOT_DATA_DEFAULT = 1005  // req u8 slot, u16 tagtype (factory emu data + flash)
    const val SET_SLOT_ENABLE = 1006        // req u8 slot, u8 sense, u8 enable
    const val SLOT_DATA_CONFIG_SAVE = 1009  // persist slot config to flash
    const val HF14A_SCAN = 2000           // resp uidlen|uid|atqa2|sak1|…  (status 0x00 = ok)
    const val MF1_DETECT_SUPPORT = 2001   // tag answers MFC auth at all
    const val MF1_DETECT_PRNG = 2002      // resp u8: 0=static,1=weak,2=hard
    const val MF1_DARKSIDE_ACQUIRE = 2004 // req keytype,block,first,syncmax
    const val MF1_STATIC_NESTED_ACQUIRE = 2003  // PRNG_STATIC: resp uid + 2x{nt1,nt2}
    const val MF1_NESTED_ACQUIRE = 2006   // req known/target
    const val MF1_HARDNESTED_ACQUIRE = 2013     // req slow,knownType,knownBlk,6s key,tgtType,tgtBlk -> NT_ENC
    const val MF1_ENC_NESTED_ACQUIRE = 2014     // req 6s backdoorKey,secCount,startSec (Fudan FM11RF08S)
    const val MF1_DETECT_NT_DIST = 2005   // req known key -> uid,distance
    const val MF1_AUTH_ONE_KEY_BLOCK = 2007 // req u8 keytype, u8 block, 6s key
    const val MF1_READ_ONE_BLOCK = 2008   // req u8 keytype, u8 block, 6s key -> 16 data
    const val MF1_WRITE_ONE_BLOCK = 2009  // req keytype,block,6s key,16s data
    const val HF14A_RAW = 2010            // req opts,u16 timeout,u16 bitlen,data
    const val HF14A_SNIFF = 2020          // HF sniffer: req u16 window ms -> trace bytes
    const val MF1_CHECK_KEYS_ON_BLOCK = 2015 // req block,keyType,count,N*6s keys -> found,6s key
    const val EM410X_SCAN = 3000          // resp u16 type, 5s id  (status 0x40 = ok)
    const val EM410X_WRITE_T55XX = 3001   // req 5s id | 4s new_key | N*4 old_keys
    const val HIDPROX_SCAN = 3002         // resp 13s HID blob
    const val VIKING_SCAN = 3004          // resp 4s id
    const val IOPROX_SCAN = 3010          // resp 16s id
    const val PAC_SCAN = 3014             // resp 8s id
    const val JABLOTRON_SCAN = 3019       // resp 5s id
    const val EM4X05_SCAN = 3030          // resp 13s (config u32|uid u32|uid_hi u32|is4x69 u8)
    const val MF1_WRITE_EMU_BLOCK = 4000  // req u8 start_block, N*16 bytes
    const val HF14A_SET_ANTI_COLL = 4001  // req u8 uidlen, uid, 2s atqa, 1s sak, u8 atslen, ats
    const val MF1_SET_DETECTION_EN = 4004 // req u8 enable (mfkey32 logging)
    const val MF1_GET_DETECTION_COUNT = 4005
    const val MF1_GET_DETECTION_LOG = 4006  // req u32 index -> N*18 entries
    const val MF1_SET_GEN1A_MODE = 4011   // req u8 bool
    const val MF1_SET_GEN2_MODE = 4013    // req u8 bool
    const val MF0_NTAG_WRITE_EMU_PAGE = 4022
    const val MF0_NTAG_SET_DETECTION_EN = 4033   // NTAG password capture
    const val MF0_NTAG_GET_DETECTION_CNT = 4034
    const val MF0_NTAG_GET_DETECTION_LOG = 4035
    const val EM410X_SET_EMU_ID = 5000    // req 5s id

    // status
    const val STATUS_SUCCESS = 0x68
    const val STATUS_HF_OK = 0x00         // HF14A scan found a tag
    const val STATUS_LF_OK = 0x40         // LF scan found a tag

    // device mode / sense / key type
    const val MODE_READER = 1
    const val MODE_TAG = 0
    const val SENSE_LF = 1
    const val SENSE_HF = 2
    const val KEY_A = 0x60
    const val KEY_B = 0x61
    // PRNG class (MF1_DETECT_PRNG)
    const val PRNG_STATIC = 0; const val PRNG_WEAK = 1; const val PRNG_HARD = 2
    // tag types
    const val TT_EM410X = 100
    const val TT_MIFARE_MINI = 1000; const val TT_MIFARE_1K = 1001; const val TT_MIFARE_2K = 1002; const val TT_MIFARE_4K = 1003
    const val TT_NTAG_213 = 1100; const val TT_NTAG_215 = 1101; const val TT_NTAG_216 = 1102; const val TT_ULTRALIGHT = 1103

    fun lrc(p: ByteArray, off: Int, n: Int): Byte {
        var s = 0
        for (i in 0 until n) s += p[off + i].toInt() and 0xFF
        return ((0x100 - (s and 0xFF)) and 0xFF).toByte()
    }
    private fun putU16(p: ByteArray, off: Int, v: Int) { p[off] = (v ushr 8).toByte(); p[off + 1] = v.toByte() }
    fun getU16(p: ByteArray, off: Int) = ((p[off].toInt() and 0xFF) shl 8) or (p[off + 1].toInt() and 0xFF)

    /** Build a request frame (status always 0 for requests). */
    fun frame(cmd: Int, data: ByteArray? = null): ByteArray {
        val dlen = data?.size ?: 0
        val out = ByteArray(FRAME_OVH + dlen)
        out[0] = SOF.toByte()
        out[1] = lrc(out, 0, 1)                 // == 0xEF
        putU16(out, 2, cmd)
        putU16(out, 4, 0)                        // status
        putU16(out, 6, dlen)
        out[8] = lrc(out, 0, 8)                  // LRC2 over the head
        if (dlen > 0) System.arraycopy(data!!, 0, out, 9, dlen)
        out[9 + dlen] = lrc(out, 9, dlen)        // LRC3 over the data
        return out
    }

    class Resp(val cmd: Int, val status: Int, val data: ByteArray)
    /** resp = a parsed frame (or null), consumed = bytes to drop from the front (0 = need more). */
    class ParseResult(val resp: Resp?, val consumed: Int)

    fun parse(buf: ByteArray, len: Int): ParseResult {
        if (len == 0) return ParseResult(null, 0)
        if ((buf[0].toInt() and 0xFF) != SOF) {                 // resync to the next SOF
            var i = 1
            while (i < len && (buf[i].toInt() and 0xFF) != SOF) i++
            return ParseResult(null, i)
        }
        if (len < 9) return ParseResult(null, 0)                 // need the 9-byte head
        if (buf[1] != lrc(buf, 0, 1)) return ParseResult(null, 1)
        if (buf[8] != lrc(buf, 0, 8)) return ParseResult(null, 1)
        val cmd = getU16(buf, 2); val st = getU16(buf, 4); val dl = getU16(buf, 6)
        if (dl > MAX_DATA) return ParseResult(null, 1)
        val total = FRAME_OVH + dl
        if (len < total) return ParseResult(null, 0)             // need the rest of the frame
        if (buf[9 + dl] != lrc(buf, 9, dl)) return ParseResult(null, 1)
        return ParseResult(Resp(cmd, st, buf.copyOfRange(9, 9 + dl)), total)
    }

    // ---- response parsers ----
    class Hf14a(val uid: ByteArray, val atqa: ByteArray, val sak: Int)
    fun parseHf14a(d: ByteArray): Hf14a? {
        if (d.isEmpty()) return null
        val ul = d[0].toInt() and 0xFF
        if (d.size < 1 + ul + 3) return null
        return Hf14a(d.copyOfRange(1, 1 + ul), d.copyOfRange(1 + ul, 1 + ul + 2), d[1 + ul + 2].toInt() and 0xFF)
    }

    class Em410x(val type: Int, val id: ByteArray)
    fun parseEm410x(d: ByteArray): Em410x? {
        if (d.size < 7) return null
        return Em410x(getU16(d, 0), d.copyOfRange(2, 7))
    }

    class Battery(val mv: Int, val pct: Int)
    fun parseBattery(d: ByteArray): Battery? {
        if (d.size < 3) return null
        return Battery(getU16(d, 0), d[2].toInt() and 0xFF)
    }

    /** 8 slots: returns (hf tag types, lf tag types). */
    fun parseSlotInfo(d: ByteArray): Pair<IntArray, IntArray>? {
        if (d.size < 32) return null
        val hf = IntArray(8); val lf = IntArray(8)
        for (i in 0 until 8) { hf[i] = getU16(d, i * 4); lf[i] = getU16(d, i * 4 + 2) }
        return hf to lf
    }

    // Default T5577 passwords for EM410x->T55xx cloning (from onyx chameleon_proto.h).
    val CH_T55_NEW = byteArrayOf(0x20, 0x20, 0x66, 0x66)
    val CH_T55_OLD = byteArrayOf(0x51, 0x24, 0x36, 0x48, 0x19, 0x92.toByte(), 0x04, 0x27)

    fun getU32(p: ByteArray, off: Int) =((p[off].toInt() and 0xFF).toLong() shl 24) or
        ((p[off + 1].toInt() and 0xFF).toLong() shl 16) or ((p[off + 2].toInt() and 0xFF).toLong() shl 8) or (p[off + 3].toInt() and 0xFF).toLong()

    // ---- builders (return the command's data payload; wrap with frame(cmd, data)) ----
    fun mf1Read(keyType: Int, block: Int, key6: ByteArray) = byteArrayOf(keyType.toByte(), block.toByte()) + key6
    fun mf1Write(keyType: Int, block: Int, key6: ByteArray, data16: ByteArray) = byteArrayOf(keyType.toByte(), block.toByte()) + key6 + data16
    fun mf1Auth(keyType: Int, block: Int, key6: ByteArray) = byteArrayOf(keyType.toByte(), block.toByte()) + key6
    fun mf1Darkside(keyType: Int, block: Int, first: Int, syncMax: Int) = byteArrayOf(keyType.toByte(), block.toByte(), first.toByte(), syncMax.toByte())
    fun mf1NtDist(keyType: Int, block: Int, key6: ByteArray) = byteArrayOf(keyType.toByte(), block.toByte()) + key6
    fun mf1Nested(knownType: Int, knownBlk: Int, knownKey6: ByteArray, tgtType: Int, tgtBlk: Int) =
        byteArrayOf(knownType.toByte(), knownBlk.toByte()) + knownKey6 + byteArrayOf(tgtType.toByte(), tgtBlk.toByte())
    fun mf1Hardnested(slow: Int, knownType: Int, knownBlk: Int, knownKey6: ByteArray, tgtType: Int, tgtBlk: Int) =
        byteArrayOf(slow.toByte(), knownType.toByte(), knownBlk.toByte()) + knownKey6 + byteArrayOf(tgtType.toByte(), tgtBlk.toByte())
    fun mf1EncNested(backdoorKey6: ByteArray, secCount: Int, startSec: Int) =
        backdoorKey6.copyOf(6) + byteArrayOf(secCount.toByte(), startSec.toByte())
    // Fudan FM11RF08S backdoor key A
    val FM_BACKDOOR = byteArrayOf(0xA3.toByte(), 0x96.toByte(), 0xEF.toByte(), 0xA4.toByte(), 0xE2.toByte(), 0x4F)
    fun mf1CheckKeysOnBlock(block: Int, keyType: Int, keys: List<ByteArray>): ByteArray {
        val out = ByteArray(3 + keys.size * 6)
        out[0] = block.toByte(); out[1] = keyType.toByte(); out[2] = keys.size.toByte()
        keys.forEachIndexed { i, k -> System.arraycopy(k, 0, out, 3 + i * 6, 6) }
        return out
    }
    fun u32be(v: Long): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    fun setSlotTagType(slot: Int, tt: Int) = byteArrayOf(slot.toByte()) + u16be(tt)
    fun setSlotDataDefault(slot: Int, tt: Int) = byteArrayOf(slot.toByte()) + u16be(tt)
    fun setSlotEnable(slot: Int, sense: Int, enable: Boolean) = byteArrayOf(slot.toByte(), sense.toByte(), if (enable) 1 else 0)
    fun writeEmuBlock(startBlock: Int, data: ByteArray) = byteArrayOf(startBlock.toByte()) + data
    fun setAntiColl(uid: ByteArray, atqa2: ByteArray, sak: Int, ats: ByteArray = ByteArray(0)) =
        byteArrayOf(uid.size.toByte()) + uid + atqa2 + byteArrayOf(sak.toByte(), ats.size.toByte()) + ats
    fun em410xSetEmuId(id5: ByteArray) = id5.copyOf(5)
    fun em410xWriteT55xx(id5: ByteArray, newKey4: ByteArray, oldKeys: ByteArray) = id5.copyOf(5) + newKey4.copyOf(4) + oldKeys
    fun hf14aRaw(opts: Int, timeoutMs: Int, bitLen: Int, data: ByteArray) =
        byteArrayOf(opts.toByte()) + u16be(timeoutMs) + u16be(bitLen) + data
    private fun u16be(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    // ---- attack parsers ----
    class AuthEntry(val block: Int, val keyB: Boolean, val nested: Boolean, val uid: Long, val nt: Long, val nr: Long, val ar: Long)
    fun parseAuthLog(d: ByteArray): List<AuthEntry> {
        val out = ArrayList<AuthEntry>()
        var i = 0
        while (i + 18 <= d.size) {
            val flags = d[i + 1].toInt() and 0xFF
            out.add(AuthEntry(d[i].toInt() and 0xFF, flags and 1 != 0, flags and 2 != 0,
                getU32(d, i + 2), getU32(d, i + 6), getU32(d, i + 10), getU32(d, i + 14)))
            i += 18
        }
        return out
    }
    class Darkside(val full: Boolean, val status: Int, val uid: Long, val nt: Long, val nr: Long, val ar: Long, val par: ByteArray, val ks: ByteArray)
    fun parseDarkside(d: ByteArray): Darkside? {
        if (d.isEmpty()) return null
        val status = d[0].toInt() and 0xFF
        if (d.size < 33) return Darkside(false, status, 0, 0, 0, 0, ByteArray(8), ByteArray(8))
        return Darkside(true, status, getU32(d, 1), getU32(d, 5), getU32(d, 25), getU32(d, 29),
            d.copyOfRange(9, 17), d.copyOfRange(17, 25))
    }
    class NestedSet(val nt1: Long, val nt2: Long, val par: Int)
    fun parseNested(d: ByteArray): List<NestedSet> {
        val out = ArrayList<NestedSet>(); var i = 0
        while (i + 9 <= d.size) { out.add(NestedSet(getU32(d, i), getU32(d, i + 4), d[i + 8].toInt() and 0xFF)); i += 9 }
        return out
    }
    class NtDist(val uid: Long, val dist: Long)
    fun parseNtDist(d: ByteArray): NtDist? = if (d.size < 8) null else NtDist(getU32(d, 0), getU32(d, 4))
    class Em4x05(val config: Long, val uid: Long, val uidHi: Long, val is4x69: Boolean)
    fun parseEm4x05(d: ByteArray): Em4x05? = if (d.size < 13) null else Em4x05(getU32(d, 0), getU32(d, 4), getU32(d, 8), d[12].toInt() != 0)

    fun tagName(tt: Int): String = when (tt) {
        0 -> "—"
        100, 101, 102, 103, 104 -> "EM410x"
        150 -> "PAC"; 170 -> "Viking"; 180 -> "Jablotron"
        200 -> "HID Prox"; 201 -> "ioProx"; 310 -> "IDTECK"
        1000 -> "MIFARE Mini"; 1001 -> "MIFARE 1K"; 1002 -> "MIFARE 2K"; 1003 -> "MIFARE 4K"
        1100 -> "NTAG213"; 1101 -> "NTAG215"; 1102 -> "NTAG216"
        1103, 1104, 1105, 1106 -> "Ultralight"; 1107 -> "NTAG210"; 1108 -> "NTAG212"
        3000 -> "ISO14443-4"; 3001 -> "HID SEOS"
        else -> "#$tt"
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
