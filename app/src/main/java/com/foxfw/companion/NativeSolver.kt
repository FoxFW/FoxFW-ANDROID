package com.foxfw.companion

/** JNI bridge to the native Fiat/Hitag2 guess-and-determine recovery (h2crack + bridge). */
object NativeSolver {
    @Volatile private var loaded = false
    val available: Boolean
        get() {
            if (!loaded) {
                try { System.loadLibrary("foxsolver"); loaded = true } catch (_: Throwable) {}
            }
            return loaded
        }

    /** Returns 6 bytes {k4,k5,clone[31..0]} or null. Blocking — call off the main thread. */
    external fun recover(uid: Int, btn: IntArray, ctrl: IntArray, hop: LongArray, threads: Int): ByteArray?

    /** Candidate states checked so far (progress). */
    external fun scanned(): Long

    /** Request the running recover to stop. */
    external fun cancel()

    /** Predict a hop from the recovered clone secret (key6) + frame0 (btn,ctrl) + target (btn,ctrl). */
    external fun predict(uid: Int, key: ByteArray, f0btn: Int, f0ctrl: Int, btn: Int, ctrl: Int): Long

    /**
     * Dictionary attack: known factory keys x 2^18 epochs. Fast — try before [recover].
     * Returns 10 bytes = absolute key[0..5] + epoch (4 bytes, big-endian), or null on a miss.
     */
    external fun dict(uid: Int, btn: IntArray, ctrl: IntArray, hop: LongArray, threads: Int): ByteArray?

    /** Predict a hop from an ABSOLUTE key (dictionary result) + its epoch. */
    external fun predictKey(uid: Int, key: ByteArray, epoch: Int, btn: Int, ctrl: Int): Long

    /** Dictionary progress (epochs tried). */
    external fun dictScanned(): Long

    /**
     * Renault V1 (classic Hitag2 + SEED) recovery: brute-force the 18-bit seed of an
     * 11-byte wire frame (needs NO secret key — the keystream is derived from the serial).
     * Returns the recovered 4-byte IV (decode cnt/btn/seed from it), or null on a miss.
     *   btn  = iv[0] and 0x0F
     *   cnt  = (iv[0] ushr 4 and 0x0F) or (iv[1] shl 4) or ((iv[2] and 0x0F) shl 12)
     *   seed = ((iv[2] and 0xF0) shl 8) or iv[3]
     */
    external fun seedRecover(frame: ByteArray): ByteArray?

    /** Forward-encode one Renault V1 frame (11 bytes) from serial+cnt+btn+seed — a real NEXT code. */
    external fun seedForge(serial: Int, cnt: Int, btn: Int, seed: Int): ByteArray
}
