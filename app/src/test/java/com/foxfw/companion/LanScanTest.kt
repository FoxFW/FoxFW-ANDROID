package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanScanTest {
    @Test fun ipRoundTrips() {
        assertEquals("192.168.1.197", LanScan.intToIp(LanScan.ipToInt("192.168.1.197")!!))
        assertEquals("0.0.0.0", LanScan.intToIp(LanScan.ipToInt("0.0.0.0")!!))
        assertEquals("255.255.255.255", LanScan.intToIp(LanScan.ipToInt("255.255.255.255")!!))
    }

    @Test fun slash24ExcludesNetworkAndBroadcast() {
        val h = LanScan.hostsForCidr("192.168.1.197", 24)
        assertEquals(254, h.size)
        assertEquals("192.168.1.1", h.first())
        assertEquals("192.168.1.254", h.last())
    }

    @Test fun slash32IsJustSelf() {
        assertEquals(listOf("10.0.0.5"), LanScan.hostsForCidr("10.0.0.5", 32))
    }

    @Test fun wideRangeIsCapped() {
        assertEquals(1024, LanScan.hostsForCidr("10.0.0.1", 16).size)
    }

    @Test fun rejectsMalformedInput() {
        assertNull(LanScan.ipToInt("999.1.1.1"))
        assertNull(LanScan.ipToInt("nope"))
        assertTrue(LanScan.hostsForCidr("bad", 24).isEmpty())
    }
}
