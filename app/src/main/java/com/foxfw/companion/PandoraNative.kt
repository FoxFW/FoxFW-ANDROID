package com.foxfw.companion

import android.content.Context

/**
 * On-device Pandora keyfob offload: runs the real D-605 / MAX firmware in the
 * native emulator (libpandora) to FORGE command frames, and DECODEs captured
 * frames to recover serial + counter + command. Host-validated in pandora-tembed/codec.
 */
object PandoraNative {
    @Volatile private var loaded = false
    val available: Boolean
        get() {
            if (!loaded) try { System.loadLibrary("pandora"); loaded = true } catch (_: Throwable) {}
            return loaded
        }

    /** model: 0 = D-605, 1 = MAX. serial = 4 bytes. Returns 9 on-air data bytes or null. */
    external fun forge(model: Int, fw: ByteArray, serial: ByteArray, counter: Int, cmd: Int): ByteArray?

    /** on-air 9 bytes -> {s0,s1,s2,s3,cmdCode,ctrHi,ctrLo,cksOk} or null. */
    external fun decode(onair: ByteArray): IntArray?

    val MODEL_NAMES = arrayOf("D-605", "MAX")
    private val assetName = mapOf(0 to "pandora_d605.bin", 1 to "pandora_max.bin")
    private val fwCache = HashMap<Int, ByteArray>()
    fun firmware(ctx: Context, model: Int): ByteArray =
        fwCache.getOrPut(model) { ctx.assets.open(assetName[model]!!).use { it.readBytes() } }

    data class Decoded(val serial: ByteArray, val cmdCode: Int, val counter: Int, val cksOk: Boolean) {
        val serialHex get() = serial.joinToString("") { "%02X".format(it) }
    }

    fun decodeFrame(onair: ByteArray): Decoded? {
        if (!available || onair.size < 9) return null
        val r = decode(onair) ?: return null
        if (r.size < 8) return null // native contract is 8 ints; never index past a short/partial result
        return Decoded(
            byteArrayOf(r[0].toByte(), r[1].toByte(), r[2].toByte(), r[3].toByte()),
            r[4], (r[5] shl 8) or r[6], r[7] == 1
        )
    }

    fun forgeFrame(ctx: Context, model: Int, serial: ByteArray, counter: Int, cmd: Int): ByteArray? {
        if (!available) return null
        return forge(model, firmware(ctx, model), serial, counter, cmd)
    }

    /**
     * Build a Flipper RAW .sub pulse list (microseconds; + = carrier on, - = off) for the
     * OOK frame: model preamble/sync + 9 data bytes, Manchester MSB-first, repeated `reps` times.
     * D-605 half-bit ~250us; MAX uses a different delay routine (calibrate vs a real capture).
     */
    fun pulses(model: Int, data: ByteArray, halfbitUs: Int = 250, reps: Int = 4, gapUs: Int = 15000): IntArray {
        val bits = ArrayList<Int>()
        fun pb(b: Int) { for (i in 7 downTo 0) bits.add((b shr i) and 1) }
        if (model == 0) { pb(0xAA); bits.add(1); pb(0xAA) }      // D-605: AA, 1, AA
        else { repeat(14) { pb(0xFF) }; pb(0xD7) }               // MAX: 14x FF, D7 sync
        for (b in data) pb(b.toInt() and 0xFF)
        val half = ArrayList<Int>()
        for (bit in bits) { if (bit == 1) { half.add(1); half.add(0) } else { half.add(0); half.add(1) } }
        val one = ArrayList<Int>()
        var i = 0
        while (i < half.size) {
            var j = i; while (j < half.size && half[j] == half[i]) j++
            val dur = (j - i) * halfbitUs
            one.add(if (half[i] == 1) dur else -dur)
            i = j
        }
        val out = ArrayList<Int>()
        repeat(reps) { out.addAll(one); out.add(-gapUs) }
        return out.toIntArray()
    }

    /**
     * Flipper RAW .sub for the OOK pulse list. Pandora keyfobs transmit OOK (D-605/MAX
     * bit-bang OOK on the ARM DATA pin, DXL-5000 on LATB) — the field-proven waveform
     * used by PandoraARM / the T-Embed port / pandora_gen.py. (An earlier 2-FSK attempt
     * was wrong: the alarm wants the OOK Manchester envelope, not FSK.)
     */
    fun subFile(pulses: IntArray, freqHz: Int = 433920000): String {
        val sb = StringBuilder()
        sb.append("Filetype: Flipper SubGhz RAW File\n")
        sb.append("Version: 1\n")
        sb.append("Frequency: $freqHz\n")
        sb.append("Preset: FuriHalSubGhzPresetOok650Async\n")
        sb.append("Protocol: RAW\n")
        var k = 0
        while (k < pulses.size) {
            val chunk = pulses.copyOfRange(k, minOf(k + 512, pulses.size))
            sb.append("RAW_Data: ").append(chunk.joinToString(" ")).append("\n")
            k += 512
        }
        return sb.toString()
    }
}
