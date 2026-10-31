package com.foxfw.companion

/**
 * Backend abstraction for the Tag Maker. Two implementations — [ChameleonTagBackend]
 * and [FlipperTagBackend] — and a capability matrix so the UI only offers (and Auto
 * only routes) operations a given backend/type can actually do.
 *
 * Capabilities reflect what is SHIPPED in this build. Deferred to fast-follows:
 *   - Flipper physical WRITE (HF magic / all LF)  → hardware-iteration fast-follow
 *   - Long-tail LF protocols (HID/Indala/AWID/…)   → LF symbol-export fast-follow
 */

enum class Op { EMULATE, WRITE, READ }

data class OpResult(val ok: Boolean, val message: String, val tag: Tag? = null) {
    companion object {
        fun ok(msg: String, tag: Tag? = null) = OpResult(true, msg, tag)
        fun fail(msg: String) = OpResult(false, msg)
    }
}

enum class Backend { AUTO, FLIPPER, CHAMELEON }

interface TagBackend {
    val name: String
    fun connected(): Boolean
    fun capabilities(type: TagType): Set<Op>
    suspend fun emulate(tag: Tag, ms: Int): OpResult
    suspend fun writeToBlank(tag: Tag): OpResult
    suspend fun read(type: TagType): OpResult
}

object TagCaps {
    /** What each concrete backend can do per type in THIS build. */
    fun flipper(type: TagType): Set<Op> = when (type) {
        // Flipper HF: NTAG/Ultralight emulate+write+read; MFC emulate+read (physical MFC write via Chameleon gen1a).
        TagType.MIFARE_ULTRALIGHT, TagType.NTAG213, TagType.NTAG215, TagType.NTAG216 ->
            setOf(Op.EMULATE, Op.WRITE, Op.READ)
        TagType.MIFARE_CLASSIC_1K, TagType.MIFARE_CLASSIC_4K,
        TagType.ISO14443_3A -> setOf(Op.EMULATE, Op.READ)
        // Flipper LF (embedded lfrfid engine): EM4100 + HID H10301 make/emulate/write/read.
        TagType.EM4100 -> setOf(Op.EMULATE, Op.WRITE, Op.READ)
        TagType.HID_PROX -> setOf(Op.EMULATE, Op.WRITE, Op.READ)
    }

    fun chameleon(type: TagType): Set<Op> = when (type) {
        // Chameleon HF: emulate (slot upload) + read; physical write to gen1a magic (MFC).
        TagType.MIFARE_CLASSIC_1K, TagType.MIFARE_CLASSIC_4K -> setOf(Op.EMULATE, Op.WRITE, Op.READ)
        TagType.MIFARE_ULTRALIGHT, TagType.NTAG213, TagType.NTAG215, TagType.NTAG216 -> setOf(Op.EMULATE, Op.READ)
        TagType.ISO14443_3A -> setOf(Op.EMULATE, Op.READ)
        // Chameleon LF: EM410x emulate + T5577 write + read. HID not supported by Chameleon.
        TagType.EM4100 -> setOf(Op.EMULATE, Op.WRITE, Op.READ)
        TagType.HID_PROX -> emptySet()
    }

    /** Auto rule: prefer Chameleon for EMULATE/WRITE (self-contained), Flipper for READ (richer HF). */
    fun pick(op: Op, type: TagType, flipperUp: Boolean, chamUp: Boolean): Backend? {
        val canCham = chamUp && op in chameleon(type)
        val canFlip = flipperUp && op in flipper(type)
        return when (op) {
            Op.EMULATE, Op.WRITE -> if (canCham) Backend.CHAMELEON else if (canFlip) Backend.FLIPPER else null
            Op.READ -> if (canFlip) Backend.FLIPPER else if (canCham) Backend.CHAMELEON else null
        }
    }
}
