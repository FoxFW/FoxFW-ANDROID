package com.foxfw.companion

import com.foxfw.companion.ChameleonProto as P

/**
 * Tag Maker backend driving a ChameleonUltra over the existing BLE proto.
 * Emulate (HF MFC/NTAG/UL slot upload, LF EM410x), physical write (EM410x→T5577,
 * MFC UID→gen1a magic), and read (EM410x, MFC UID). Reuses the HW-proven ops.
 */
object ChameleonTagBackend : TagBackend {
    override val name = "ChameleonUltra"
    private val c get() = FoxApplication.instance.chameleon
    override fun connected() = c.conn == Conn.CONNECTED
    override fun capabilities(type: TagType) = TagCaps.chameleon(type)

    private fun ttFor(type: TagType): Int = when (type) {
        TagType.MIFARE_CLASSIC_1K -> P.TT_MIFARE_1K
        TagType.MIFARE_CLASSIC_4K -> P.TT_MIFARE_4K
        TagType.NTAG213 -> P.TT_NTAG_213
        TagType.NTAG215 -> P.TT_NTAG_215
        TagType.NTAG216 -> P.TT_NTAG_216
        TagType.MIFARE_ULTRALIGHT -> P.TT_ULTRALIGHT
        TagType.EM4100 -> P.TT_EM410X
        else -> P.TT_MIFARE_1K
    }

    private suspend fun activeSlot(): Int {
        val (st, d) = c.cmd(P.GET_ACTIVE_SLOT)
        return if (st == P.STATUS_SUCCESS && d != null && d.isNotEmpty()) d[0].toInt() and 0xFF else 0
    }

    override suspend fun emulate(tag: Tag, ms: Int): OpResult {
        if (!connected()) return OpResult.fail("ChameleonUltra not connected.")
        val slot = activeSlot()
        return when (tag) {
            is LfTag -> {
                if (tag.type != TagType.EM4100) return OpResult.fail("Chameleon LF = EM410x only.")
                c.cmd(P.SET_SLOT_TAG_TYPE, P.setSlotTagType(slot, P.TT_EM410X))
                val (st, _) = c.cmd(P.EM410X_SET_EMU_ID, P.em410xSetEmuId(tag.data))
                c.cmd(P.SET_SLOT_ENABLE, P.setSlotEnable(slot, P.SENSE_LF, true))
                c.cmd(P.SLOT_DATA_CONFIG_SAVE)
                if (st == P.STATUS_SUCCESS) OpResult.ok("Emulating EM410x in slot $slot.")
                else OpResult.fail("Set EM410x emu id failed (0x%02X).".format(st))
            }
            is HfTag -> {
                c.cmd(P.SET_SLOT_DATA_DEFAULT, P.setSlotDataDefault(slot, ttFor(tag.type)))
                c.cmd(P.HF14A_SET_ANTI_COLL, P.setAntiColl(tag.uid, tag.atqa, tag.sak))
                var wrote = 0
                if (tag.type == TagType.MIFARE_CLASSIC_1K || tag.type == TagType.MIFARE_CLASSIC_4K) {
                    tag.blocks.forEachIndexed { i, b ->
                        if (b.size == 16) { c.cmd(P.MF1_WRITE_EMU_BLOCK, P.writeEmuBlock(i, b)); wrote++ }
                    }
                } else { // NTAG / Ultralight pages (4 bytes)
                    tag.blocks.forEachIndexed { i, pg ->
                        if (pg.size == 4) { c.cmd(P.MF0_NTAG_WRITE_EMU_PAGE, byteArrayOf(i.toByte()) + pg); wrote++ }
                    }
                }
                c.cmd(P.SET_SLOT_ENABLE, P.setSlotEnable(slot, P.SENSE_HF, true))
                val (st, _) = c.cmd(P.SLOT_DATA_CONFIG_SAVE)
                if (st == P.STATUS_SUCCESS || wrote > 0)
                    OpResult.ok("Emulating ${tag.type.label} in slot $slot ($wrote blocks/pages).")
                else OpResult.fail("Slot save failed (0x%02X).".format(st))
            }
        }
    }

    override suspend fun writeToBlank(tag: Tag): OpResult {
        if (!connected()) return OpResult.fail("ChameleonUltra not connected.")
        return when (tag) {
            is LfTag -> {
                if (tag.type != TagType.EM4100) return OpResult.fail("Chameleon LF write = EM410x→T5577 only.")
                readerMode(c)
                val (st, _) = c.cmd(P.EM410X_WRITE_T55XX,
                    P.em410xWriteT55xx(tag.data, byteArrayOf(0, 0, 0, 0), ByteArray(0)), 4000)
                if (st == P.STATUS_SUCCESS) OpResult.ok("Wrote EM410x ${hexOf(tag.data)} to T5577.")
                else OpResult.fail("T5577 write failed (0x%02X) — is a T5577 on the LF antenna?".format(st))
            }
            is HfTag -> {
                if (tag.type != TagType.MIFARE_CLASSIC_1K && tag.type != TagType.MIFARE_CLASSIC_4K)
                    return OpResult.fail("Chameleon HF write = MIFARE→gen1a magic only.")
                val blk0 = tag.blocks.firstOrNull() ?: ByteArray(16).also {
                    val n = minOf(4, tag.uid.size); System.arraycopy(tag.uid, 0, it, 0, n)
                    it[4] = tag.uid.fold(0) { a, b -> a xor (b.toInt() and 0xFF) }.toByte()
                    it[5] = tag.sak.toByte(); it[6] = tag.atqa.getOrElse(0) { 0 }; it[7] = tag.atqa.getOrElse(1) { 0x44 }
                }
                val read = ReadCard("made", tag.uid, tag.sak, tag.atqa, blk0)
                var msg = ""; var ok = false
                writeGen1aUid(c, read, onStatus = { msg = it }, onDone = {})
                ok = msg.contains("ok", true) || msg.contains("written", true) || msg.contains("UID", true)
                if (ok) OpResult.ok("Wrote UID to gen1a magic. (full-content write: fast-follow)")
                else OpResult.fail(msg.ifEmpty { "gen1a write failed — is a gen1a magic card present?" })
            }
        }
    }

    override suspend fun read(type: TagType): OpResult {
        if (!connected()) return OpResult.fail("ChameleonUltra not connected.")
        return if (type.cls == TagClass.LF) {
            val (st, d) = c.cmd(P.EM410X_SCAN, null, 2500)
            val id = if (st == P.STATUS_LF_OK && d != null) P.parseEm410x(d)?.id else null
            if (id != null) OpResult.ok("Read EM410x ${hexOf(id)}", LfTag(TagType.EM4100, id))
            else OpResult.fail("No LF tag on the antenna.")
        } else {
            sweepFob(c, onLine = {}, onDone = {})
            val r = ChamSession.lastRead
            if (r?.hfUid != null) {
                // Classify by SAK so the clone comes back as the right type.
                val t = when (r.sak and 0xFF) {
                    0x08, 0x88 -> TagType.MIFARE_CLASSIC_1K
                    0x18 -> TagType.MIFARE_CLASSIC_4K
                    0x00 -> TagType.MIFARE_ULTRALIGHT // NTAG/Ultralight family (SAK 00)
                    else -> TagType.ISO14443_3A
                }
                OpResult.ok("Read ${t.label} · UID ${hexOf(r.hfUid)}",
                    HfTag(t, r.hfUid, r.atqa ?: byteArrayOf(0, 0x44), r.sak))
            } else OpResult.fail("No HF tag detected.")
        }
    }

    private fun hexOf(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}
