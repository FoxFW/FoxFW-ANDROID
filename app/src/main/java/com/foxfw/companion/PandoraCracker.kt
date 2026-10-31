package com.foxfw.companion

/**
 * The Pandora "code-grabber": recover a keyfob's unlock code from its firmware
 * dump, natively on the phone. Wraps the ported PandoraPINBrute logic (libpandora):
 *   - ARM (D-605 / MAX): static Thumb-2 scan -> knock sequence.
 *   - PIC (DXL-5000): read the PIN compare literals, else brute 0000-9999.
 */
object PandoraCracker {
    @Volatile private var loaded = false
    val available: Boolean
        get() {
            if (!loaded) try { System.loadLibrary("pandora"); loaded = true } catch (_: Throwable) {}
            return loaded
        }

    const val VAR_UNKNOWN = 0
    const val VAR_ARM_D605 = 1
    const val VAR_ARM_MAX = 2
    const val VAR_PIC_DXL = 3

    /** Classify a raw firmware image. */
    external fun detect(fw: ByteArray): Int

    /** ARM: recover the knock sequence as AK button codes (0..5), or null. */
    external fun knock(fw: ByteArray): IntArray?

    /** PIC: recover the numeric PIN (0..9999), or -1. `name` = file name hint. */
    external fun pin(fw: ByteArray, name: String): Int

    private val AK_NAMES = arrayOf("UP", "DOWN", "OK", "BACK", "B5", "B6")
    fun knockTokens(codes: IntArray): String =
        codes.joinToString(" ") { AK_NAMES.getOrElse(it) { "?" } }

    fun variantName(v: Int): String = when (v) {
        VAR_ARM_D605 -> "Pandora D-605 (ARM)"
        VAR_ARM_MAX -> "Pandora MAX (ARM)"
        VAR_PIC_DXL -> "Pandora DXL-5000 (PIC)"
        else -> "Unknown"
    }

    /** Recover whatever code this firmware uses: knock (ARM) or PIN (PIC). */
    data class Result(val variant: Int, val knock: IntArray?, val pin: Int) {
        val isArm get() = variant == VAR_ARM_D605 || variant == VAR_ARM_MAX
        val display: String
            get() = when {
                isArm && knock != null -> "KNOCK: " + knockTokens(knock)
                variant == VAR_PIC_DXL && pin >= 0 -> "PIN: %04d".format(pin)
                else -> "No code recovered"
            }
    }

    fun crack(fw: ByteArray, name: String): Result {
        if (!available) return Result(VAR_UNKNOWN, null, -1)
        val v = detect(fw)
        return when (v) {
            VAR_ARM_D605, VAR_ARM_MAX -> Result(v, knock(fw), -1)
            VAR_PIC_DXL -> Result(v, null, pin(fw, name))
            else -> Result(v, null, -1)
        }
    }
}
