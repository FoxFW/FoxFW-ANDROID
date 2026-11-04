package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the pure Esp32 command/parse core (no USB/BLE). */
class Esp32CoreTest {
    @Test fun parsesPlainApLine() {
        val ap = Esp32.parseAp("[0][CH:6] MyNetwork -45")!!
        assertEquals(0, ap.index)
        assertEquals(6, ap.channel)
        assertEquals(-45, ap.rssi)
        assertEquals("MyNetwork", ap.essid)
        assertFalse(ap.selected)
        assertEquals(BandScan.Band.GHZ24, ap.band)
    }

    @Test fun parsesSelected5GhzLine() {
        val ap = Esp32.parseAp("[1][CH:36] MyNetwork5 -60 (selected)")!!
        assertEquals(1, ap.index)
        assertEquals(36, ap.channel)
        assertEquals(-60, ap.rssi)
        assertEquals("MyNetwork5", ap.essid)
        assertTrue(ap.selected)
        assertEquals(BandScan.Band.GHZ5, ap.band)
    }

    @Test fun essidMayContainSpaces() {
        val ap = Esp32.parseAp("[12][CH:11] My Home Net 5G -72")!!
        assertEquals(12, ap.index)
        assertEquals("My Home Net 5G", ap.essid)
        assertEquals(-72, ap.rssi)
    }

    @Test fun hiddenEssidBecomesPlaceholder() {
        val ap = Esp32.parseAp("[3][CH:1]            -80")!!
        assertEquals("<hidden>", ap.essid)
        assertEquals(-80, ap.rssi)
        assertEquals(1, ap.channel)
    }

    @Test fun rejectsNonApLines() {
        assertNull(Esp32.parseAp(""))
        assertNull(Esp32.parseAp("Scanning started"))
        assertNull(Esp32.parseAp("[CH:6] no index"))
        assertNull(Esp32.parseAp("random [0][CH:6] not at start"))
    }

    @Test fun bandFromChannel() {
        assertEquals(BandScan.Band.GHZ24, Esp32.parseAp("[0][CH:14] x -40")!!.band)
        assertEquals(BandScan.Band.GHZ5, Esp32.parseAp("[0][CH:15] x -40")!!.band)
        assertEquals(BandScan.Band.GHZ5, Esp32.parseAp("[0][CH:149] x -40")!!.band)
    }

    @Test fun commandBuildersMatchEsp32Cli() {
        assertEquals("scanap", Esp32.SCAN_AP)
        assertEquals("stopscan", Esp32.STOP)
        assertEquals("list -a", Esp32.LIST_AP)
        assertEquals("select -a 4", Esp32.selectAp(4))
        assertEquals("select -a all", Esp32.selectAllAp())
        assertEquals("attack -t deauth", Esp32.deauth(false))
        assertEquals("attack -t deauth -c", Esp32.deauth(true))
    }
}
