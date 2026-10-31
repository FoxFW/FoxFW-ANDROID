package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the pure WiFi/BLE band-plan math (no Android). */
class BandScanTest {
    @Test fun wifi24FreqToChannel() {
        assertEquals(1, BandScan.freqToChannel(2412))
        assertEquals(6, BandScan.freqToChannel(2437))
        assertEquals(11, BandScan.freqToChannel(2462))
        assertEquals(13, BandScan.freqToChannel(2472))
        assertEquals(14, BandScan.freqToChannel(2484)) // special case
    }

    @Test fun wifi5FreqToChannel() {
        assertEquals(36, BandScan.freqToChannel(5180))
        assertEquals(149, BandScan.freqToChannel(5745))
        assertEquals(165, BandScan.freqToChannel(5825))
    }

    @Test fun sixGhzAndJunkAreNotWifi24or5() {
        assertNull(BandScan.freqToChannel(5955))  // 6 GHz (WiFi 6E) — out of scope
        assertNull(BandScan.freqToChannel(2400))  // not a channel center
        assertNull(BandScan.freqToChannel(900))
    }

    @Test fun bandClassification() {
        assertEquals(BandScan.Band.GHZ24, BandScan.bandOf(2437))
        assertEquals(BandScan.Band.GHZ5, BandScan.bandOf(5180))
        assertNull(BandScan.bandOf(5955))
    }

    @Test fun channelToCenterRoundTrips() {
        assertEquals(2412, BandScan.channelToCenter(1, BandScan.Band.GHZ24))
        assertEquals(2484, BandScan.channelToCenter(14, BandScan.Band.GHZ24))
        assertEquals(5180, BandScan.channelToCenter(36, BandScan.Band.GHZ5))
    }

    @Test fun rssiToFractionClamps() {
        assertEquals(0f, BandScan.rssiToFraction(-90), 0.001f)
        assertEquals(1f, BandScan.rssiToFraction(-30), 0.001f)
        assertEquals(0.5f, BandScan.rssiToFraction(-60), 0.001f)
        assertEquals(0f, BandScan.rssiToFraction(-120), 0.001f) // below floor clamps to 0
        assertEquals(1f, BandScan.rssiToFraction(-10), 0.001f)  // above ceiling clamps to 1
    }

    @Test fun aggregateCountsAndPeaksPerChannel() {
        val aps = listOf(
            BandScan.Ap("HomeNet", "aa", 2437, -45),   // ch6
            BandScan.Ap("Neighbour", "bb", 2437, -70),  // ch6
            BandScan.Ap("Guest", "cc", 2412, -60),      // ch1
            BandScan.Ap("FarAway5G", "dd", 5180, -80),  // ch36 (wrong band -> excluded for 2.4)
        )
        val g24 = BandScan.aggregate(aps, BandScan.Band.GHZ24)
        // every 2.4 GHz channel is represented (grid of 14)
        assertEquals(14, g24.size)
        val ch6 = g24.first { it.channel == 6 }
        assertEquals(2, ch6.apCount)
        assertEquals(-45, ch6.maxRssi)        // strongest wins
        assertEquals("HomeNet", ch6.topSsid)
        val ch1 = g24.first { it.channel == 1 }
        assertEquals(1, ch1.apCount)
        val empty = g24.first { it.channel == 11 }
        assertEquals(0, empty.apCount)         // no AP -> count 0, floor rssi
        assertTrue(empty.maxRssi <= -100)
        // the 5 GHz AP must not leak into the 2.4 GHz aggregate
        assertEquals(0, g24.sumOf { it.apCount } - 3)
    }
}
