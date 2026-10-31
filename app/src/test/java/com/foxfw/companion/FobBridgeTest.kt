package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the pure fob JSON→model parse (no Chaquopy/Android). */
class FobBridgeTest {
    @Test
    fun parsesFobInfoRouteAWithButtons() {
        val fi = parseFobInfo(
            """{"has_cipher":true,"route":"A","buttons":[{"code":1,"label":"Unlock"},
               {"code":2,"label":"Lock"}],"freq_hz":433920000,"preset":"AM650",
               "custom_regs":null,"gate":false,"fw_protocol":null}""",
        )
        assertNotNull(fi)
        fi!!
        assertTrue(fi.hasCipher)
        assertEquals("A", fi.route)
        assertEquals(2, fi.buttons.size)
        assertEquals("Unlock", fi.buttons[0].label)
        assertEquals(1, fi.buttons[0].code)
        assertEquals(433920000, fi.freqHz)
        assertEquals("AM650", fi.preset)
    }

    @Test
    fun parsesReplayFobInfo() {
        val fi = parseFobInfo(
            """{"has_cipher":false,"route":"replay","buttons":[],"freq_hz":433920000,"preset":"AM650"}""",
        )
        assertNotNull(fi)
        assertEquals("replay", fi!!.route)
        assertFalse(fi.hasCipher)
        assertTrue(fi.buttons.isEmpty())
    }

    @Test
    fun parsesForgeRaw() {
        val r = parseForge("""{"route":"A","sub":"Filetype: Flipper SubGhz RAW File\nRAW_Data: 1 -1"}""")
        assertTrue(r is ForgeResult.Raw)
        assertTrue((r as ForgeResult.Raw).sub.startsWith("Filetype"))
    }

    @Test
    fun parsesForgeProtocol() {
        val r = parseForge("""{"route":"B","sub":"Protocol: Kia V6\nKey: 00","protocol":"Kia V6"}""")
        assertTrue(r is ForgeResult.Protocol)
        r as ForgeResult.Protocol
        assertEquals("Kia V6", r.protocol)
        assertTrue(r.sub.contains("Kia V6"))
    }

    @Test
    fun parsesForgeError() {
        val r = parseForge("""{"error":"replay only"}""")
        assertTrue(r is ForgeResult.Err)
        assertEquals("replay only", (r as ForgeResult.Err).msg)
    }

    @Test
    fun nextCounterIsCapturedPlusOne() {
        // the fob must send the NEXT code (captured+1), not the already-consumed captured counter
        assertEquals(0x11L, fobNextCounter(0x10L))
        assertEquals(1L, fobNextCounter(null))
        assertEquals(1L, fobNextCounter(0L))
    }
}
