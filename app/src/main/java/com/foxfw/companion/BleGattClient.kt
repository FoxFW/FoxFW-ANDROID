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
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import java.util.UUID

/**
 * A generic BLE central for recon: scan every advertiser, connect to one, discover its whole GATT
 * tree, and read / subscribe / write any characteristic. Standalone — not tied to a known device.
 * One GATT op in flight at a time (Android requires it); notifications bypass the queue.
 */
@SuppressLint("MissingPermission")
class BleGattClient(private val app: Context) {

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    /** A characteristic node with live, observable value + subscription state. */
    class CharUi(val uuid: UUID, val props: Int) {
        var value by mutableStateOf<ByteArray?>(null)
        var notifying by mutableStateOf(false)
    }
    class SvcUi(val uuid: UUID, val chars: List<CharUi>)
    data class Found(val address: String, val name: String?, val rssi: Int)

    var conn by mutableStateOf(Conn.DISCONNECTED); private set
    var scanning by mutableStateOf(false); private set
    var connectedName by mutableStateOf<String?>(null); private set
    var connectedAddr by mutableStateOf<String?>(null); private set
    var services by mutableStateOf<List<SvcUi>>(emptyList()); private set
    var status by mutableStateOf<String?>(null); private set
    val devices: SnapshotStateList<Found> = mutableStateListOf()

    private val main = Handler(Looper.getMainLooper())
    private val mgr get() = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = mgr.adapter
    private var gatt: BluetoothGatt? = null
    private val charRefs = HashMap<Pair<UUID, UUID>, BluetoothGattCharacteristic>()
    private val uiMap = HashMap<Pair<UUID, UUID>, CharUi>()

    // ---- GATT op serializer: one read/write/descriptor-write outstanding at a time ----
    private val ops = ArrayDeque<() -> Unit>()
    private var busy = false
    private fun enqueue(op: () -> Unit) { main.post { ops.addLast(op); pump() } }
    private fun pump() {
        if (busy) return
        val op = ops.removeFirstOrNull() ?: return
        busy = true
        try { op() } catch (_: Throwable) { busy = false; pump() }
    }
    private fun opDone() { main.post { busy = false; pump() } }

    // ---- scanning ----
    fun startScan() {
        val ad = adapter ?: run { status = "No Bluetooth adapter."; return }
        if (!ad.isEnabled) { status = "Turn Bluetooth on."; return }
        val scanner = ad.bluetoothLeScanner ?: run { status = "Scanner unavailable."; return }
        if (scanning) return
        devices.clear(); scanning = true; status = "Scanning…"
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        main.postDelayed({ if (scanning) stopScan() }, 20_000)
    }

    fun stopScan() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Throwable) {}
        scanning = false
        if (status == "Scanning…") status = "Found ${devices.size} device(s)."
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = try { dev.name } catch (_: Throwable) { null } ?: result.scanRecord?.deviceName
            main.post {
                val i = devices.indexOfFirst { it.address == dev.address }
                val f = Found(dev.address, name, result.rssi)
                if (i >= 0) devices[i] = f else devices.add(f)
                devices.sortByDescending { it.rssi }
            }
        }
        override fun onScanFailed(errorCode: Int) { main.post { scanning = false; status = "Scan failed ($errorCode)." } }
    }

    // ---- connection ----
    fun connect(address: String) {
        val ad = adapter ?: return
        stopScan()
        val dev = try { ad.getRemoteDevice(address) } catch (_: Throwable) { null } ?: return
        conn = Conn.CONNECTING; connectedAddr = address
        connectedName = try { dev.name } catch (_: Throwable) { null } ?: devices.firstOrNull { it.address == address }?.name
        status = "Connecting…"
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            dev.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else dev.connectGatt(app, false, gattCb)
    }

    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null; conn = Conn.DISCONNECTED; connectedName = null; connectedAddr = null
        services = emptyList(); charRefs.clear(); uiMap.clear(); ops.clear(); busy = false
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { conn = Conn.CONNECTED; this@BleGattClient.status = "Discovering services…" }
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post {
                    conn = Conn.DISCONNECTED; connectedName = null; connectedAddr = null
                    services = emptyList(); charRefs.clear(); uiMap.clear(); ops.clear(); busy = false
                    this@BleGattClient.status = "Disconnected."
                }
                try { g.close() } catch (_: Throwable) {}
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, m: Int, s: Int) { g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            val built = ArrayList<SvcUi>()
            charRefs.clear(); uiMap.clear()
            for (svc in g.services) {
                val cs = ArrayList<CharUi>()
                for (ch in svc.characteristics) {
                    val ui = CharUi(ch.uuid, ch.properties)
                    cs.add(ui)
                    charRefs[svc.uuid to ch.uuid] = ch
                    uiMap[svc.uuid to ch.uuid] = ui
                }
                built.add(SvcUi(svc.uuid, cs))
            }
            main.post { services = built; status = "${built.size} services · ${charRefs.size} characteristics" }
        }

        // read — new + legacy signatures
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            deliver(findSvcFor(ch), ch.uuid, value); opDone()
        }
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") deliver(findSvcFor(ch), ch.uuid, ch.value); opDone()
        }

        // notifications — new + legacy signatures (do NOT consume the op queue)
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(findSvcFor(ch), ch.uuid, value)
        }
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") deliver(findSvcFor(ch), ch.uuid, ch.value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post { this@BleGattClient.status = if (status == BluetoothGatt.GATT_SUCCESS) "Write OK" else "Write failed ($status)" }
            opDone()
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { opDone() }
    }

    private fun findSvcFor(ch: BluetoothGattCharacteristic): UUID? = ch.service?.uuid

    private fun deliver(svc: UUID?, charUuid: UUID, value: ByteArray?) {
        if (svc == null || value == null) return
        val ui = uiMap[svc to charUuid] ?: return
        main.post { ui.value = value }
    }

    // ---- per-characteristic actions ----
    fun read(svc: UUID, charUuid: UUID) {
        val g = gatt ?: return; val ch = charRefs[svc to charUuid] ?: return
        enqueue { if (g.readCharacteristic(ch) != true) opDone() }
    }

    fun toggleNotify(svc: UUID, charUuid: UUID) {
        val g = gatt ?: return; val ch = charRefs[svc to charUuid] ?: return
        val ui = uiMap[svc to charUuid] ?: return
        val enable = !ui.notifying
        val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        enqueue {
            g.setCharacteristicNotification(ch, enable)
            val cccd = ch.getDescriptor(CCCD)
            if (cccd == null) { main.post { ui.notifying = enable }; opDone(); return@enqueue }
            val v = if (!enable) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            else if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, v) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") run { cccd.value = v; g.writeDescriptor(cccd) }
            }
            main.post { ui.notifying = enable }
            if (!ok) opDone()
        }
    }

    fun write(svc: UUID, charUuid: UUID, data: ByteArray, noResponse: Boolean = false) {
        val g = gatt ?: return; val ch = charRefs[svc to charUuid] ?: return
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        enqueue {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, data, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = data; g.writeCharacteristic(ch) }
            }
            if (!ok) opDone()
        }
    }
}
