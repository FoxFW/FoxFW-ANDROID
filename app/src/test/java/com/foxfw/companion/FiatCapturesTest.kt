package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parse decoded "Fiat V1" .sub captures into cracker-ready {uid,btn,ctrl,hop} frames. */
class FiatCapturesTest {
    private val fiat = """
        Filetype: Flipper SubGhz Key File
        Version: 1
        Frequency: 433920000
        Preset: FuriHalSubGhzPreset2FSKDev238Async
        Protocol: Fiat V1
        Bit: 64
        Serial: 1973864488
        Btn: 2
        Cnt: 345
        Hop: 3778active
    """.trimIndent().replace("3778active", "3778000000")

    @Test
    fun parsesFiatV1() {
        val f = FiatCaptures.parse(fiat, "fob_a.sub")!!
        assertEquals(1973864488L, f.uid)
        assertEquals(2, f.btn)
        assertEquals(345, f.ctrl)
        assertEquals(3778000000L, f.hop)
        assertEquals("fob_a.sub", f.name)
    }

    @Test
    fun rejectsOtherProtocols() {
        val keeloq = "Protocol: KeeLoq\nSerial: 123\nBtn: 1\nHop: 9\n"
        assertNull(FiatCaptures.parse(keeloq, "x.sub"))
    }

    @Test
    fun rejectsMissingHop() {
        val s = "Protocol: Fiat V1\nSerial: 123\nBtn: 1\nCnt: 5\n"
        assertNull(FiatCaptures.parse(s, "x.sub"))
    }

    @Test
    fun toleratesHexValues() {
        val s = "Protocol: Fiat V1\nSerial: 0x75E1D628\nBtn: 2\nCnt: 345\nHop: 0xE1371615\n"
        val f = FiatCaptures.parse(s, "h.sub")!!
        assertEquals(0x75E1D628L, f.uid)
        assertEquals(0xE1371615L, f.hop)
    }
}
