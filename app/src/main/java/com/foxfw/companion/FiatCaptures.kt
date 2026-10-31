package com.foxfw.companion

/**
 * Parse decoded "Fiat V1" Sub-GHz captures saved on the Flipper SD (/ext/subghz).
 *
 * A decoded Fiat V1 .sub is a flipper_format text file that already carries the fields the
 * Hitag2 cracker needs — no demodulation required (the Flipper's subghz decoder extracted them
 * at capture time). The firmware's own recover scene reads the same fields
 * (subghz_scene_fiat_v1_recover.c): Protocol == "Fiat V1", Serial(=uid), Btn, Cnt(=control), Hop.
 * flipper_format writes uint32 values as decimal; we also tolerate hex just in case.
 */
object FiatCaptures {
    const val PROTOCOL = "Fiat V1"

    /** One cracker-ready frame parsed from a saved capture. */
    data class Frame(val uid: Long, val btn: Int, val ctrl: Int, val hop: Long, val name: String)

    /** Parse one .sub's text; returns a Frame iff it is a Fiat V1 capture with Serial+Hop, else null. */
    fun parse(subText: String, name: String): Frame? {
        var proto: String? = null
        var serial: Long? = null
        var btn: Int? = null
        var cnt: Int? = null
        var hop: Long? = null
        for (raw in subText.lineSequence()) {
            val line = raw.trim()
            val c = line.indexOf(':')
            if (c <= 0) continue
            val key = line.substring(0, c).trim()
            val value = line.substring(c + 1).trim()
            when (key) {
                "Protocol" -> proto = value
                "Serial" -> serial = num(value)
                "Btn" -> btn = num(value)?.toInt()
                "Cnt" -> cnt = num(value)?.toInt()
                "Hop" -> hop = num(value)
            }
        }
        if (proto != PROTOCOL) return null
        val u = serial ?: return null
        val h = hop ?: return null
        return Frame(u, btn ?: 0, cnt ?: 0, h, name)
    }

    /** First whitespace-separated token as a number (decimal, 0x-hex, or bare hex). */
    private fun num(v: String): Long? {
        val t = v.trim().substringBefore(' ').trim()
        if (t.isEmpty()) return null
        return try {
            when {
                t.startsWith("0x", true) -> t.substring(2).toLong(16)
                t.all { it.isDigit() } -> t.toLong()
                else -> t.toLong(16)
            }
        } catch (_: Exception) { null }
    }
}
