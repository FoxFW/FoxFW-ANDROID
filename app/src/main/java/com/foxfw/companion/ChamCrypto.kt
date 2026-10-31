package com.foxfw.companion

/** JNI bridge to the vendored onyx MIFARE-Classic crypto (crapto1 + darkside + mfkey32_lowmem). */
object ChamCrypto {
    @Volatile private var loaded = false
    val available: Boolean
        get() {
            if (!loaded) try { System.loadLibrary("chamcrypto"); loaded = true } catch (_: Throwable) {}
            return loaded
        }

    /** mfkey32: two reader auths on the same key -> 48-bit key, or -1. Blocking (~1-2 min); off-thread. */
    external fun mfkey32(uid: Int, nt0: Int, nr0: Int, ar0: Int, nt1: Int, nr1: Int, ar1: Int): Long

    /**
     * darkside: recover candidate 48-bit keys from acquire rounds. par/ks are nrounds*8 bytes
     * (round r, nt_diff d at r*8+d). Returns candidate keys (validate each against the card).
     */
    external fun darkside(uid: IntArray, nt: IntArray, nr: IntArray, ar: IntArray,
                          par: ByteArray, ks: ByteArray, nrounds: Int): LongArray?
}
