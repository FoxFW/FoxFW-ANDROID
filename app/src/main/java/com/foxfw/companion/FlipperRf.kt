package com.foxfw.companion

import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * File-based live Sub-GHz RX/TX through the Flipper's CC1101 (int/ext), via the fox_subghz_io.fap
 * over whichever transport is ACTIVE (BLE or USB — see [FlipperTransport]).
 *
 * TX reports the FAP's REAL result: the FAP writes io_status.txt (Task F2) at op end; after
 * appStart we poll it and map to a [TxResult] — so a launched-but-failed TX surfaces honestly.
 */
object FlipperRf {
    const val DEV_INT = 0
    const val DEV_EXT = 1
    const val DEV_AUTO = 2

    /** Radio used for ALL forge/replay TX. Default INTERNAL (the Flipper's own antenna); AUTO can
     *  route to an external CC1101 that may have no antenna attached, radiating almost nothing. */
    var txDevice by mutableStateOf(DEV_INT)

    private const val DIR = "/ext/apps_data/fox_companion"
    private const val FAP = "/ext/apps/Fox/fox_subghz_io.fap"
    private const val TX_SUB = "$DIR/tx.sub"
    private const val RX_SUB = "$DIR/rx.sub"
    private const val STATUS = "$DIR/io_status.txt"
    private const val PROTO_SUB = "/ext/subghz/fox_fob.sub" // Route-B keyed .sub for ProtoPirate (F3)
    private const val SPEC = "$DIR/spec.txt"

    const val AM270 = 0; const val AM650 = 1; const val FM238 = 2; const val FM476 = 3

    /** Diagnostic breadcrumb from the last specAn() call — surfaced in the Spectrum Analyzer UI. */
    @Volatile var lastSpecDiag: String = ""

    /** Diagnostic breadcrumb from the last rx() call — surfaced in the Decode UI. */
    @Volatile var lastRxDiag: String = ""

    val connected: Boolean get() = FlipperTransport.active?.connected == true

    /**
     * Make sure the loader is free BEFORE launching the helper FAP. A resident FAP holds the loader
     * and appStart then false-succeeds (reports OK but nothing runs). The usual culprit is a live
     * Spectrum sweep that wasn't stopped — and that FAP is headless, so returnToDesktop()'s BACK
     * presses can't close it. So first drop the stop-flag the sweep polls, give it a beat to exit,
     * then back any open menu out to the desktop. Idempotent/harmless when nothing is running.
     */
    private suspend fun freeLoader(l: FlipperLink) {
        l.storageWrite(SPEC_STOP, byteArrayOf('x'.code.toByte())) // halt a resident continuous sweep
        delay(250)                                                // let it notice the flag and exit
        l.returnToDesktop()                                       // back any menu out to the desktop
    }

    /** Write the .sub to the Flipper and TX it live via CC1101; returns the real op result. */
    suspend fun tx(subText: String, freqHz: Int, dev: Int, preset: Int = AM650): TxResult {
        val l = FlipperTransport.active ?: return TxResult.NotConnected
        l.storageMkdir(DIR)
        freeLoader(l)
        l.storageDelete(STATUS)
        if (!l.storageWrite(TX_SUB, subText.toByteArray())) return TxResult.NotConnected
        if (!l.appStartSafe(FAP, "TX $TX_SUB $freqHz $dev $preset")) return TxResult.NotConnected
        return txResultFromStatus(true, awaitStatus(l))
    }

    /** Capture for ms via CC1101, then pull the captured .sub text back. Null on failure. */
    suspend fun rx(freqHz: Int, dev: Int, ms: Int, preset: Int = AM650): String? {
        val l = FlipperTransport.active ?: run { lastRxDiag = "no link"; return null }
        l.storageMkdir(DIR)
        freeLoader(l)
        l.storageDelete(RX_SUB)
        l.storageDelete(STATUS)
        if (!l.appStartSafe(FAP, "RX $freqHz $dev $ms $RX_SUB $preset")) { lastRxDiag = "Flipper busy — hold Back to its desktop, then retry"; return null }
        delay((ms + 1800).toLong())
        val data = l.storageRead(RX_SUB)
        if (data == null) {
            val fap = l.storageRead(STATUS)?.let { String(it).trim() } ?: "no response"
            lastRxDiag = "no capture file (FAP: $fap)"
            return null
        }
        lastRxDiag = "${data.size}B"
        return if (data.isNotEmpty()) String(data) else null
    }

