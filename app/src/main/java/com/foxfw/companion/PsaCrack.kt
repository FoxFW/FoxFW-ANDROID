package com.foxfw.companion

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * PSA (Peugeot / Citroen / Opel / Vauxhall / DS) TEA brute-force — the GPU
 * companion's crack core (pp_psa_bf.py) ported to pure Kotlin so the phone can
 * serve FoxFW's on-device "PSA decrypt" offload over BLE.
 *
 * The firmware hands us the TEA ciphertext words (w0, w1); we scan two 2^24
 * key windows (BF1 0x23.. / BF2 0xF3..), TEA-decrypt each candidate, and accept
 * on the firmware's exact validity gate. All math is 32-bit; Kotlin Int wraps
 * mod 2^32 like the Python `& 0xFFFFFFFF`, and `ushr` gives the logical shifts.
 */
object PsaCrack {
    private const val ROUNDS = 32
    private const val DELTA = 0x9E3779B9.toInt()

    private val BF1_KEY = intArrayOf(0x4A434915, 0xD6743C2B.toInt(), 0x1F29D308, 0xE6B79A64.toInt())
    private const val BF1_U4 = 0x0E0F5C41
    private const val BF1_U5 = 0x0F5C4123
    private val BF2_KEY = intArrayOf(0x4039C240, 0xEDA92CAB.toInt(), 0x4306C02A, 0x02192A04)

    private const val BF1_START = 0x23000000
    private const val BF1_END = 0x24000000
    private const val BF2_START = 0xF3000000.toInt()
    private const val BF2_END = 0xF4000000.toInt()

    /** ok=false means exhausted both windows (or cancelled) with no hit. */
    data class Result(val ok: Boolean, val counter: Int, val dv0: Int, val dv1: Int)

    // ---- TEA primitives (match pp_psa_bf.build_schedule / tea_decrypt) ----

    private fun buildSchedule(key: IntArray, s0: IntArray, s1: IntArray) {
        for (i in 0 until ROUNDS) {
            val sum0 = i * DELTA
            val sum1 = (i + 1) * DELTA
            s0[i] = key[sum0 and 3] + sum0
            s1[i] = key[(sum1 ushr 11) and 3] + sum1
        }
    }

    private fun teaCrc(dv0: Int, dv1: Int): Int {
        var crc = ((dv0 ushr 24) and 0xFF) + ((dv0 ushr 16) and 0xFF) +
            ((dv0 ushr 8) and 0xFF) + (dv0 and 0xFF)
        crc += ((dv1 ushr 24) and 0xFF) + ((dv1 ushr 16) and 0xFF) + ((dv1 ushr 8) and 0xFF)
        return crc and 0xFF
    }

