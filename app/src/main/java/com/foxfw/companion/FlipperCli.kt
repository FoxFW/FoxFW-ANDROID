package com.foxfw.companion

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs Flipper CLI commands through the Fox companion IO daemon (fox_companion_io.fap) over whatever
 * Flipper link is live — BLE or USB-to-Flipper ([FlipperTransport.active]) — using the same proven
 * file-staging RPC path as the ESP32 relay: stage "CLI <command>" to the daemon's command file,
 * launch the daemon once, read the board's reply back. Works over Bluetooth; USB is just faster.
 *
 * Request/response per command. The daemon runs the command on the Flipper and writes its output to
 * the out file. Needs the fox_companion_io daemon FAP on the Flipper (firmware side); until that
 * ships, [run] reports that the companion IO app is missing rather than hanging.
 */
class FlipperCli {
    companion object {
        const val FAP = "/ext/apps/System/fox_android_companion.fap"
        const val DIR = "/ext/apps_data/fox_companion"
        const val CMD = "$DIR/cmd.txt"
        const val OUT = "$DIR/out.txt"
        const val STOP = "$DIR/stop"
        const val DEFAULT_CAPTURE_MS = 2500
    }

    private var lineCb: ((String) -> Unit)? = null
    private val mutex = Mutex()

    val connected: Boolean get() = FlipperTransport.active?.connected == true
    fun onLine(cb: (String) -> Unit) { lineCb = cb }

    /**
     * One-shot launch: stage [line]+[captureMs] to cmd.txt, launch the daemon for THIS command only,
     * and return its reply (or null). The daemon runs the command and exits immediately, so the
     * Flipper's loader is free again straight after. If [link].appStart returns false the loader is
     * busy (another app is open on the Flipper) — nothing is left running — and we surface that as
     * access-denied instead of hanging.
     */
    private suspend fun oneShot(link: FlipperLink, line: String, captureMs: Int, report: Boolean = true): String? {
        link.storageMkdir(DIR)
        link.storageDelete(OUT, false)
        if (!link.storageWrite(CMD, "$line\n$captureMs\n".toByteArray(Charsets.UTF_8))) return null
        if (!link.appStart(FAP, DIR)) { if (report) CompanionLink.report(false); return null }  // loader busy
        var waited = 0
        while (waited < captureMs + 6000) {
            delay(400); waited += 400
            val out = link.storageRead(OUT)
            if (out != null) { if (report) CompanionLink.report(true); return String(out, Charsets.UTF_8) }
        }
        if (report) CompanionLink.report(false)   // launched but no reply in time
        return null
    }

    /**
     * Send one Flipper CLI [command] and feed each reply line to the [onLine] callback. Serialized so
     * concurrent sends don't interleave on the single RPC channel. Relaunches once if the daemon idled.
     */
    suspend fun run(command: String, captureMs: Int = DEFAULT_CAPTURE_MS): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val link = FlipperTransport.active
            if (link == null) { lineCb?.invoke("[cli] no Flipper link — connect it on the Device tab"); return@withContext }
            val out = oneShot(link, "CLI $command", captureMs)
            if (out != null) {
                out.split('\n').forEach { val t = it.trimEnd(); if (t.isNotEmpty()) lineCb?.invoke(t) }
                Log.i("FoxCli", "cmd='$command' out=${out.length}")
            } else {
                lineCb?.invoke("[cli] Flipper busy — return it to its desktop, then retry")
            }
        }
    }

    /** Generic one-shot daemon exchange: write "<line>\n<ms>\n", return the reply text (or null). */
    suspend fun exchange(line: String, captureMs: Int = 2500): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val link = FlipperTransport.active ?: return@withContext null
            oneShot(link, line, captureMs)
        }
    }

    /** Like [exchange] but never touches the companion access banner / command bank — for per-command
     *  relays (e.g. ESP32) where a no-reply is a normal, local outcome shown in the console, not a
     *  config-sync failure that should pop the global banner. */
    suspend fun exchangeQuiet(line: String, captureMs: Int = 2500): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val link = FlipperTransport.active ?: return@withContext null
            oneShot(link, line, captureMs, report = false)
        }
    }

    /** DEVICE receiver status via the daemon RXSTATUS channel (ext CC1101 + ESP32 Fox-FW probe). */
    suspend fun receiverStatus(captureMs: Int = 3000): ReceiverStatus? {
        val out = exchange("RXSTATUS", captureMs) ?: return null
        fun field(k: String) = Regex("$k=(\\w+)").find(out)?.groupValues?.get(1)
        return ReceiverStatus(
            field("cc1101") ?: "wait",
            field("esp32") ?: "wait",
            field("protocols")?.toIntOrNull(),
            field("apps")?.toIntOrNull(),
            field("sd_used_kb")?.toLongOrNull(),
            field("sd_total_kb")?.toLongOrNull(),
        )
    }
}

/**
 * cc1101Ext: "ready"/"wait"; esp32: "fox"/"notfox"/"none"/"wait"; protocols: live firmware count.
 * apps: installed .fap count; sdUsedKb/sdTotalKb: SD usage in KiB. All null if the daemon is older
 * or the field was absent.
 */
data class ReceiverStatus(
    val cc1101Ext: String,
    val esp32: String,
    val protocols: Int? = null,
    val apps: Int? = null,
    val sdUsedKb: Long? = null,
    val sdTotalKb: Long? = null,
)
