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
import java.util.UUID

/** One received offload job: a UID and its same-UID frames, sent by the Flipper. */
class Offload(val uid: Long, val btn: IntArray, val ctrl: IntArray, val hop: LongArray)

enum class Conn { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

/**
 * GATT client for the FoxFW serial-service. The Flipper is the server: it notifies
 * the TX characteristic (Flipper -> phone) and accepts writes on RX (phone -> Flipper).
 * Wire protocol (little-endian):
 *   Flipper -> phone:  BEGIN [0x11][uid:4][n:1]   TRACE [0x12][idx:1][btn:1][ctrl:2][hop:4]   CANCEL [0x14]
 *   phone -> Flipper:  PROGRESS [0x15][states:4][elapsed:2]   RESULT [0x16][ok:1][key:6]
 */
@SuppressLint("MissingPermission")
class FlipperBle(private val app: Context) : FlipperLink {

    override val kind = LinkKind.BLE
    override val connected: Boolean get() = rpcConnected()

    companion object {
        val SVC: UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
        val TX: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e66fe0000") // notify: Flipper->phone (custom crack data)
        val RX: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e65fe0000") // write:  phone->Flipper (custom crack data)
        // Standard Flipper RPC serial channel (used for screen mirror + remote input)
        val RPC_TX: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000") // indicate: Flipper->phone
        val RPC_RX: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000") // write:  phone->Flipper
        val FLOW: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e63fe0000")   // notify: RX buffer credit
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Flipper RPC receive buffer (lib/rpc RPC_BUFFER_SIZE). The flow-control char reports how
        // much of this is free; writing past it overflows the WB stack and drops the link.
        const val RPC_BUFFER = 1024

        const val OP_BEGIN = 0x11
        const val OP_TRACE = 0x12
        const val OP_CANCEL = 0x14
        const val OP_PROGRESS = 0x15
        const val OP_RESULT = 0x16

        // PSA TEA offload (subghz_scene_psa_decrypt.c). Distinct opcodes from the
        // Hitag2 offload above, so both share this custom channel.
        const val PSA_REQUEST = 0x01   // Flipper->phone [0x01][0x00][w0:4][w1:4]
        const val PSA_PROGRESS = 0x02  // phone->Flipper [0x02][tested:4][kps:4]
        const val PSA_RESULT = 0x03    // phone->Flipper [0x03][ok:1][ctr:4][dv0:4][dv1:4][pad:4]
        const val PSA_CANCEL = 0x04    // Flipper->phone [0x04]
    }

    var conn by mutableStateOf(Conn.DISCONNECTED); private set
    var deviceName by mutableStateOf<String?>(null); private set
    var incoming by mutableStateOf<Offload?>(null)   // set when a full offload arrives; cleared by the UI

    private val main = Handler(Looper.getMainLooper())
    private val mgr get() = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = mgr.adapter
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null       // custom crack RX (fe65)
    private var rpcRxChar: BluetoothGattCharacteristic? = null    // standard RPC RX (fe62)

    // CCCD writes must be serialized (Android drops a second while one is pending).
    // Each entry carries its enable value: notifications for fe66, INDICATIONS for fe61 (TX is indicate-only).
    private val cccdQueue = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()

    // Reassembles the length-delimited PB_Main stream on the RPC TX (fe61) notify channel.
    private var rpcBuf = ByteArray(0)

    // Flow control: credit = bytes the Flipper can still receive. Starts full; the fe63 flow
    // characteristic refills it to RPC_BUFFER each time the Flipper's RPC buffer drains.
    private var rpcCredit = RPC_BUFFER
    private var flowWaitSince = 0L

    // Auto-reconnect: ride through transient drops (overflow, range, interference) without the
    // user re-tapping. wantConnected stays true until an explicit disconnect().
    private var wantConnected = false
    private var reconnectAttempts = 0
    private val maxReconnect = 6

    // partial offload assembly
    private var expUid = 0L
    private var expN = 0
    private val btn = IntArray(8); private val ctrl = IntArray(8); private val hop = LongArray(8)
    private var got = 0

    fun connect() {
        wantConnected = true
        reconnectAttempts = 0
        doConnect()
    }