    private fun crc16Buypass(buf: IntArray): Int {
        var crc = 0
        for (b in buf) {
            crc = crc xor ((b and 0xFF) shl 8)
            for (k in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x8005 else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    private fun bf2CrcOk(dv0: Int, dv1: Int): Boolean {
        val buf = intArrayOf(
            (dv0 ushr 24) and 0xFF, (dv0 ushr 16) and 0xFF, (dv0 ushr 8) and 0xFF, dv0 and 0xFF,
            (dv1 ushr 24) and 0xFF, (dv1 ushr 16) and 0xFF,
        )
        val expected = (dv1 and 0xFF) or (((dv1 ushr 16) and 0xFF) shl 8)
        return crc16Buypass(buf) == expected
    }

    /**
     * Scan a half-open counter window on the current thread. Writes a hit into
     * [out] (counter, dv0, dv1) and returns true; honours [found]/[cancel] and
     * bumps [tested] every 64k candidates. [bf1] selects the key schedule.
     */
    private fun scanRange(
        w0: Int, w1: Int, start: Int, end: Int, bf1: Boolean,
        found: AtomicBoolean, cancel: AtomicBoolean, tested: AtomicLong, out: AtomicReference<IntArray?>,
    ): Boolean {
        val s0 = IntArray(ROUNDS); val s1 = IntArray(ROUNDS)
        if (bf1) buildSchedule(BF1_KEY, s0, s1)
        val key = IntArray(4)
        var local = 0L
        var c = start
        while (c != end) {
            if ((c and 0xFFFF) == 0) {
                if (found.get() || cancel.get()) { tested.addAndGet(local); return false }
                tested.addAndGet(local); local = 0
            }
            if (bf1) {
                // wk2,wk3 = enc(U4, c); wk0,wk1 = enc((c<<8)|0x0E, U5)
                var a = BF1_U4; var b = c
                for (i in 0 until ROUNDS) {
                    a += s0[i] xor ((((b ushr 5) xor (b shl 4)) + b))
                    b += s1[i] xor ((((a ushr 5) xor (a shl 4)) + a))
                }
                key[2] = a; key[3] = b
                a = (c shl 8) or 0x0E; b = BF1_U5
                for (i in 0 until ROUNDS) {
                    a += s0[i] xor ((((b ushr 5) xor (b shl 4)) + b))
                    b += s1[i] xor ((((a ushr 5) xor (a shl 4)) + a))
                }
                key[0] = a; key[1] = b
            } else {
                key[0] = BF2_KEY[0] xor c; key[1] = BF2_KEY[1] xor c
                key[2] = BF2_KEY[2] xor c; key[3] = BF2_KEY[3] xor c
            }
            // tea_decrypt(w0, w1, key)
            var da = w0; var db = w1
            var s = DELTA * ROUNDS
            for (i in 0 until ROUNDS) {
                var t = key[(s ushr 11) and 3] + s
                s -= DELTA
                db -= t xor ((((da ushr 5) xor (da shl 4)) + da))
                t = key[s and 3] + s
                da -= t xor ((((db ushr 5) xor (db shl 4)) + db))
            }
            if ((c and 0xFFFFFF) == (da ushr 8)) {
                val hit = if (bf1) teaCrc(da, db) == (db and 0xFF) else bf2CrcOk(da, db)
                if (hit) {
                    out.set(intArrayOf(c, da, db))
                    found.set(true)
                    tested.addAndGet(local)
                    return true
                }
            }
            local++
            c++
        }
        tested.addAndGet(local)
        return false
    }

    /**
     * Crack (w0, w1) across both windows using [threads] workers. [onProgress]
     * is called ~5×/s with (keysTested, keysPerSec); [cancel] aborts early.
     */
    fun crack(
        w0: Int,
        w1: Int,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result {
        val tested = AtomicLong(0)
        val t0 = System.nanoTime()
        var progressor: Thread? = null
        if (onProgress != null) {
            progressor = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        Thread.sleep(200)
                        val n = tested.get()
                        val dt = (System.nanoTime() - t0) / 1e9
                        onProgress(n, if (dt > 0) (n / dt).toLong() else 0L)
                    }
                } catch (_: InterruptedException) {}
            }.also { it.isDaemon = true; it.start() }
        }

        for (window in listOf(Triple(BF1_START, BF1_END, true), Triple(BF2_START, BF2_END, false))) {
            val (start, end, bf1) = window
            val found = AtomicBoolean(false)
            val out = AtomicReference<IntArray?>(null)
            // split [start,end) into `threads` stripes (unsigned span = 0x01000000)
            val span = 0x01000000
            val step = span / threads
            val workers = ArrayList<Thread>(threads)
            for (ti in 0 until threads) {
                val s = start + ti * step
                val e = if (ti == threads - 1) end else start + (ti + 1) * step
                workers += Thread { scanRange(w0, w1, s, e, bf1, found, cancel, tested, out) }
            }
            workers.forEach { it.start() }
            workers.forEach { it.join() }
            val hit = out.get()
            if (hit != null) {
                progressor?.interrupt()
                return Result(true, hit[0], hit[1], hit[2])
            }
            if (cancel.get()) break
        }
        progressor?.interrupt()
        return Result(false, 0, 0, 0)
    }
}
