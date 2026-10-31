package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * End-to-end: parse a real capture (aircrack-ng's canonical wpa.cap, a full WPA 4-way handshake) into
 * hc22000, then crack it. This is the independent known-answer that validates BOTH the pcap parser
 * AND the EAPOL MIC crypto (PMK→PTK→KCK→MIC) — if the real captured MIC is reproduced for the known
 * passphrase, the whole chain is correct.
 */
class PcapTest {
    private fun capBytes(): ByteArray =
        javaClass.getResourceAsStream("/wpa.cap")!!.readBytes()

    @Test fun extractsEapolFromWpaCapAndCracks() {
        val lines = Pcap.toHc22000(capBytes())
        val wpa02 = lines.firstOrNull { it.startsWith("WPA*02*") }
        assertNotNull("no WPA*02 extracted; got: $lines", wpa02)
        val t = WpaCrack.parseEapol(wpa02!!)!!
        val hit = WpaCrack.crackEapol(
            t, sequenceOf("password", "biscotte", "12345678", "dictionary", "notthis"),
        )
        assertEquals("biscotte", hit)
    }
}
