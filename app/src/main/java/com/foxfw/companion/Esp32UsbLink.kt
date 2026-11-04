package com.foxfw.companion

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * USB-OTG transport to a Esp32 board (BFFB v2 / ESP32-C5) plugged straight into the phone. Speaks
 * USB CDC-ACM — the class the C5's native USB-Serial/JTAG enumerates as — at 115200 8N1, DTR+RTS up,
 * the same line settings the board answered on over the Flipper bridge. Incoming bytes are split into
 * lines and pushed to [onLine]; [send] appends a newline. Silicon Labs CP210x bridges (the BFFB v2's
 * chip) are driven via their vendor control protocol; other bridge chips (CH34x) aren't yet — see [canDrive].
 */
class Esp32UsbLink(private val appCtx: Context) : Esp32Link {

    companion object {
        private const val TAG = "Esp32Usb"
        private const val ACTION_PERM = "com.foxfw.companion.USB_PERMISSION"

        /** Espressif native USB-Serial/JTAG + the usual ESP devkit bridge vendors. */
        val ESP_VIDS = setOf(0x303A, 0x10C4, 0x1A86, 0x0403)

        private val mgr get() = { c: Context -> c.getSystemService(Context.USB_SERVICE) as UsbManager }

        /** The attached board, preferring one that exposes a CDC-DATA interface we can drive. */
        fun find(ctx: Context): UsbDevice? {
            val m = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val all = m.deviceList.values
            return all.firstOrNull { cdcDataInterface(it) != null }
                ?: all.firstOrNull { it.vendorId in ESP_VIDS }
        }

        private const val VID_SILABS = 0x10C4   // CP210x (the BFFB v2's USB-UART bridge)

        /** True when we can open this device: CDC-ACM, or a Silicon Labs CP210x bridge. */
        fun canDrive(d: UsbDevice): Boolean = cdcDataInterface(d) != null || d.vendorId == VID_SILABS

        private fun cdcDataInterface(d: UsbDevice): UsbInterface? {
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) return intf
            }
            return null
        }

        private fun bulkEndpoints(intf: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
            var din: UsbEndpoint? = null; var dout: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val e = intf.getEndpoint(i)
                if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (e.direction == UsbConstants.USB_DIR_IN) din = e else dout = e
            }
            return din to dout
        }
    }

    @Volatile private var conn: UsbDeviceConnection? = null
    @Volatile private var epIn: UsbEndpoint? = null
    @Volatile private var epOut: UsbEndpoint? = null
    private val running = AtomicBoolean(false)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lineCb: ((String) -> Unit)? = null

    override val connected: Boolean get() = conn != null && running.get()

    override fun onLine(cb: (String) -> Unit) { lineCb = cb }

    /** Ask for USB permission if needed; suspend until the user answers. */
    suspend fun ensurePermission(device: UsbDevice): Boolean {
        val m = appCtx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (m.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action != ACTION_PERM) return
                    appCtx.unregisterReceiver(this)
                    val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(ok)
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(appCtx, 0, Intent(ACTION_PERM).setPackage(appCtx.packageName), flags)
            val filter = IntentFilter(ACTION_PERM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else
                @Suppress("UnspecifiedRegisterReceiverFlag") appCtx.registerReceiver(receiver, filter)
            cont.invokeOnCancellation { runCatching { appCtx.unregisterReceiver(receiver) } }
            m.requestPermission(device, pi)
        }
    }

    /** Open [device] (CDC-ACM or CP210x), configure 115200 8N1 + DTR/RTS, start the reader. */
    suspend fun connect(device: UsbDevice): String = withContext(Dispatchers.IO) {
        val m = appCtx.getSystemService(Context.USB_SERVICE) as UsbManager
        val c = m.openDevice(device) ?: return@withContext "Couldn't open the USB device."
        val dataIf = cdcDataInterface(device)
        val eps: Pair<UsbEndpoint, UsbEndpoint> = if (dataIf != null) {
            // ---- CDC-ACM (Flipper, ESP32 native USB) ----
            var commIf: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_COMM) { commIf = intf; break }
            }
            commIf?.let { c.claimInterface(it, true) }
            if (!c.claimInterface(dataIf, true)) { c.close(); return@withContext "Couldn't claim the serial interface." }
            val (din, dout) = bulkEndpoints(dataIf)
            if (din == null || dout == null) { c.close(); return@withContext "No bulk endpoints on the serial interface." }
            val ctrlIf = commIf ?: dataIf
            val coding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0, 0, 8)  // 115200 8N1
            c.controlTransfer(0x21, 0x20, 0, ctrlIf.id, coding, coding.size, 500)   // CDC SET_LINE_CODING
            c.controlTransfer(0x21, 0x22, 0x03, ctrlIf.id, null, 0, 500)            // SET_CONTROL_LINE_STATE DTR|RTS
            din to dout
        } else if (device.vendorId == VID_SILABS) {
            // ---- Silicon Labs CP210x (BFFB v2) — vendor control protocol ----
            val intf = device.getInterface(0)
            if (!c.claimInterface(intf, true)) { c.close(); return@withContext "Couldn't claim the CP210x interface." }
            val (din, dout) = bulkEndpoints(intf)
            if (din == null || dout == null) { c.close(); return@withContext "No bulk endpoints on the CP210x." }
            // host->device, vendor, interface recipient (0x41). 115200 8N1, DTR+RTS.
            c.controlTransfer(0x41, 0x00, 0x0001, intf.id, null, 0, 500)                        // IFC_ENABLE
            c.controlTransfer(0x41, 0x1E, 0, intf.id, byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00), 4, 500) // SET_BAUDRATE 115200
            c.controlTransfer(0x41, 0x03, 0x0800, intf.id, null, 0, 500)                        // SET_LINE_CTL 8N1
            c.controlTransfer(0x41, 0x07, 0x0303, intf.id, null, 0, 500)                        // SET_MHS DTR+RTS
            din to dout
        } else {
            c.close(); return@withContext "That USB device isn't a serial port we can drive (unknown bridge chip)."
        }

        conn = c; epIn = eps.first; epOut = eps.second; running.set(true)
        startReader()
        "Connected to ${device.productName ?: "Esp32"} over USB."
    }

    private fun startReader() {
        io.launch {
            val c = conn ?: return@launch
            val ep = epIn ?: return@launch
            val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(64))
            val sb = StringBuilder()
            while (running.get()) {
                val n = try { c.bulkTransfer(ep, buf, buf.size, 200) } catch (t: Throwable) { -1 }
                if (n > 0) {
                    val chunk = String(buf, 0, n, Charsets.UTF_8)
                    for (ch in chunk) {
                        if (ch == '\n') { emit(sb.toString()); sb.setLength(0) }
                        else if (ch != '\r') sb.append(ch)
                    }
                }
            }
        }
    }

    private fun emit(line: String) {
        val l = line.trimEnd()
        if (l.isNotEmpty()) lineCb?.invoke(l)
    }

    override suspend fun send(command: String) {
        val c = conn ?: return
        val ep = epOut ?: return
        val bytes = (command + "\n").toByteArray(Charsets.UTF_8)
        withContext(Dispatchers.IO) { c.bulkTransfer(ep, bytes, bytes.size, 1000) }
    }

    override suspend fun disconnect() {
        running.set(false)
        withContext(Dispatchers.IO) { runCatching { conn?.close() } }
        conn = null; epIn = null; epOut = null
    }
}