    private fun doConnect() {
        val ad = adapter ?: return
        if (!ad.isEnabled) return
        if (conn == Conn.SCANNING || conn == Conn.CONNECTING || conn == Conn.CONNECTED) return
        // Fast path: if the Flipper is already bonded, connect to it directly. This works even
        // when it isn't advertising (e.g. the phone still holds an ACL link for the battery
        // service), which a name scan would never find.
        val bonded = try { ad.bondedDevices } catch (_: Throwable) { null }
        val known = bonded?.firstOrNull {
            val n = try { it.name } catch (_: Throwable) { null } ?: ""
            n.startsWith("Flipper") || n.contains("Fox", true)
        }
        if (known != null) { connectTo(known, known.name ?: "Flipper"); return }
        conn = Conn.SCANNING
        val scanner = ad.bluetoothLeScanner ?: run { conn = Conn.DISCONNECTED; return }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCb)
        // stop scanning after 12s if nothing found, then retry (device may be waking / out of range)
        main.postDelayed({
            if (conn == Conn.SCANNING) { try { scanner.stopScan(scanCb) } catch (_: Throwable) {}; scheduleReconnect() }
        }, 12_000)
    }

    fun disconnect() {
        wantConnected = false   // user-initiated: stop auto-reconnect
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Throwable) {} // stop a scan in progress
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null; rxChar = null; rpcRxChar = null; conn = Conn.DISCONNECTED; deviceName = null
        cccdQueue.clear(); rpcBuf = ByteArray(0); ScreenState.streaming = false; rpcTxQueue.clear(); rpcPumping = false; rpcCredit = RPC_BUFFER; flowWaitSince = 0L
        failAllPending()
    }

    /** Fail every in-flight RPC so a waiting coroutine returns now instead of blocking its full timeout
     *  (8–60 s) after the link is already gone. */
    private fun failAllPending() {
        if (pendingRpc.isEmpty()) return
        val cbs = pendingRpc.values.toList()
        pendingRpc.clear()
        val fail = Proto.RpcResp(cmdId = -1, status = -1, hasNext = false, readData = null)
        cbs.forEach { runCatching { it.invoke(fail) } }
    }

    /** After an unexpected drop, retry with backoff while the user still wants to be connected. */
    private fun scheduleReconnect() {
        if (!wantConnected || reconnectAttempts >= maxReconnect) { conn = Conn.DISCONNECTED; return }
        reconnectAttempts++
        conn = Conn.CONNECTING   // keep the UI in a "linking" state, not "offline"
        // clear CONNECTING first, or doConnect()'s own guard would bail and no reconnect ever happens
        main.postDelayed({ if (wantConnected && conn != Conn.CONNECTED) { conn = Conn.DISCONNECTED; doConnect() } }, 700L * reconnectAttempts)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = dev.name ?: result.scanRecord?.deviceName ?: return
            if (!name.startsWith("Flipper") && !name.contains("Fox", true)) return
            try { adapter?.bluetoothLeScanner?.stopScan(this) } catch (_: Throwable) {}
            connectTo(dev, name)
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
                reconnectAttempts = 0   // got a link; reset the backoff
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                deviceName = null; rxChar = null; rpcRxChar = null; gatt = null
                cccdQueue.clear(); rpcBuf = ByteArray(0); ScreenState.streaming = false; rpcTxQueue.clear(); rpcPumping = false; rpcCredit = RPC_BUFFER; flowWaitSince = 0L
                try { g.close() } catch (_: Throwable) {}
                failAllPending()      // unblock coroutines awaiting an RPC that will never be answered now
                scheduleReconnect()   // ride through transient drops; no-op after an explicit disconnect()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SVC) ?: run { try { g.disconnect() } catch (_: Throwable) {}; return }
            // Low-latency connection interval (~11-15ms) so remote input and screen frames are snappy.
            try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Throwable) {}
            rxChar = svc.getCharacteristic(RX)
            rpcRxChar = svc.getCharacteristic(RPC_RX)
            cccdQueue.clear()
            // Subscribe to both Flipper->phone channels. fe66 (custom crack) is NOTIFY;
            // fe61 (standard RPC TX) is INDICATE-only, so it needs ENABLE_INDICATION_VALUE.
            val subs = listOf(
                TX to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                RPC_TX to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
                FLOW to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,  // RX buffer credit
            )
            for ((u, enable) in subs) {
                val ch = svc.getCharacteristic(u) ?: continue
                g.setCharacteristicNotification(ch, true)
                ch.getDescriptor(CCCD)?.let { cccdQueue.add(it to enable) }
            }
            if (!writeNextCccd(g)) conn = Conn.CONNECTED  // nothing to subscribe; still usable
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!writeNextCccd(g)) conn = Conn.CONNECTED   // all subscriptions done
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") onData(ch.uuid, ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onData(ch.uuid, value)
        }
    }

    /** Pop and write the next pending CCCD. Returns false when the queue is empty. */
    private fun writeNextCccd(g: BluetoothGatt): Boolean {
        val (cccd, enable) = cccdQueue.removeFirstOrNull() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION") run {
                cccd.value = enable
                g.writeDescriptor(cccd)
            }
        }
        return true
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    // When set (by the Sub-GHz bridge), raw custom-channel (fe66) data is routed here instead of
    // the crack parser. Lets the phone talk to the Fox SubGHz Bridge FAP over the same channel.
    @Volatile var customListener: ((ByteArray) -> Unit)? = null

    private fun onData(uuid: UUID, data: ByteArray) {
        if (uuid == RPC_TX) { onRpcData(data); return }
        if (uuid == FLOW) { onFlowCredit(data); return }
        if (uuid != TX || data.isEmpty()) return
        customListener?.let { it(data); return }
        when (u8(data, 0)) {
            OP_BEGIN -> if (data.size >= 6) {
                expUid = (u8(data,1).toLong()) or (u8(data,2).toLong() shl 8) or
                         (u8(data,3).toLong() shl 16) or (u8(data,4).toLong() shl 24)
                expN = u8(data, 5).coerceIn(0, 8); got = 0
            }
            OP_TRACE -> if (data.size >= 9) {
                val idx = u8(data, 1)
                if (idx in 0..7) {
                    btn[idx] = u8(data, 2)
                    ctrl[idx] = u8(data, 3) or (u8(data, 4) shl 8)
                    hop[idx] = (u8(data,5).toLong()) or (u8(data,6).toLong() shl 8) or
                               (u8(data,7).toLong() shl 16) or (u8(data,8).toLong() shl 24)
                    got++
                    if (got >= expN && expN >= 2) {
                        incoming = Offload(expUid, btn.copyOf(expN), ctrl.copyOf(expN), hop.copyOf(expN))
                    }
                }
            }
            OP_CANCEL -> { incoming = null }
            PSA_REQUEST -> if (data.size >= 10) {
                val w0 = u8(data,2) or (u8(data,3) shl 8) or (u8(data,4) shl 16) or (u8(data,5) shl 24)
                val w1 = u8(data,6) or (u8(data,7) shl 8) or (u8(data,8) shl 16) or (u8(data,9) shl 24)
                main.post { PsaOffload.onRequest(w0, w1) }
            }
            PSA_CANCEL -> main.post { PsaOffload.onCancel() }
        }
    }

    /** PSA offload PROGRESS (phone->Flipper): [0x02][keys_tested:4 LE][keys_per_sec:4 LE]. */
    fun sendPsaProgress(tested: Long, kps: Long) {
        val b = ByteArray(9)
        b[0] = PSA_PROGRESS.toByte()
        val t = tested.toInt(); val k = kps.toInt()
        b[1] = (t and 0xFF).toByte(); b[2] = ((t ushr 8) and 0xFF).toByte()
        b[3] = ((t ushr 16) and 0xFF).toByte(); b[4] = ((t ushr 24) and 0xFF).toByte()
        b[5] = (k and 0xFF).toByte(); b[6] = ((k ushr 8) and 0xFF).toByte()
        b[7] = ((k ushr 16) and 0xFF).toByte(); b[8] = ((k ushr 24) and 0xFF).toByte()
        write(b)
    }

    /** PSA offload RESULT (phone->Flipper): [0x03][ok:1][ctr:4][dv0:4][dv1:4][pad:4]. */
    fun sendPsaResult(ok: Boolean, counter: Int, dv0: Int, dv1: Int) {
        val b = ByteArray(18)
        b[0] = PSA_RESULT.toByte(); b[1] = if (ok) 1 else 0
        fun put(off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte(); b[off+1] = ((v ushr 8) and 0xFF).toByte()
            b[off+2] = ((v ushr 16) and 0xFF).toByte(); b[off+3] = ((v ushr 24) and 0xFF).toByte()
        }
        put(2, counter); put(6, dv0); put(10, dv1); put(14, 0)
        write(b)
    }

    // ---- Standard Flipper RPC: screen mirror + remote input (fe61 notify / fe62 write) ----

    /** Accumulate notify chunks, split the varint-length-delimited PB_Main stream, decode frames. */
    private fun onRpcData(data: ByteArray) {
        rpcBuf = if (rpcBuf.isEmpty()) data else rpcBuf + data
        var off = 0
        while (true) {
            // read a varint length prefix starting at off
            var p = off; var shift = 0; var len = 0L; var haveLen = false
            while (p < rpcBuf.size) {
                val c = rpcBuf[p].toInt() and 0xFF; p++
                len = len or ((c.toLong() and 0x7F) shl shift)
                if (c and 0x80 == 0) { haveLen = true; break }
                shift += 7
                if (shift > 35) { haveLen = false; break }
            }
            if (!haveLen) break                       // length prefix not fully arrived yet
            val msgLen = len.toInt()
            if (msgLen < 0 || msgLen > 8192) { off = p; continue }   // guard against desync
            if (p + msgLen > rpcBuf.size) break       // body not fully arrived yet
            val msg = rpcBuf.copyOfRange(p, p + msgLen)
            off = p + msgLen   // advance PAST this frame before parsing, so a bad frame can't wedge the buffer
            // a malformed frame must not throw out of this Binder callback (would skip the rpcBuf trim
            // below and re-parse the poison frame forever); drop it and keep going.
            try {
                val fb = Proto.parseScreenFrame(msg)
                if (fb != null) main.post { ScreenState.onFrame(fb) }
                else if (pendingRpc.isNotEmpty()) {
                    val resp = Proto.parseResponse(msg)
                    pendingRpc[resp.cmdId]?.invoke(resp)
                }
            } catch (t: Throwable) {
                android.util.Log.w("FlipperBle", "dropped a bad RPC frame", t)
            }
        }
        rpcBuf = if (off >= rpcBuf.size) ByteArray(0) else rpcBuf.copyOfRange(off, rpcBuf.size)
        // keep the leftover buffer bounded if something goes wrong
        if (rpcBuf.size > 16384) rpcBuf = ByteArray(0)
    }

    fun startScreenStream() {
        if (conn != Conn.CONNECTED) return
        ScreenState.streaming = true
        enqueueRpc(Proto.startScreenStream())
    }

    fun stopScreenStream() {
        ScreenState.streaming = false
        if (conn != Conn.CONNECTED) return
        enqueueRpc(Proto.stopScreenStream())
    }

    // Paced FIFO so multi-key sequences (keyboard typing) stay strictly ordered on the wire.
    private val rpcTxQueue = ArrayDeque<ByteArray>()
    private var rpcPumping = false
    private var rpcFailStreak = 0
    // All rpcTxQueue / rpcCredit / rpcPumping access is confined to the main thread (callers may be
    // on IO/GATT threads), so the deque + credit counters never race. Mirrors the fe65 customQueue.
    private fun enqueueRpc(b: ByteArray) {
        main.post {
            rpcTxQueue.addLast(b)
            if (!rpcPumping) { rpcPumping = true; pumpRpc() }
        }
    }
    /** Flipper reported its free RX buffer (big-endian uint32). Refill credit and resume sending. */
    private fun onFlowCredit(data: ByteArray) {
        if (data.size < 4) return
        val credit = (((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
                     ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)).coerceIn(0, RPC_BUFFER)
        main.post {
            rpcCredit = credit
            flowWaitSince = 0L
            if (!rpcPumping && rpcTxQueue.isNotEmpty()) { rpcPumping = true; pumpRpc() }
        }
    }

    private fun pumpRpc() {
        val b = rpcTxQueue.firstOrNull() ?: run { rpcPumping = false; return }   // peek
        // Flow control is batch-style: we may send up to RPC_BUFFER bytes, then must WAIT for the
        // Flipper to drain and refill (it notifies fe63 only once its RX buffer hits 0 and empties).
        // We send while any credit remains — the last packet drives the Flipper's counter to 0,
        // which triggers the refill. Bursts are paced slowly enough that the buffer never backs up,
        // so that last packet always fits (no overflow -> no dropped link).
        if (rpcCredit <= 0) {
            // Sent a full buffer's worth; let the Flipper drain. Because bursts are paced below its
            // processing rate the RX buffer is near-empty, so a short pause clears it. (The Flipper
            // only notifies a refill when idle, which it isn't while streaming, so we self-time.)
            val now = android.os.SystemClock.uptimeMillis()
            if (flowWaitSince == 0L) flowWaitSince = now
            if (now - flowWaitSince > 500) { rpcCredit = RPC_BUFFER; flowWaitSince = 0L }
            else { main.postDelayed(::pumpRpc, 25); return }
        } else {
            flowWaitSince = 0L
        }
        val ok = writeRpc(b)
        if (ok) {
            rpcTxQueue.removeFirstOrNull(); rpcCredit -= b.size; rpcFailStreak = 0
        } else if (++rpcFailStreak > 40) {
            // A single packet that keeps failing (persistent BUSY / lost link) would pump forever;
            // after ~1.2s of retries drop it and move on so the queue can't wedge.
            rpcTxQueue.removeFirstOrNull(); rpcFailStreak = 0
        }
        if (rpcTxQueue.isEmpty()) { rpcPumping = false; return }
        // A lone tap (short queue) drains fast for snappy nav; a typing burst is paced below the
        // Flipper's input+redraw rate so its RX buffer never backs up and the link stays alive.
        val pace = if (!ok) 30L else if (rpcTxQueue.size > 6) 24L else 14L
        main.postDelayed(::pumpRpc, pace)
    }
    private fun enqueueKey(key: Int, long: Boolean) {
        // The Flipper's input routing needs the full press -> action -> release sequence
        // (a lone Short/Long is dropped). Snappiness comes from the high-priority connection
        // interval + the tight pump, not from shortening this.
        enqueueRpc(Proto.sendInput(key, Proto.TYPE_PRESS))
        enqueueRpc(Proto.sendInput(key, if (long) Proto.TYPE_LONG else Proto.TYPE_SHORT))
        enqueueRpc(Proto.sendInput(key, Proto.TYPE_RELEASE))
    }

    /** A remote button tap: press -> short -> release, like a real physical press. */
    fun sendInputTap(key: Int) { if (conn == Conn.CONNECTED) enqueueKey(key, long = false) }

    /** A remote button long-press: press -> long -> release. */
    fun sendInputLong(key: Int) { if (conn == Conn.CONNECTED) enqueueKey(key, long = true) }

    /** Drive the Flipper on-screen keyboard: an ordered path of (key, long) presses. */
    fun sendKeys(keys: List<FlipperKeyboard.Press>) {
        if (conn != Conn.CONNECTED) return
        for (p in keys) enqueueKey(p.key, p.long)
    }

    /** Backspace on a text_input must be a LONG Back (a short Back exits the scene). */
    fun sendBackspace() { if (conn == Conn.CONNECTED) enqueueKey(Proto.KEY_BACK, long = true) }

    /** Returns true if the write was accepted by the stack; false (e.g. BUSY) means retry it. */
    private fun writeRpc(b: ByteArray): Boolean {
        val g = gatt ?: return false; val rc = rpcRxChar ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(rc, b, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") run {
                    rc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    rc.value = b
                    g.writeCharacteristic(rc)
                }
            }
        } catch (_: Throwable) { false }
    }

    fun sendProgress(states: Long, elapsed: Int) {
        val b = ByteArray(7)
        b[0] = OP_PROGRESS.toByte()
        val s = states.toInt()
        b[1] = (s and 0xFF).toByte(); b[2] = ((s ushr 8) and 0xFF).toByte()
        b[3] = ((s ushr 16) and 0xFF).toByte(); b[4] = ((s ushr 24) and 0xFF).toByte()
        b[5] = (elapsed and 0xFF).toByte(); b[6] = ((elapsed ushr 8) and 0xFF).toByte()
        write(b)
    }

    fun sendResult(ok: Boolean, key: ByteArray?) {
        val b = ByteArray(8)
        b[0] = OP_RESULT.toByte(); b[1] = if (ok && key != null) 1 else 0
        if (ok && key != null) System.arraycopy(key, 0, b, 2, minOf(6, key.size))
        write(b)
    }

    // ---- Custom channel (fe65) paced sender for the Sub-GHz bridge ----
    private val customQueue = ArrayDeque<ByteArray>()
    private var customPumping = false
    fun sendCustom(b: ByteArray) {
        main.post { customQueue.addLast(b); if (!customPumping) { customPumping = true; pumpCustom() } }
    }
    fun customConnected() = conn == Conn.CONNECTED && rxChar != null
    fun rpcConnected() = conn == Conn.CONNECTED && rpcRxChar != null
    private fun pumpCustom() {
        val b = customQueue.firstOrNull() ?: run { customPumping = false; return }
        write(b); customQueue.removeFirstOrNull()
        if (customQueue.isEmpty()) { customPumping = false; return }
        main.postDelayed(::pumpCustom, 12L)
    }

    // ---- Storage RPC: write/read/mkdir/delete files on the Flipper SD (BLE or USB transport).
    // Ported from the FoxFW-ANDROID companion (ProtobufCodec storage requests + chunked write). ----
    private val pendingRpc = java.util.concurrent.ConcurrentHashMap<Int, (Proto.RpcResp) -> Unit>()

    /** Launch a Flipper app (or a .fap by path) with args, over RPC. */
    override suspend fun appStart(name: String, args: String): Boolean =
        rpcCall(12000) { id -> Proto.appStart(id, name, args) }?.status == 0

    /** Back out up to 4 menu/app levels to the desktop so the loader is free for appStart. */
    override suspend fun returnToDesktop() {
        if (conn != Conn.CONNECTED) return
        repeat(4) { sendInputTap(Proto.KEY_BACK); kotlinx.coroutines.delay(130) }
        kotlinx.coroutines.delay(250) // let the queued input drain + the UI settle
    }

    override suspend fun storageMkdir(path: String): Boolean = rpcCall(10000) { id -> Proto.mkdirRequest(id, path) }?.status == 0
    override suspend fun storageDelete(path: String, recursive: Boolean): Boolean =
        rpcCall(10000) { id -> Proto.deleteRequest(id, path, recursive) } != null
    override suspend fun storageRename(oldPath: String, newPath: String): Boolean =
        rpcCall(10000) { id -> Proto.renameRequest(id, oldPath, newPath) }?.status == 0
    override suspend fun storageBackupCreate(archivePath: String): Boolean =
        rpcCall(60000) { id -> Proto.backupCreateRequest(id, archivePath) }?.status == 0
    override suspend fun storageBackupRestore(archivePath: String): Boolean =
        rpcCall(60000) { id -> Proto.backupRestoreRequest(id, archivePath) }?.status == 0
    override suspend fun systemUpdate(manifestPath: String): Int {
        val r = rpcCall(30000) { id -> Proto.updateRequest(id, manifestPath) } ?: return -1
        return r.contentBytes?.takeIf { r.contentField == 46 }?.let { Proto.parseUpdateCode(it) }
            ?: if (r.status == 0) 0 else 10
    }
    override suspend fun systemReboot(mode: Int) { reboot(mode) }

    override suspend fun storageWrite(path: String, data: ByteArray): Boolean {
        val id = Proto.allocId()
        val done = kotlinx.coroutines.CompletableDeferred<Proto.RpcResp>()
        pendingRpc[id] = { r -> if (!r.hasNext) { pendingRpc.remove(id); done.complete(r) } }
        return try {
            // Each Write frame = chunk + path + protobuf framing (~55 B) and must fit one GATT write
            // (ATT_MTU 247 → ~244 usable); 448 overflowed → big files (e.g. a 12 KB FAP) failed. 128 is safe.
            var off = 0; val chunk = 128
            if (data.isEmpty()) enqueueRpc(Proto.writeRequest(id, path, ByteArray(0), false))
            while (off < data.size) {
                val end = minOf(off + chunk, data.size)
                enqueueRpc(Proto.writeRequest(id, path, data.copyOfRange(off, end), end < data.size))
                off = end
            }
            // Scale the ceiling with size: ~128-B frames paced 14–30 ms + periodic flow-control
            // pauses ≈ a few KB/s, so a fixed 30 s silently failed big writes. Completes early on
            // the real response; this is only an upper bound (capped so a stall can't hang forever).
            val timeoutMs = (30000L + (data.size.toLong() / 1024L) * 2000L).coerceAtMost(300000L)
            val r = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { done.await() }
            r != null && r.status == 0
        } finally { pendingRpc.remove(id) }
    }

    override suspend fun storageRead(path: String): ByteArray? {
        val id = Proto.allocId()
        val buf = java.io.ByteArrayOutputStream()
        val done = kotlinx.coroutines.CompletableDeferred<Proto.RpcResp>()
        pendingRpc[id] = { r -> r.readData?.let { buf.write(it) }; if (!r.hasNext) { pendingRpc.remove(id); done.complete(r) } }
        return try {
            enqueueRpc(Proto.readRequest(id, path))
            val r = kotlinx.coroutines.withTimeoutOrNull(15000) { done.await() }
            if (r != null && r.status == 0) buf.toByteArray() else null
        } finally { pendingRpc.remove(id) }
    }

    private suspend fun rpcCall(timeoutMs: Long, build: (Int) -> ByteArray): Proto.RpcResp? {
        val id = Proto.allocId()
        val done = kotlinx.coroutines.CompletableDeferred<Proto.RpcResp>()
        pendingRpc[id] = { r -> if (!r.hasNext) { pendingRpc.remove(id); done.complete(r) } }
        return try { enqueueRpc(build(id)); kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { done.await() } }
        finally { pendingRpc.remove(id) }
    }

    /** Accumulate every response for one command id until has_next=false (streamed k/v). */
    private suspend fun rpcCallStream(timeoutMs: Long, build: (Int) -> ByteArray): List<Proto.RpcResp>? {
        val id = Proto.allocId()
        val acc = java.util.Collections.synchronizedList(ArrayList<Proto.RpcResp>())
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        pendingRpc[id] = { r -> acc.add(r); if (!r.hasNext) { pendingRpc.remove(id); done.complete(Unit) } }
        return try {
            enqueueRpc(build(id))
            if (kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { done.await() } == null) null
            else ArrayList(acc)
        } finally { pendingRpc.remove(id) }
    }

    // ---- stat (existence check) ----
    suspend fun storageStat(path: String): Boolean = rpcCall(8000) { id -> Proto.statRequest(id, path) }?.status == 0

    /** List a directory on the Flipper SD. Null = error (bad path / disconnected); empty list = empty dir.
     *  Dirs sorted first, then files, both A→Z. */
    override suspend fun storageList(path: String): List<Proto.FileEntry>? {
        val rs = rpcCallStream(15000) { id -> Proto.listRequest(id, path) } ?: return null
        if (rs.isNotEmpty() && rs.last().status != 0) return null
        return rs.flatMap { it.files ?: emptyList() }
            .sortedWith(compareByDescending<Proto.FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    // ---- System RPC (Settings panel) ----
    private suspend fun kvInfo(field: Int, build: (Int) -> ByteArray): List<Pair<String, String>>? {
        val rs = rpcCallStream(12000, build) ?: return null
        return rs.mapNotNull { r -> r.contentBytes?.takeIf { r.contentField == field }?.let { Proto.parseKeyValue(it) } }
    }
    suspend fun deviceInfo(): List<Pair<String, String>>? = kvInfo(33) { id -> Proto.deviceInfoRequest(id) }
    suspend fun powerInfo(): List<Pair<String, String>>? = kvInfo(45) { id -> Proto.powerInfoRequest(id) }
    suspend fun getDateTime(): Proto.DateTime? {
        val r = rpcCall(10000) { id -> Proto.getDateTimeRequest(id) } ?: return null
        return r.contentBytes?.takeIf { r.contentField == 36 }?.let { Proto.parseDateTime(it) }
    }
    suspend fun setDateTime(cal: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val weekday = if (dow == java.util.Calendar.SUNDAY) 7 else dow - 1   // Flipper: 1=Mon..7=Sun
        return rpcCall(10000) { id ->
            Proto.setDateTimeRequest(id, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND), cal.get(java.util.Calendar.DAY_OF_MONTH), month,
                cal.get(java.util.Calendar.YEAR), weekday)
        }?.status == 0
    }
    suspend fun playAlert(): Boolean = rpcCall(8000) { id -> Proto.playAlertRequest(id) }?.status == 0
    /** Fire-and-forget: the BLE link drops as the device reboots/wipes. */
    fun reboot(mode: Int) { enqueueRpc(Proto.rebootRequest(Proto.allocId(), mode)) }
    fun factoryReset() { enqueueRpc(Proto.factoryResetRequest(Proto.allocId())) }

    private fun write(b: ByteArray) {
        val g = gatt ?: return; val rc = rxChar ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(rc, b, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION") run {
                rc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                rc.value = b
                g.writeCharacteristic(rc)
            }
        }
    }
}
