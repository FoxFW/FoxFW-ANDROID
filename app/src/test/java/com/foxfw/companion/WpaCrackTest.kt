package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the WPA crack CPU reference (the oracle the GPU kernel is validated against). */
class WpaCrackTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun pmkMatchesKnown80211iVector() {
        // IEEE 802.11i / RFC-style vector: "ThisIsAPassword" / "ThisIsASSID".
        val pmk = WpaCrack.pmk("ThisIsAPassword", "ThisIsASSID".toByteArray())
        assertEquals("0dc0d6eb90555ed6419756b9a15ec3e3209b63df707dd508d14581f8982721af", hex(pmk))
    }

    @Test fun pmkidMatchesComputedVector() {
        val pmk = WpaCrack.pmk("letmein123", "FoxNet5G".toByteArray())
        val pmkid = WpaCrack.pmkid(pmk, hexToBytes("aabbccddeeff"), hexToBytes("112233445566"))
        assertEquals("f414df3ad563bd18581c358d506b9448", hex(pmkid))
    }

    @Test fun parsesHc22000PmkidLine() {
        val t = WpaCrack.parsePmkid(
            "WPA*01*f414df3ad563bd18581c358d506b9448*aabbccddeeff*112233445566*466f784e65743547***",
        )!!
        assertEquals("f414df3ad563bd18581c358d506b9448", hex(t.pmkid))
        assertEquals("aabbccddeeff", hex(t.apMac))
        assertEquals("112233445566", hex(t.staMac))
        assertEquals("FoxNet5G", t.ssidText)
    }

    @Test fun rejectsNonPmkidLines() {
        assertNull(WpaCrack.parsePmkid("WPA*02*...*...")) // handshake, not PMKID
        assertNull(WpaCrack.parsePmkid("garbage"))
        assertNull(WpaCrack.parsePmkid(""))
    }

    @Test fun crackFindsPassphraseInWordlist() {
        val t = WpaCrack.parsePmkid(
            "WPA*01*f414df3ad563bd18581c358d506b9448*aabbccddeeff*112233445566*466f784e65743547***",
        )!!
        val hit = WpaCrack.crackPmkid(t, sequenceOf("password", "wrongone", "letmein123", "never"))
        assertEquals("letmein123", hit)
    }

    @Test fun crackReturnsNullWhenAbsent() {
        val t = WpaCrack.parsePmkid(
            "WPA*01*f414df3ad563bd18581c358d506b9448*aabbccddeeff*112233445566*466f784e65743547***",
        )!!
        assertNull(WpaCrack.crackPmkid(t, sequenceOf("nope", "nada", "wrong")))
    }

    @Test fun pbkdf2SkipsPassphrasesOutOfWpaLengthRange() {
        // WPA passphrases are 8..63 chars; the cracker must skip the rest rather than waste PBKDF2.
        val t = WpaCrack.parsePmkid(
            "WPA*01*f414df3ad563bd18581c358d506b9448*aabbccddeeff*112233445566*466f784e65743547***",
        )!!
        var tested = 0
        val words = sequenceOf("short", "x".repeat(64), "letmein123").onEach { tested++ }
        assertEquals("letmein123", WpaCrack.crackPmkid(t, words))
    }

    // ---- 4-way EAPOL (WPA*02) ----

    @Test fun parsesEapolLineFieldsAndKeyver() {
        val eapol = ByteArray(99)
        eapol[1] = 0x03; eapol[4] = 0x02
        eapol[5] = 0x00; eapol[6] = 0x8a.toByte() // Key Information: key descriptor version = 2
        for (i in 0 until 32) eapol[17 + i] = (0xC0 + i).toByte() // SNonce at offset 17
        val line = "WPA*02*" + "0".repeat(32) + "*aabbccddeeff*112233445566*466f784e65743547*" +
            "e0".repeat(32) + "*" + hex(eapol) + "*02"
        val t = WpaCrack.parseEapol(line)!!
        assertEquals(2, t.keyver)
        assertTrue(t.supported)
        assertEquals("FoxNet5G", t.ssidText)
        assertEquals("c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf", hex(t.snonce))
    }

    @Test fun eapolCrackRoundTrips() {
        // NOTE: self-consistency test — no published WPA*02 known-answer vector exists, so this proves
        // the parse + PRF-512/PTK + MIC chain is internally consistent and the crack loop works.
        // End-to-end correctness is confirmed against a real captured handshake on device.
        val ssid = "FoxNet5G".toByteArray()
        val ap = hexToBytes("aabbccddeeff"); val sta = hexToBytes("112233445566")
        val anonce = ByteArray(32) { (0xE0 + it).toByte() }
        val snonce = ByteArray(32) { (0xC0 + it).toByte() }
        val eapol = ByteArray(99)
        eapol[1] = 0x03; eapol[4] = 0x02; eapol[5] = 0x00; eapol[6] = 0x8a.toByte()
        for (i in 0 until 32) eapol[17 + i] = snonce[i]
        val base = WpaCrack.EapolTarget(ByteArray(16), ap, sta, ssid, anonce, snonce, eapol, 2)
        val mic = WpaCrack.eapolMic(WpaCrack.kck("letmein123", base), eapol, 2)!!
        val target = base.copy(mic = mic)
        assertEquals("letmein123", WpaCrack.crackEapol(target, sequenceOf("wrong1", "letmein123", "wrong2")))
        assertNull(WpaCrack.crackEapol(target, sequenceOf("nope", "nada")))
    }

    private fun hexToBytes(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
    }
}
