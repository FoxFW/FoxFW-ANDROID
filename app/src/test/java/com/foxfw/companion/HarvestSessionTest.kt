package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class HarvestSessionTest {
    @Test fun parseTakesMaxCountPerMetric() {
        val out = "Received EAPOL: 1\nComplete EAPOL: 0\nPwnd #: 0"
        assertEquals(HarvestSession.Window(1, 0, 0), HarvestParse.parseWindow(out))
    }

    @Test fun parseUsesMaxWhenCountGrows() {
        val out = "Pwnd #: 1\nReceived EAPOL: 2\nPwnd #: 3\nComplete EAPOL: 2"
        assertEquals(HarvestSession.Window(2, 2, 3), HarvestParse.parseWindow(out))
    }

    @Test fun parseNoCountsIsZero() {
        assertEquals(HarvestSession.Window(0, 0, 0), HarvestParse.parseWindow("nothing interesting here"))
    }

    @Test fun parseRealV120StatsBlock() {
        // Real ESP32 v1.20.0 (C5) sniffpmkid stats: bare "EAPOL: N" + "Complete EAPOL: N",
        // no "Received"/"Pwnd". The bare EAPOL max must be read without picking up "Complete EAPOL".
        val out = "     Deauth: 0\n    EAPOL: 4\nComplete EAPOL: 2\n     RSSI: 0 - -128\nDEAUTH TX: TRUE\n" +
            "    EAPOL: 7\nComplete EAPOL: 3\n     RSSI: 0 - -128"
        val w = HarvestParse.parseWindow(out)
        assertEquals(7, w.received)
        assertEquals(3, w.complete)
        assertEquals(0, w.pwnd)
    }

    @Test fun addReturnsNewCapturesAndAccumulates() {
        val s = HarvestSession()
        assertEquals(1, s.add(HarvestSession.Window(2, 1, 1)))
        assertEquals(1, s.windows)
        assertEquals(1, s.totalPwnd)
        assertEquals(0, s.add(HarvestSession.Window(0, 0, 0)))
        assertEquals(3, s.add(HarvestSession.Window(5, 2, 3)))
        assertEquals(3, s.windows)
        assertEquals(4, s.totalPwnd)
        assertEquals(3, s.totalComplete)
    }

    @Test fun newCapturesIsMaxOfCompleteAndPwnd() {
        val s = HarvestSession()
        assertEquals(4, s.add(HarvestSession.Window(0, 4, 2))) // complete-heavy window
    }
}
