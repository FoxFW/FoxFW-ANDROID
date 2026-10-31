package com.foxfw.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/**
 * BLE central for the ChameleonUltra over its Nordic-UART service. Serialized request/response:
 * one command in flight, responses reassembled from notifications and matched by command id.
 * Pairing-enabled units use LESC+MITM with fixed passkey 123456 (auto-answered where possible).
 */
@SuppressLint("MissingPermission")
class ChameleonBle(private val app: Context) {

    companion object {
        val SVC: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")  // write  (phone->device)
        val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")  // notify (device->phone)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val PASSKEY = "123456"
    }

    var conn by mutableStateOf(Conn.DISCONNECTED); private set
    var deviceName by mutableStateOf<String?>(null); private set

    private val main = Handler(Looper.getMainLooper())
    private val mgr get() = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = mgr.adapter
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var mtu = 20
    private var pairingRegistered = false

    // Serialized command machinery
    private class Pending(val cmd: Int, val onResult: (Int, ByteArray?) -> Unit, val timeout: Runnable)
    private class QCmd(val cmd: Int, val data: ByteArray?, val timeoutMs: Long, val onResult: (Int, ByteArray?) -> Unit)
    private var pending: Pending? = null
    private val queue = ArrayDeque<QCmd>()
    private var rbuf = ByteArray(0)

    // ---- connection ----
    fun connect() {
        val ad = adapter ?: return
        if (!ad.isEnabled) return
        if (conn != Conn.DISCONNECTED) return
        registerPairing()
        // bonded fast path
        val bonded = try { ad.bondedDevices } catch (_: Throwable) { null }
        val known = bonded?.firstOrNull {
            val n = try { it.name } catch (_: Throwable) { null } ?: ""
            n.startsWith("ChameleonUltra") || n.startsWith("CU-")
        }
        if (known != null) { connectTo(known, known.name ?: "ChameleonUltra"); return }
        conn = Conn.SCANNING
        val scanner = ad.bluetoothLeScanner ?: run { conn = Conn.DISCONNECTED; return }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        main.postDelayed({
            if (conn == Conn.SCANNING) { try { scanner.stopScan(scanCb) } catch (_: Throwable) {}; conn = Conn.DISCONNECTED }
        }, 12_000)
    }

    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null; rxChar = null; conn = Conn.DISCONNECTED; deviceName = null
        failAll(); unregisterPairing()
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = dev.name ?: result.scanRecord?.deviceName
            val nus = result.scanRecord?.serviceUuids?.any { it.uuid == SVC } == true
            val ok = nus || (name != null && (name.startsWith("ChameleonUltra") || name.startsWith("CU-")))
            if (!ok) return
            try { adapter?.bluetoothLeScanner?.stopScan(this) } catch (_: Throwable) {}
            connectTo(dev, name ?: "ChameleonUltra")
        }
    }

    private fun connectTo(dev: BluetoothDevice, name: String) {
        conn = Conn.CONNECTING; deviceName = name
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            dev.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else dev.connectGatt(app, false, gattCb)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                conn = Conn.DISCONNECTED; deviceName = null; rxChar = null; mtu = 20; failAll()
                try { g.close() } catch (_: Throwable) {}
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) { mtu = if (m in 23..517) m else 23; g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SVC) ?: run { try { g.disconnect() } catch (_: Throwable) {}; return }
            rxChar = svc.getCharacteristic(RX)
            val tx = svc.getCharacteristic(TX) ?: run { try { g.disconnect() } catch (_: Throwable) {}; return }
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else { @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) } }
            } else { conn = Conn.CONNECTED }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            conn = Conn.CONNECTED   // notifications enabled; ready for commands
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") if (ch.uuid == TX) onRx(ch.value ?: return)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == TX) onRx(value)
        }
    }

    // ---- serialized request / response ----

    /** Run a command; onResult(status, data). status<0 = transport error/timeout. Serialized. */
    fun command(cmd: Int, data: ByteArray? = null, timeoutMs: Long = 2500, onResult: (Int, ByteArray?) -> Unit) {
        main.post {
            if (conn != Conn.CONNECTED) { onResult(-1, null); return@post }
            if (pending == null) startCommand(cmd, data, timeoutMs, onResult)
            else queue.addLast(QCmd(cmd, data, timeoutMs, onResult))
        }
    }

    private fun startCommand(cmd: Int, data: ByteArray?, timeoutMs: Long, onResult: (Int, ByteArray?) -> Unit) {
        val to = Runnable { if (pending?.cmd == cmd) { pending = null; onResult(-2, null); pumpNext() } }
        pending = Pending(cmd, onResult, to)
        rbuf = ByteArray(0)
        main.postDelayed(to, timeoutMs)
        writeFrame(ChameleonProto.frame(cmd, data))
    }

    private fun onRx(data: ByteArray) {
        rbuf = if (rbuf.isEmpty()) data else rbuf + data
        while (true) {
            val r = ChameleonProto.parse(rbuf, rbuf.size)
            if (r.consumed > 0) rbuf = rbuf.copyOfRange(r.consumed, rbuf.size)
            val resp = r.resp
            if (resp != null) {
                val p = pending
                if (p != null && resp.cmd == p.cmd) {
                    main.removeCallbacks(p.timeout); pending = null
                    p.onResult(resp.status, resp.data); pumpNext()
                }
            }
            if (r.resp == null && r.consumed == 0) break   // need more bytes
            if (rbuf.isEmpty()) break
        }
        if (rbuf.size > 8192) rbuf = ByteArray(0)
    }

    private fun pumpNext() {
        queue.removeFirstOrNull()?.let { startCommand(it.cmd, it.data, it.timeoutMs, it.onResult) }
    }
    private fun failAll() {
        pending?.let { main.removeCallbacks(it.timeout); runCatching { it.onResult(-1, null) } }; pending = null
        // fail every QUEUED command too — dropping the closures would hang their awaiting coroutines
        val q = queue.toList(); queue.clear()
        q.forEach { runCatching { it.onResult(-1, null) } }
        rbuf = ByteArray(0)
    }

    private fun writeFrame(frame: ByteArray) {
        val g = gatt ?: return; val rc = rxChar ?: return
        val chunk = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < frame.size) {
            val n = minOf(chunk, frame.size - off)
            val part = frame.copyOfRange(off, off + n)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                g.writeCharacteristic(rc, part, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            else { @Suppress("DEPRECATION") run { rc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; rc.value = part; g.writeCharacteristic(rc) } }
            off += n
        }
    }

    // ---- auto-pairing (fixed passkey 123456 on pairing-enabled units) ----
    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            dev ?: return
            val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
            try {
                when (variant) {
                    BluetoothDevice.PAIRING_VARIANT_PIN -> { dev.setPin(PASSKEY.toByteArray()); abortBroadcast() }
                    2 /* PAIRING_VARIANT_PASSKEY_CONFIRMATION */ -> { dev.setPairingConfirmation(true); abortBroadcast() }
                    // passkey ENTRY (variant 1) has no public setter -> the system dialog asks for 123456
                }
            } catch (_: Throwable) {}
        }
    }
    private fun registerPairing() {
        if (pairingRegistered) return
        try {
            val f = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST); f.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                app.registerReceiver(pairingReceiver, f, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(pairingReceiver, f)
            pairingRegistered = true
        } catch (_: Throwable) {}
    }
    private fun unregisterPairing() {
        if (!pairingRegistered) return
        try { app.unregisterReceiver(pairingReceiver) } catch (_: Throwable) {}
        pairingRegistered = false
    }
}
