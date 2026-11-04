package com.foxfw.companion

/**
 * Running tally for continuous handshake auto-harvest. Each capture window (one sniffpmkid pass)
 * is parsed into a [HarvestSession.Window] of counts; the session sums them and reports how many
 * NEW captures each window produced. Counts are capture EVENTS across the session — the actual
 * de-duplicated handshakes land as .pcap files on the board SD.
 */
class HarvestSession {
    data class Window(val received: Int, val complete: Int, val pwnd: Int)

    var windows = 0; private set
    var totalReceived = 0; private set
    var totalComplete = 0; private set
    var totalPwnd = 0; private set

    /** Fold one window's counts in; returns the new captures it produced (max of complete / pwnd). */
    fun add(w: Window): Int {
        windows++
        totalReceived += w.received
        totalComplete += w.complete
        totalPwnd += w.pwnd
        return maxOf(w.complete, w.pwnd)
    }
}

/** Extracts the per-metric high-water counts from a window of Esp32 sniffpmkid serial output. */
object HarvestParse {
    // ESP32 v1.20.0 (C5) prints a bare "    EAPOL: N" frames-seen line in its stats block
    // (no "Received" prefix) plus a separate "Complete EAPOL: N". Match the bare line at line-start so
    // "Complete EAPOL" is NOT counted as received; still accept the older "Received EAPOL" wording.
    private val RECEIVED = Regex("""^[ \t]*(?:Received )?EAPOL:[ \t]*(\d+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val COMPLETE = Regex("""Complete EAPOL:\s*(\d+)""", RegexOption.IGNORE_CASE)
    // Older/pwnagotchi builds print "Pwnd #: N"; v1.20.0 does not. Harmless when absent.
    private val PWND = Regex("""Pwnd\s*#?:\s*(\d+)""", RegexOption.IGNORE_CASE)

    private fun maxOf(re: Regex, text: String): Int =
        re.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0

    fun parseWindow(out: String) = HarvestSession.Window(
        received = maxOf(RECEIVED, out),
        complete = maxOf(COMPLETE, out),
        pwnd = maxOf(PWND, out),
    )
}
