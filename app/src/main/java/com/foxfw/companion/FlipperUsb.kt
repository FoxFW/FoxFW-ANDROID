package com.foxfw.companion

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * CDC-ACM link to a Flipper over USB. Implements the same [FlipperLink] RPC surface as
 * [FlipperBle], so FlipperRf works identically over a cable. Opens the virtual COM port, sends
 * `start_rpc_session`, then exchanges the same varint-delimited PB_Main stream (shared
 * [RpcReassembler] + [Proto]). Device-verified at V1.
 */
class FlipperUsb(private val mgr: UsbManager, private val device: UsbDevice) : FlipperLink {
    override val kind = LinkKind.USB

    @Volatile private var conn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    @Volatile private var running = false
    private var reader: Thread? = null

    private val pending = ConcurrentHashMap<Int, (Proto.RpcResp) -> Unit>()
    private val reasm = RpcReassembler { msg ->
        val r = Proto.parseResponse(msg)
        pending[r.cmdId]?.invoke(r)
    }

    override val connected: Boolean get() = running && conn != null

    /** Open the CDC port + start the RPC session. Returns false if the device isn't a usable CDC. */
    fun open(): Boolean {
        val c = mgr.openDevice(device) ?: return false
        var comm: UsbInterface? = null
        var data: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            when (itf.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> comm = itf
                UsbConstants.USB_CLASS_CDC_DATA -> data = itf
            }
        }
        val d = data ?: run { c.close(); return false }
        c.claimInterface(d, true)
        comm?.let { c.claimInterface(it, true) }
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (e in 0 until d.endpointCount) {
            val ep = d.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
        }
        if (inEp == null || outEp == null) { c.close(); return false }
        // CDC: raise DTR|RTS, set 115200 8N1 (baud is cosmetic for a VCP but some stacks require it).
        val ci = comm?.id ?: 0
        c.controlTransfer(0x21, 0x22, 0x03, ci, null, 0, 200)
        val lineCoding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)
        c.controlTransfer(0x21, 0x20, 0, ci, lineCoding, lineCoding.size, 200)

        conn = c; epIn = inEp; epOut = outEp
        // The CDC endpoint is the Flipper CLI (text), not the RPC channel. Switch it into RPC mode,
        // then DRAIN the CLI banner + command echo + prompt before the protobuf reader starts — those
        // text bytes would otherwise be mis-parsed as varint message lengths and swallow every RPC
        // response (unlike BLE, which has a dedicated RPC characteristic with no CLI in front).
        Thread.sleep(60)            // let DTR settle so the CLI is listening
        drainInput(250)             // consume any connect banner/prompt already sitting in the buffer
        writeRaw("start_rpc_session\r".toByteArray())
        drainInput(600)             // consume the echoed command + the mode-switch, leaving a clean stream
        reasm.reset()
        running = true
        reader = Thread { readLoop() }.also { it.isDaemon = true; it.start() }
        FlipperTransport.usb = this
        return true
    }

    /** Read and discard CDC input until it goes quiet (~180ms idle) or [maxMs] elapses. Used to clear
     *  the CLI text that precedes RPC mode so the protobuf stream starts on a frame boundary. */
    private fun drainInput(maxMs: Int) {
        val c = conn ?: return
        val ep = epIn ?: return
        val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(64))
        val start = System.currentTimeMillis()
        var lastData = start
        while (System.currentTimeMillis() - start < maxMs) {
            val n = try { c.bulkTransfer(ep, buf, buf.size, 60) } catch (_: Throwable) { -1 }
            val now = System.currentTimeMillis()
            if (n > 0) lastData = now else if (now - lastData > 180) break
        }
    }

    fun close() {
        running = false
        try { reader?.interrupt() } catch (_: Throwable) {}
        try { conn?.close() } catch (_: Throwable) {}
        conn = null
        pending.clear()
        if (FlipperTransport.usb === this) FlipperTransport.usb = null
    }

    private fun readLoop() {
        val c = conn ?: return
        val ep = epIn ?: return
        val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(64))
        while (running) {
            val n = try { c.bulkTransfer(ep, buf, buf.size, 200) } catch (_: Throwable) { -1 }
            // a malformed frame must not throw out of this plain Thread (no uncaught handler = app dies)
            if (n > 0) try { reasm.feed(buf.copyOf(n)) } catch (t: Throwable) {
                android.util.Log.w("FlipperUsb", "dropped a bad frame", t)
            }
        }
    }

    private fun writeRaw(b: ByteArray) {
        val c = conn ?: return
        val ep = epOut ?: return
        var off = 0
        while (off < b.size) {
            val end = minOf(off + 4096, b.size)
            val chunk = if (off == 0 && end == b.size) b else b.copyOfRange(off, end)
            if (c.bulkTransfer(ep, chunk, chunk.size, 1500) < 0) return
            off = end
        }
    }

    /** writeRaw off the caller's thread — the bulkTransfer loop blocks up to 1.5 s/chunk and callers
     *  (e.g. FwUpdate) invoke from the Main dispatcher, which would ANR on a multi-MB transfer. */
    private suspend fun send(b: ByteArray) = withContext(Dispatchers.IO) { writeRaw(b) }

    private suspend fun rpcCall(timeoutMs: Long, build: (Int) -> ByteArray): Proto.RpcResp? {
        val id = Proto.allocId()
        val done = CompletableDeferred<Proto.RpcResp>()
        pending[id] = { r -> if (!r.hasNext) { pending.remove(id); done.complete(r) } }
        return try {
            send(build(id))
            withTimeoutOrNull(timeoutMs) { done.await() }
        } finally { pending.remove(id) }
    }

    override suspend fun appStart(name: String, args: String): Boolean =
        rpcCall(12000) { id -> Proto.appStart(id, name, args) }?.status == 0

    /** Like [rpcCall] but accumulates every response in a hasNext stream (for KV info replies). */
    private suspend fun rpcCallStream(timeoutMs: Long, build: (Int) -> ByteArray): List<Proto.RpcResp>? {
        val id = Proto.allocId()
        val acc = java.util.Collections.synchronizedList(ArrayList<Proto.RpcResp>())
        val done = CompletableDeferred<Unit>()
        pending[id] = { r -> acc.add(r); if (!r.hasNext) { pending.remove(id); done.complete(Unit) } }
        return try {
            send(build(id))
            if (withTimeoutOrNull(timeoutMs) { done.await() } == null) null else ArrayList(acc)
        } finally { pending.remove(id) }
    }

    override suspend fun storageStat(path: String): Boolean =
        rpcCall(8000) { id -> Proto.statRequest(id, path) }?.status == 0

    override suspend fun deviceInfo(): List<Pair<String, String>>? {
        val rs = rpcCallStream(12000) { id -> Proto.deviceInfoRequest(id) } ?: return null
        return rs.mapNotNull { r -> r.contentBytes?.takeIf { r.contentField == 33 }?.let { Proto.parseKeyValue(it) } }
    }

    override suspend fun powerInfo(): List<Pair<String, String>>? {
        val rs = rpcCallStream(12000) { id -> Proto.powerInfoRequest(id) } ?: return null
        return rs.mapNotNull { r -> r.contentBytes?.takeIf { r.contentField == 45 }?.let { Proto.parseKeyValue(it) } }
    }

    override suspend fun getDateTime(): Proto.DateTime? {
        val r = rpcCall(10000) { id -> Proto.getDateTimeRequest(id) } ?: return null
        return r.contentBytes?.takeIf { r.contentField == 36 }?.let { Proto.parseDateTime(it) }
    }

    override suspend fun storageMkdir(path: String): Boolean =
        rpcCall(10000) { id -> Proto.mkdirRequest(id, path) }?.status == 0

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
    override suspend fun systemReboot(mode: Int) { send(Proto.rebootRequest(Proto.allocId(), mode)) }

    override suspend fun storageWrite(path: String, data: ByteArray, onProgress: ((Long, Long) -> Unit)?): Boolean {
        val id = Proto.allocId()
        val done = CompletableDeferred<Proto.RpcResp>()
        pending[id] = { r -> if (!r.hasNext) { pending.remove(id); done.complete(r) } }
        return try {
            withContext(Dispatchers.IO) {
                var off = 0; val chunk = 2048
                if (data.isEmpty()) writeRaw(Proto.writeRequest(id, path, ByteArray(0), false))
                while (off < data.size) {
                    val end = minOf(off + chunk, data.size)
                    writeRaw(Proto.writeRequest(id, path, data.copyOfRange(off, end), end < data.size))
                    off = end
                    onProgress?.invoke(off.toLong(), data.size.toLong())
                }
            }
            val r = withTimeoutOrNull(30000) { done.await() }
            r != null && r.status == 0
        } finally { pending.remove(id) }
    }

    override suspend fun storageRead(path: String): ByteArray? {
        val id = Proto.allocId()
        val buf = ByteArrayOutputStream()
        val done = CompletableDeferred<Proto.RpcResp>()
        pending[id] = { r -> r.readData?.let { buf.write(it) }; if (!r.hasNext) { pending.remove(id); done.complete(r) } }
        return try {
            send(Proto.readRequest(id, path))
            val r = withTimeoutOrNull(15000) { done.await() }
            if (r != null && r.status == 0) buf.toByteArray() else null
        } finally { pending.remove(id) }
    }

    override suspend fun storageList(path: String): List<Proto.FileEntry>? {
        val id = Proto.allocId()
        val acc = ArrayList<Proto.FileEntry>()
        val done = CompletableDeferred<Proto.RpcResp>()
        pending[id] = { r -> r.files?.let { acc.addAll(it) }; if (!r.hasNext) { pending.remove(id); done.complete(r) } }
        return try {
            send(Proto.listRequest(id, path))
            val r = withTimeoutOrNull(15000) { done.await() }
            if (r != null && r.status == 0) acc else null
        } finally { pending.remove(id) }
    }
}
