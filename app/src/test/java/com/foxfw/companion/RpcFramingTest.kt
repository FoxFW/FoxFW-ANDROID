package com.foxfw.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The varint-length-delimited PB_Main reassembler, shared by BLE + USB transports. */
class RpcFramingTest {
    private fun varint(o: ByteArrayOutputStream, v: Int) {
        var x = v
        while (true) {
            val b = x and 0x7F
            x = x ushr 7
            if (x != 0) o.write(b or 0x80) else { o.write(b); break }
        }
    }

    private fun framed(vararg msgs: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        for (m in msgs) { varint(o, m.size); o.write(m) }
        return o.toByteArray()
    }

    private fun collector(): Pair<RpcReassembler, MutableList<ByteArray>> {
        val got = ArrayList<ByteArray>()
        return RpcReassembler { got.add(it) } to got
    }

    @Test
    fun singleMessage() {
        val (r, got) = collector()
        r.feed(framed("hi".toByteArray()))
        assertEquals(1, got.size)
        assertEquals("hi", String(got[0]))
    }

    @Test
    fun twoMessagesInOneFeed() {
        val (r, got) = collector()
        r.feed(framed("aa".toByteArray(), "bbb".toByteArray()))
        assertEquals(2, got.size)
        assertEquals("aa", String(got[0]))
        assertEquals("bbb", String(got[1]))
    }

    @Test
    fun bodySplitAcrossFeeds() {
        val (r, got) = collector()
        val f = framed("hello".toByteArray())
        r.feed(f.copyOfRange(0, 3))
        assertEquals(0, got.size) // body not fully arrived
        r.feed(f.copyOfRange(3, f.size))
        assertEquals(1, got.size)
        assertEquals("hello", String(got[0]))
    }

    @Test
    fun twoByteLengthPrefixSplit() {
        val (r, got) = collector()
        val big = ByteArray(200) { 'x'.code.toByte() } // length varint = 2 bytes (0xC8 0x01)
        val f = framed(big)
        r.feed(f.copyOfRange(0, 1)) // only first length byte
        assertEquals(0, got.size)
        r.feed(f.copyOfRange(1, f.size))
        assertEquals(1, got.size)
        assertEquals(200, got[0].size)
        assertArrayEquals(big, got[0])
    }
}
