package com.foxfw.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MdnsTest {
    @Test fun buildsAQuery() {
        val q = Mdns.buildQuery("hue-proxy.local")
        val expected = byteArrayOf(
            0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, // header: QD=1
            9, 'h'.code.toByte(), 'u'.code.toByte(), 'e'.code.toByte(), '-'.code.toByte(),
            'p'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(), 'x'.code.toByte(), 'y'.code.toByte(),
            5, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
            0,               // name terminator
            0, 1,            // QTYPE = A
            0x80.toByte(), 1, // QCLASS = IN, unicast-response bit set
        )
        assertArrayEquals(expected, q)
    }

    @Test fun parsesAnAnswerWithCompressedName() {
        // response: QR+AA, 1 question, 1 answer (A record, name = ptr to offset 12), rdata 192.168.1.132
        val r = byteArrayOf(
            0, 0, 0x84.toByte(), 0, 0, 1, 0, 1, 0, 0, 0, 0,                 // header
            9, 'h'.code.toByte(), 'u'.code.toByte(), 'e'.code.toByte(), '-'.code.toByte(),
            'p'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(), 'x'.code.toByte(), 'y'.code.toByte(),
            5, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
            0, 0, 1, 0, 1,                                                  // question QNAME/QTYPE/QCLASS
            0xC0.toByte(), 0x0C,                                           // answer NAME: pointer to offset 12
            0, 1, 0x80.toByte(), 1, 0, 0, 0, 0x78,                         // TYPE=A, CLASS, TTL
            0, 4, 192.toByte(), 168.toByte(), 1, 132.toByte(),            // RDLENGTH=4, RDATA
        )
        assertEquals("192.168.1.132", Mdns.parseA(r))
    }

    @Test fun ignoresNonAnswer() {
        val r = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0) // no answers
        assertNull(Mdns.parseA(r))
    }
}
