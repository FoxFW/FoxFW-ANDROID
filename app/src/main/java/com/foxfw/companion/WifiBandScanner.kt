package com.foxfw.companion

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Thin Android-WiFi wrapper for the phone Band Analyzer. Reads [WifiManager] scan results (the APs
 * the phone currently sees) and maps them to [BandScan.Ap].
 *
 * Robustness notes (why it's more than one getScanResults call):
 *  - The scan-complete broadcast only fires when a fresh scan finishes; on a phone where scans are
 *    throttled it may never arrive, so the screen ALSO polls the cached results on a timer.
 *  - getScanResults() can momentarily be empty/stale; the currently-connected AP is always folded in
 *    as a fallback so an on-WiFi user never sees a totally blank band.
 *  - Failures are recorded in [diag] instead of being swallowed, so the UI can say why it's empty.
 */
class WifiBandScanner(context: Context) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    var diag: String = "idle"
        private set

    fun wifiEnabled(): Boolean = try { wifi.isWifiEnabled } catch (e: Exception) { false }

    @SuppressLint("MissingPermission")
    fun currentAps(): List<BandScan.Ap> {
        val out = ArrayList<BandScan.Ap>()
        try {
            val results = wifi.scanResults
            for (r in results) {
                if (r.frequency <= 0) continue
                out.add(BandScan.Ap(r.SSID ?: "", r.BSSID ?: "", r.frequency, r.level))
            }
            diag = if (results.isEmpty()) "scan cache empty" else "scanResults=${results.size}"
        } catch (e: SecurityException) {
            diag = "permission error"
            Log.w("BandScan", "scanResults SecurityException", e)
        } catch (e: Exception) {
            diag = "scan error: ${e.message}"
            Log.w("BandScan", "scanResults error", e)
        }
        // Always include the currently-connected AP (survives an empty/stale scan cache).
        connectedAp()?.let { c -> if (out.none { it.bssid.equals(c.bssid, true) }) out.add(c) }
        Log.i("BandScan", "currentAps -> ${out.size} aps ($diag), wifiOn=${wifiEnabled()}")
        return out
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun connectedAp(): BandScan.Ap? = try {
        val info = wifi.connectionInfo ?: return null
        val freq = info.frequency
        if (freq <= 0 || info.networkId == -1) {
            null
        } else {
            val ssid = info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "(connected)"
            BandScan.Ap(ssid = ssid, bssid = info.bssid ?: "self", freqMhz = freq, rssi = info.rssi)
        }
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    fun requestScan(): Boolean = try { wifi.startScan() } catch (e: Exception) { false }

    /**
     * Register for scan-complete broadcasts (push the latest APs on each completion), push the
     * currently-cached results immediately, and kick one scan. Returns a cleanup lambda.
     */
    fun start(onResults: (List<BandScan.Ap>) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { onResults(currentAps()) }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        // SCAN_RESULTS_AVAILABLE_ACTION is a protected system broadcast -> NOT_EXPORTED is correct.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onResults(currentAps()) // show whatever the OS already has, instantly
        requestScan()
        return { try { app.unregisterReceiver(receiver) } catch (e: Exception) {} }
    }
}
