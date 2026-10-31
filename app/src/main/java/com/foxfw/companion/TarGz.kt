package com.foxfw.companion

import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Minimal .tar.gz / .tgz reader — enough to unpack a FoxFW update bundle on the phone. No deps. */
object TarGz {
    data class Item(val path: String, val bytes: ByteArray)

    fun extract(input: InputStream): List<Item> {
        val out = ArrayList<Item>()
        GZIPInputStream(input).use { gz ->
            val header = ByteArray(512)
            var longName: String? = null
            while (true) {
                if (!readFully(gz, header, 512)) break
                if (header.all { it.toInt() == 0 }) break // end-of-archive marker
                val typeflag = header[156].toInt().toChar()
                val size = parseOctal(header, 124, 12)
                val name = longName ?: run {
                    val n = cstr(header, 0, 100)
                    val prefix = cstr(header, 345, 155)
                    if (prefix.isNotEmpty()) "$prefix/$n" else n
                }
                longName = null
                val padded = ((size + 511) / 512) * 512
                when (typeflag) {
                    'L' -> { // GNU long filename: payload is the real name for the NEXT header
                        val nb = ByteArray(size.toInt()); readFully(gz, nb, size.toInt()); skip(gz, padded - size)
                        longName = String(nb, Charsets.UTF_8).trimEnd('\u0000')
                    }
                    '0', '\u0000' -> { // regular file
                        val data = ByteArray(size.toInt()); readFully(gz, data, size.toInt()); skip(gz, padded - size)
                        out.add(Item(normalize(name), data))
                    }
                    else -> skip(gz, padded) // directory (5), pax (x/g), links, etc. — dirs re-created on upload
                }
            }
        }
        return out
    }

    private fun normalize(name: String): String = name.removePrefix("./").trim('/')

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off; val max = off + len
        while (end < max && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.US_ASCII)
    }
    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        val s = cstr(b, off, len).trim()
        return if (s.isEmpty()) 0L else s.toLong(8)
    }
    private fun readFully(ins: InputStream, buf: ByteArray, len: Int): Boolean {
        var n = 0
        while (n < len) { val r = ins.read(buf, n, len - n); if (r < 0) return false; n += r }
        return true
    }
    private fun skip(ins: InputStream, count: Long) {
        var left = count
        val tmp = ByteArray(8192)
        while (left > 0) { val r = ins.read(tmp, 0, minOf(left, 8192L).toInt()); if (r < 0) break; left -= r }
    }
}