    /**
     * Route B: a keyed protocol .sub (e.g. KIA V6) the Flipper renders with its OWN encoder.
     * Standalone one-tap TX is deferred (plugin encoder — ledger F3); park it for ProtoPirate.
     */
    suspend fun txProtocol(protoSub: String): TxResult {
        val l = FlipperTransport.active ?: return TxResult.NotConnected
        if (!l.storageWrite(PROTO_SUB, protoSub.toByteArray())) return TxResult.NotConnected
        return TxResult.SavedForProtoPirate
    }

    /**
     * Spectrum Analyzer / Frequency Finder: sweep [startHz,stopHz] by stepHz reading RSSI per
     * channel via the Flipper CC1101; returns the freq→RSSI points (null on failure). The peak
     * (spectrumPeak) is the Frequency Finder result.
     */
    @Volatile private var specDirReady = false
    private const val SPEC_STOP = "$SPEC.stop"

    /** One-shot sweep (Single, and the auto-fallback for Live on an old FAP). Polls the spec file
     *  directly (works whether or not the FAP writes io_status) and, if the FAP produced nothing
     *  — which on this firmware usually means loader_open reported success but the sweep never ran
     *  because a menu/app was open — backs out to the desktop and relaunches once. */
    suspend fun specAn(startHz: Int, stopHz: Int, stepHz: Int, dev: Int): List<SpecPoint>? {
        val l = FlipperTransport.active ?: run { lastSpecDiag = "no link"; return null }
        if (!specDirReady) { l.storageMkdir(DIR); specDirReady = true } // mkdir once, not per frame
        val channels = ((stopHz - startHz) / stepHz + 1).coerceIn(1, 4000)
        val budgetMs = channels * 14L + 4500 // boot + max-hold ~7ms/ch + BLE read margin
        var launched = false     // did appStart ever report success?
        var fapExit: String? = null  // did the FAP write an exit code (io_status)?
        for (attempt in 0..1) {
            if (attempt == 1) { l.returnToDesktop(); delay(400) } // 2nd try: free the loader first
            l.storageDelete(SPEC)
            l.storageDelete(STATUS)
            if (!l.appStart(FAP, "SWEEP $startHz $stopHz $stepHz $dev $SPEC 1")) continue
            launched = true
            var waited = 0L
            while (waited < budgetMs) {
                delay(250); waited += 250
                val data = l.storageRead(SPEC)
                if (data != null && data.isNotEmpty()) {
                    val pts = parseSpectrum(String(data))
                    if (pts.isNotEmpty()) { lastSpecDiag = "${data.size}B ${pts.size}ch (try ${attempt + 1})"; return pts }
                }
                val st = l.storageRead(STATUS) // FAP finished but wrote no usable spec -> stop waiting this try
                if (st != null && st.isNotEmpty()) { fapExit = String(st).trim(); break }
            }
        }
        // Precise cause (no masking) so the failure is actionable:
        lastSpecDiag = when {
            !launched -> "loader busy — a Flipper app is still running; hold BACK on the Flipper to close it, then retry"
            fapExit != null -> "FAP ran, no spectrum (exit $fapExit) — radio init / wrong fox_subghz_io.fap"
            else -> "FAP launched but wrote nothing — old/missing fox_subghz_io.fap on the SD (no SWEEP support)"
        }
        return null
    }

    /** Launch a CONTINUOUS sweep: the FAP re-sweeps + rewrites spec.txt until [specAnStop]. Launches
     *  the FAP ONCE (no per-frame appStart/blind-wait) — the smooth path. Returns true if launched. */
    suspend fun specAnStart(startHz: Int, stopHz: Int, stepHz: Int, dev: Int): Boolean {
        val l = FlipperTransport.active ?: run { lastSpecDiag = "no link"; return false }
        if (!specDirReady) { l.storageMkdir(DIR); specDirReady = true }
        l.returnToDesktop() // ensure the loader is free (loader_open can false-succeed over a menu)
        delay(300)
        l.storageDelete(SPEC_STOP) // clear stale stop flag
        l.storageDelete(SPEC)
        l.storageDelete(STATUS)    // resident (new) FAP leaves this absent; one-shot (old) FAP writes it at exit
        val started = l.appStart(FAP, "SWEEP $startHz $stopHz $stepHz $dev $SPEC 0") // passes=0 => continuous
        if (!started) lastSpecDiag = "Flipper busy — hold Back to its desktop, then retry"
        return started
    }

