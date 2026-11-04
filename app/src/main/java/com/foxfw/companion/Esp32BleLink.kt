package com.foxfw.companion

/**
 * ESP32 transport over the Flipper relay: drives the board through the Flipper's GPIO UART using the
 * Fox companion daemon's one-shot **ESP32 channel** — the same file-staging path [FlipperCli] uses for
 * "CLI". Each command stages `ESP32 <command>` to the daemon's cmd.txt, the (one-shot) daemon runs it
 * on the board via the GPIO UART and writes the reply to out.txt, then exits so the Flipper's loader is
 * free again. Needs fox_android_companion.fap on the Flipper.
 *
 * Request/response per command, not a continuous stream. That's fine for the ESP32: the BOARD keeps
 * its own scan/attack state between daemon invocations, so e.g. a deauth keeps running until a later
 * `stopscan`.
 */
class Esp32BleLink : Esp32Link {
    companion object {
        const val DEFAULT_CAPTURE_MS = 1500
    }

    private var lineCb: ((String) -> Unit)? = null
    private val cli = FlipperCli() // the proven one-shot daemon client; we just use the ESP32 channel

    override val connected: Boolean get() = FlipperTransport.active?.connected == true
    override fun onLine(cb: (String) -> Unit) { lineCb = cb }
    override suspend fun disconnect() {}

    override suspend fun send(command: String) = sendCapturing(command, DEFAULT_CAPTURE_MS)

    /** One-shot daemon — nothing stays resident, so there is nothing to stop. Kept for API parity. */
    suspend fun stopDaemon() {}

    /**
     * Relay one command to the ESP32 through the daemon's ESP32 channel and feed each reply line back.
     * [captureMs] is how long the daemon listens to the board's UART for this command's output.
     */
    suspend fun sendCapturing(command: String, captureMs: Int) {
        if (FlipperTransport.active == null) {
            lineCb?.invoke("[relay] no Flipper link — connect it on the Device tab")
            return
        }
        val out = cli.exchangeQuiet("ESP32 $command", captureMs)
        if (out != null) {
            out.split('\n').forEach { val t = it.trimEnd(); if (t.isNotEmpty()) lineCb?.invoke(t) }
        } else {
            lineCb?.invoke("[relay] no reply — return the Flipper to its desktop, then retry")
        }
    }
}
