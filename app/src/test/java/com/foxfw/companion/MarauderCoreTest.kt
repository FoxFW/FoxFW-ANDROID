package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the pure Marauder command/parse core (no USB/BLE). */
class MarauderCoreTest {
    @Test fun parsesPlainApLine() {
        val ap = Marauder.parseAp("[0][CH:6] MyNetwork -45")!!
        assertEquals(0, ap.index)
        assertEquals(6, ap.channel)
        assertEquals(-45, ap.rssi)
        assertEquals("MyNetwork", ap.essid)
        assertFalse(ap.selected)
        assertEquals(BandScan.Band.GHZ24, ap.band)
    }

    @Test fun parsesSelected5GhzLine() {
        val ap = Marauder.parseAp("[1][CH:36] MyNetwork5 -60 (selected)")!!
        assertEquals(1, ap.index)
        assertEquals(36, ap.channel)
        assertEquals(-60, ap.rssi)
        assertEquals("MyNetwork5", ap.essid)
        assertTrue(ap.selected)
        assertEquals(BandScan.Band.GHZ5, ap.band)
    }

    @Test fun essidMayContainSpaces() {
        val ap = Marauder.parseAp("[12][CH:11] My Home Net 5G -72")!!
        assertEquals(12, ap.index)
        assertEquals("My Home Net 5G", ap.essid)
        assertEquals(-72, ap.rssi)
    }

    @Test fun hiddenEssidBecomesPlaceholder() {
        val ap = Marauder.parseAp("[3][CH:1]            -80")!!
        assertEquals("<hidden>", ap.essid)
        assertEquals(-80, ap.rssi)
        assertEquals(1, ap.channel)
    }

    @Test fun rejectsNonApLines() {
        assertNull(Marauder.parseAp(""))
        assertNull(Marauder.parseAp("Scanning started"))
        assertNull(Marauder.parseAp("[CH:6] no index"))
        assertNull(Marauder.parseAp("random [0][CH:6] not at start"))
    }

    @Test fun bandFromChannel() {
        assertEquals(BandScan.Band.GHZ24, Marauder.parseAp("[0][CH:14] x -40")!!.band)
        assertEquals(BandScan.Band.GHZ5, Marauder.parseAp("[0][CH:15] x -40")!!.band)
        assertEquals(BandScan.Band.GHZ5, Marauder.parseAp("[0][CH:149] x -40")!!.band)
    }

    @Test fun commandBuildersMatchMarauderCli() {
        assertEquals("scanap", Marauder.SCAN_AP)
        assertEquals("stopscan", Marauder.STOP)
        assertEquals("list -a", Marauder.LIST_AP)
        assertEquals("select -a 4", Marauder.selectAp(4))
        assertEquals("select -a all", Marauder.selectAllAp())
        assertEquals("attack -t deauth", Marauder.deauth(false))
        assertEquals("attack -t deauth -c", Marauder.deauth(true))
    }
}