    /** Latest spectrum the continuous sweep has written (null if nothing yet). */
    suspend fun specAnLatest(): List<SpecPoint>? {
        val l = FlipperTransport.active ?: return null
        val data = l.storageRead(SPEC) ?: return null
        return parseSpectrum(String(data))
    }

    /**
     * Push the app-bundled fox_subghz_io.fap to the Flipper SD so the helper FAP always matches this
     * build (fixes "FAP launched but wrote nothing" = an old FAP without SWEEP/continuous support).
     * Verifies by reading the size back. Returns a short status for the UI.
     */
    suspend fun deployFap(bytes: ByteArray): String {
        val l = FlipperTransport.active ?: return "no link"
        if (bytes.isEmpty()) return "bundled FAP missing"
        l.storageMkdir("/ext/apps")
        l.storageMkdir("/ext/apps/Fox")
        l.storageDelete(FAP) // remove the stale FAP first so the write starts clean
        if (!l.storageWrite(FAP, bytes)) return "write failed"
        val back = l.storageRead(FAP)
        return if (back != null && back.size == bytes.size) "deployed ${bytes.size} B OK"
        else "verify failed (${back?.size ?: 0}/${bytes.size} B)"
    }

    /** True once the FAP has exited (io_status present) — i.e. an old/one-shot FAP, not a resident sweeper. */
    suspend fun specAnFapExited(): Boolean {
        val l = FlipperTransport.active ?: return true
        val s = l.storageRead(STATUS)
        return s != null && s.isNotEmpty()
    }

    /** Stop a continuous sweep by dropping the stop flag the FAP polls between passes. */
    suspend fun specAnStop() {
        val l = FlipperTransport.active ?: return
        l.storageWrite(SPEC_STOP, byteArrayOf('x'.code.toByte()))
    }

    private const val SUBGHZ_DIR = "/ext/subghz"

    /**
     * Recursively scan the Flipper SD's /ext/subghz tree (top level AND any subfolders the user
     * files captures into) for decoded "Fiat V1" captures, parsing each into a cracker-ready frame
     * (uid/btn/ctrl/hop). No demod — the decoded .sub already holds the fields.
     * Returns null only if the SD couldn't be listed at all (not connected / RPC error).
     */
    suspend fun loadFiatCaptures(onProgress: ((scanned: Int, found: Int) -> Unit)? = null): List<FiatCaptures.Frame>? {
        val l = FlipperTransport.active ?: return null
        val top = l.storageList(SUBGHZ_DIR) ?: return null // can't even list the SD -> report failure
        val out = ArrayList<FiatCaptures.Frame>()
        scanFiat(l, SUBGHZ_DIR, top, 0, out, intArrayOf(0), onProgress)
        return out
    }

    private suspend fun scanFiat(
        l: FlipperLink, dir: String, entries: List<Proto.FileEntry>, depth: Int,
        out: MutableList<FiatCaptures.Frame>, scanned: IntArray, onProgress: ((Int, Int) -> Unit)?,
    ) {
        for (e in entries) {
            if (out.size >= 500) return // sanity cap
            if (e.isDir) {
                if (depth >= 6 || e.name.startsWith(".")) continue
                val sub = l.storageList("$dir/${e.name}") ?: continue
                scanFiat(l, "$dir/${e.name}", sub, depth + 1, out, scanned, onProgress)
            } else if (e.name.endsWith(".sub", true) && e.size in 1..16384) {
                // decoded-protocol subs are tiny; skipping big files avoids reading RAW captures
                val path = "$dir/${e.name}"
                scanned[0]++
                if (scanned[0] % 10 == 0) onProgress?.invoke(scanned[0], out.size)
                val data = l.storageRead(path) ?: continue
                FiatCaptures.parse(String(data), path.removePrefix("$SUBGHZ_DIR/"))?.let { out.add(it) }
            }
        }
    }

    /** Poll io_status.txt (written by the FAP at op end) until present or timeout. */
    private suspend fun awaitStatus(l: FlipperLink, timeoutMs: Int = 6000): String? {
        var waited = 0
        while (waited < timeoutMs) {
            val d = l.storageRead(STATUS)
            if (d != null && d.isNotEmpty()) return String(d)
            delay(400)
            waited += 400
        }
        return null
    }
}
