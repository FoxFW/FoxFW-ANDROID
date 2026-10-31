package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure spectrum parse + peak (Frequency Finder) — the SWEEP file is "<freqHz> <rssi>\n" per line. */
class SpecAnTest {
    @Test
    fun parsesLines() {
        val s = parseSpectrum("433050000 -92\n433075000 -40\n433100000 -88\n")
        assertEquals(3, s.size)
        assertEquals(433075000, s[1].freqHz)
        assertEquals(-40, s[1].rssi)
    }

    @Test
    fun peakPicksStrongest() {
        val p = spectrumPeak(parseSpectrum("433050000 -92\n433075000 -40\n433100000 -88\n"))!!
        assertEquals(433075000, p.freqHz)
        assertEquals(-40, p.rssi)
    }

    @Test
    fun ignoresGarbageLines() {
        val s = parseSpectrum("bad\n433050000 -50\n\nx y\n")
        assertEquals(1, s.size)
        assertEquals(-50, s[0].rssi)
    }

    @Test
    fun emptyIsEmpty() {
        assertTrue(parseSpectrum("").isEmpty())
        assertNull(spectrumPeak(emptyList()))
    }
}
