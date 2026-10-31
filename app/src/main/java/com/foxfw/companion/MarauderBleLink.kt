package com.foxfw.companion

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Marauder transport for when the BFFB v2 stays wired to the Flipper's GPIO UART (no USB cable to the
 * phone). It drives the board THROUGH the Flipper over whatever Flipper link is live — BLE or
 * USB-to-Flipper ([FlipperTransport.active]) — using the same proven file-staging RPC path as the
 * SubGHz TX FAP (verified OTA over BLE): stage the command to a file, AppStart the `fox_marauder_io`
 * FAP with a capture window, read the board's reply back.
 *
 * Request/response per command, not a continuous stream. That's fine for Marauder: the BOARD keeps
 * its own scan/attack state between FAP invocations, so e.g. a deauth keeps running after the FAP
 * exits, until a later `stopscan` command. Needs the `fox_marauder_io.fap` flashed on the Flipper.
 */
class MarauderBleLink : MarauderLink {
    companion object {
        const val FAP = "/ext/apps/Fox/fox_marauder_io.fap"
        const val DIR = "/ext/apps_data/fox_companion"
        const val CMD = "$DIR/mar_cmd.txt"
        const val OUT = "$DIR/mar_out.txt"
        const val STOP = "$DIR/mar_stop"
        const val DEFAULT_CAPTURE_MS = 1500

        /** Push the app-bundled fox_marauder_io.fap to the Flipper SD; verify by size. UI status. */
        suspend fun deployFap(bytes: ByteArray): String {
            val l = FlipperTransport.active ?: return "no Flipper link"
            if (bytes.isEmpty()) return "bundled FAP missing"
            l.storageMkdir("/ext/apps")
            l.storageMkdir("/ext/apps/Fox")
            l.storageDelete(FAP, false)
            if (!l.storageWrite(FAP, bytes)) return "write failed"
            val back = l.storageRead(FAP)
            return if (back != null && back.size == bytes.size) "deployed ${bytes.size} B OK"
            else "verify failed (${back?.size ?: 0}/${bytes.size} B)"
        }
    }

    private var lineCb: ((String) -> Unit)? = null
    @Volatile private var daemonUp = false // the resident fox_marauder_io FAP has been launched this session

    // Only ONE relay op may touch the Flipper at a time. Concurrent ops (a live-loop list -a racing a
    // manual Stop) interleave requests on the single BLE RPC channel and desync it → every op after
    // fails. Serializing here keeps the channel sane; callers just queue.
    private val mutex = Mutex()

    override val connected: Boolean get() = FlipperTransport.active?.connected == true
    override fun onLine(cb: (String) -> Unit) { lineCb = cb }
    override suspend fun disconnect() {}

    override suspend fun send(command: String) = sendCapturing(command, DEFAULT_CAPTURE_MS)

    /** Launch the resident daemon ONCE per session (not per command — repeated launches jam the loader). */
    private suspend fun ensureDaemon(link: FlipperLink) {
        if (daemonUp) return
        link.storageMkdir(DIR)
        link.storageDelete(STOP, false)
        link.storageDelete(CMD, false) // clear any stale command so a fresh daemon doesn't run it
        // Plain appStart (NOT appStartSafe → no back-tap storm). The firmware returns SYSTEM_LOCKED
        // (false here) if a daemon — ours, from a prior session — is already running; that's fine, we
        // use the running one. The command's OUT poll is the real liveness test; a timeout triggers
        // the STOP-and-relaunch recovery in sendCapturing. Either way the daemon is considered up.
        val launched = link.appStart(FAP, DIR)
        Log.i("FoxMar", "daemon appStart=$launched (false usually = already running)")
        daemonUp = true
        if (launched) delay(1500) // a fresh launch needs a moment to grab the UART
    }

    /** Ask the daemon to stop; it stopscans the board, releases the UART, and exits. */
    suspend fun stopDaemon() = mutex.withLock {
        val link = FlipperTransport.active
        if (link != null) runCatching { link.storageWrite(STOP, byteArrayOf('x'.code.toByte())) }
        daemonUp = false
    }

    /**
     * Stage [command] + a [captureMs] window to the resident daemon and feed back each reply line.
     * No per-command FAP launch — the daemon is started once by [ensureDaemon]. If it had idled out
     * (no reply), relaunch and retry once.
     */
    suspend fun sendCapturing(command: String, captureMs: Int): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val link = FlipperTransport.active
            if (link == null) { lineCb?.invoke("[relay] no Flipper link — connect it on the Device tab"); return@withContext }
            link.storageMkdir(DIR)
            repeat(2) { attempt ->
                ensureDaemon(link)
                link.storageDelete(OUT, false)
                val wrote = link.storageWrite(CMD, "$command\n$captureMs\n".toByteArray(Charsets.UTF_8))
                if (!wrote) { lineCb?.invoke("[relay] couldn't stage the command (link busy)"); return@withContext }
                var waited = 0
                var out: ByteArray? = null
                while (waited < captureMs + 6000) {
                    delay(400); waited += 400
                    out = link.storageRead(OUT)
                    if (out != null) break
                }
                Log.i("FoxMar", "cmd='$command' attempt=$attempt out=${out?.size ?: -1}")
                if (out != null) {
                    String(out, Charsets.UTF_8).split('\n').forEach { val t = it.trimEnd(); if (t.isNotEmpty()) lineCb?.invoke(t) }
                    return@withContext
                }
                // No reply: a stale daemon is likely holding the loader lock. Tell it to exit (which
                // unlocks the loader), wait for it to quit, then the next attempt relaunches clean —
                // a software recovery so you never have to reboot the Flipper.
                daemonUp = false
                if (attempt == 0) {
                    link.storageWrite(STOP, byteArrayOf('x'.code.toByte()))
                    delay(2800)
                }
            }
            lineCb?.invoke("[relay] no reply — hold the Flipper at its desktop, then retry")
        }
    }
}
