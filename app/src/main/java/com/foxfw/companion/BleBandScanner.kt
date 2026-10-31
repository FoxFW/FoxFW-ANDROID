package com.foxfw.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

/**
 * BLE side of the phone Band Analyzer. A phone can't tell which 2.4 GHz channel a BLE advert landed
 * on (the API doesn't expose it), so we report aggregate 2.4 GHz BLE ACTIVITY: how many distinct
 * advertisers are nearby and how loud the strongest is. BLE adverts hop the 3 primary channels
 * (2402 / 2426 / 2480 MHz), i.e. the low, middle and top of the 2.4 GHz band.
 */
class BleBandScanner(context: Context) {
    private val app = context.applicationContext

    data class BleActivity(val devices: Int, val maxRssi: Int, val names: List<String>)

    private val scanner
        get() = try {
            (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner
        } catch (e: Exception) { null }

    /**
     * Start a low-latency scan; pushes a rolling [BleActivity] as adverts arrive. Returns a cleanup
     * lambda. No-op (empty cleanup) if BT is off or the permission is missing.
     */
    @SuppressLint("MissingPermission")
    fun start(onUpdate: (BleActivity) -> Unit): () -> Unit {
        val s = scanner ?: return {}
        val bestRssi = HashMap<String, Int>()
        val names = HashMap<String, String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address ?: return
                bestRssi[addr] = maxOf(bestRssi[addr] ?: -127, result.rssi)
                result.scanRecord?.deviceName?.let { if (it.isNotBlank()) names[addr] = it }
                onUpdate(
                    BleActivity(
                        devices = bestRssi.size,
                        maxRssi = bestRssi.values.maxOrNull() ?: -127,
                        names = names.values.distinct().sorted().take(6),
                    )
                )
            }
            override fun onScanFailed(errorCode: Int) { /* leave last activity as-is */ }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return try {
            s.startScan(null, settings, cb)
            val cleanup: () -> Unit = { try { s.stopScan(cb) } catch (e: Exception) {} }
            cleanup
        } catch (e: Exception) {
            val noop: () -> Unit = {}
            noop
        }
    }
}
