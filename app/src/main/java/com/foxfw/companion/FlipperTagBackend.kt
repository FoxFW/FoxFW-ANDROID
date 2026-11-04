package com.foxfw.companion

import kotlinx.coroutines.delay

/**
 * Tag Maker backend driving the Flipper HF (ST25R3916) through the headless
 * fox_nfc_io.fap over storage/app RPC. Emulate + read shipped; physical write
 * is deferred to the hardware-iteration fast-follow (fox_nfc_io WRITE_HF).
 */
object FlipperTagBackend : TagBackend {
    override val name = "Flipper"
    private val ble get() = FoxApplication.instance.ble
    private const val DIR = "/ext/apps_data/fox_companion"
    private const val FAP = "/ext/apps/System/fox_android_companion.fap"
    private const val MAKE = "$DIR/make.nfc"
    private const val READOUT = "$DIR/read.nfc"
    private const val MAKE_LF = "$DIR/make.rfid"
    private const val READOUT_LF = "$DIR/read.rfid"
    private const val STATUS = "$DIR/nfc_status.txt"

    override fun connected() = ble.rpcConnected()
    override fun capabilities(type: TagType) = TagCaps.flipper(type)

    private suspend fun readStatus(): String? =
        ble.storageRead(STATUS)?.let { if (it.isNotEmpty()) String(it).trim() else null }

    private val loaderLocked = "Flipper wouldn't launch the NFC app — finish the Flipper's first-boot setup (loader is locked during it) or close the current app, then retry."

    override suspend fun emulate(tag: Tag, ms: Int): OpResult {
        if (!connected()) return OpResult.fail("Flipper not connected.")
        ble.storageMkdir(DIR)
        ble.storageDelete(STATUS, true)
        val (file, arg) = if (tag is LfTag) MAKE_LF to "EMU_LF" else MAKE to "EMU_HF"
        if (!ble.storageWrite(file, tag.toFlipperFile().toByteArray())) return OpResult.fail("Couldn't write the tag file to the Flipper SD.")
        if (!ble.appStartSafe(FAP, "$arg $file $ms")) return OpResult.fail(loaderLocked)
        delay((ms + 1500).toLong())
        val st = readStatus()
        return when (st) {
            "ok" -> OpResult.ok("Emulated ${if (tag is LfTag) tag.keyTypeName() else tag.type.label} on the Flipper for ${ms}ms.")
            "bad-args" -> OpResult.fail("Flipper couldn't load the tag (format).")
            null -> OpResult.ok("Emulation launched (no status returned).")
            else -> OpResult.fail("Flipper NFC status: $st")
        }
    }

    override suspend fun writeToBlank(tag: Tag): OpResult {
        if (!connected()) return OpResult.fail("Flipper not connected.")
        ble.storageMkdir(DIR)
        ble.storageDelete(STATUS, true)
        if (tag is LfTag) {
            if (!ble.storageWrite(MAKE_LF, tag.toFlipperFile().toByteArray())) return OpResult.fail("Couldn't write the tag file to the Flipper SD.")
            if (!ble.appStartSafe(FAP, "WRITE_LF $MAKE_LF")) return OpResult.fail(loaderLocked)
            delay(13000)
            val stLf = readStatus()
            return when (stLf) {
                "ok" -> OpResult.ok("Wrote ${tag.keyTypeName()} to T5577 via the Flipper.")
                "not-writable" -> OpResult.fail("That protocol can't be written to a T5577.")
                "no-tag", "write-failed", null -> OpResult.fail("Write failed — is a T5577 on the Flipper's LF antenna?")
                else -> OpResult.fail("Flipper LF status: $stLf")
            }
        }
        // HF: NTAG/Ultralight write via fox_nfc_io WRITE_HF (MFC physical write → use Chameleon).
        if (!ble.storageWrite(MAKE, tag.toFlipperFile().toByteArray())) return OpResult.fail("Couldn't write the tag file to the Flipper SD.")
        if (!ble.appStartSafe(FAP, "WRITE_HF $MAKE")) return OpResult.fail(loaderLocked)
        delay(9000)
        val stHf = readStatus()
        return when (stHf) {
            "ok" -> OpResult.ok("Wrote ${(tag as? HfTag)?.type?.label ?: "tag"} to the tag via the Flipper.")
            "not-writable" -> OpResult.fail("MIFARE Classic physical write goes through ChameleonUltra (gen1a) — switch backend to Chameleon.")
            "no-tag", null -> OpResult.fail("No writable tag on the Flipper's HF antenna (present an NTAG/Ultralight).")
            "write-failed" -> OpResult.fail("Write failed — is the NTAG writable (not locked)?")
            else -> OpResult.fail("Flipper HF status: $stHf")
        }
    }

    override suspend fun read(type: TagType): OpResult {
        if (!connected()) return OpResult.fail("Flipper not connected.")
        ble.storageMkdir(DIR)
        ble.storageDelete(STATUS, true)
        val lf = type.cls == TagClass.LF
        val out = if (lf) READOUT_LF else READOUT
        ble.storageDelete(out, true)
        if (!ble.appStartSafe(FAP, "${if (lf) "READ_LF" else "READ_HF"} $out")) return OpResult.fail(loaderLocked)
        delay(if (lf) 5500 else 3500)
        val st = readStatus()
        return when (st) {
            "ok" -> {
                val data = ble.storageRead(out)
                val tag = data?.let { TagCodec.parseFlipperFile(String(it)) }
                if (tag != null) {
                    val label = (tag as? HfTag)?.type?.label ?: (tag as? LfTag)?.keyTypeName() ?: "tag"
                    OpResult.ok("Read $label from the Flipper.", tag)
                } else OpResult.ok("Read a tag (couldn't parse the dump).")
            }
            "no-tag", null -> OpResult.fail("No tag on the Flipper's ${if (lf) "LF" else "HF"} antenna.")
            else -> OpResult.fail("Flipper status: $st")
        }
    }
}
