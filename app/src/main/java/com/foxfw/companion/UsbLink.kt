package com.foxfw.companion

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * USB auto-detect: opens a plugged-in Flipper's CDC VCP as a [FlipperUsb] and registers it with
 * [FlipperTransport] (preferred over BLE while connected). Handles attach/detach/permission.
 * [usbConnected] is observable so the UI can show a USB indicator.
 */
object UsbLink {
    const val ACTION_PERM = "com.foxfw.companion.USB_PERMISSION"
    var usbConnected by mutableStateOf(false)
        private set

    private fun isFlipper(d: UsbDevice) = d.vendorId == 0x0483 && d.productId == 0x5740

    /** Open a Flipper that's already attached + permitted, else request permission. */
    fun scan(ctx: Context) {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        if (FlipperTransport.usb != null) return
        val dev = mgr.deviceList.values.firstOrNull { isFlipper(it) } ?: return
        if (mgr.hasPermission(dev)) {
            open(mgr, dev)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ACTION_PERM).setPackage(ctx.packageName), flags,
            )
            mgr.requestPermission(dev, pi)
        }
    }

    @Volatile private var opening = false

    private fun open(mgr: UsbManager, dev: UsbDevice) {
        if (opening || FlipperTransport.usb != null) return
        opening = true
        val u = FlipperUsb(mgr, dev)
        // open() blocks ~1s entering RPC mode (drains the Flipper CLI banner first), so it must run
        // off the main thread; usbConnected flips true only once the RPC session is actually live.
        Thread {
            try { if (u.open()) usbConnected = true else u.close() } finally { opening = false }
        }.also { it.isDaemon = true }.start()
    }

    private fun detach() {
        (FlipperTransport.usb as? FlipperUsb)?.close()
        usbConnected = false
    }

    /** User-initiated USB disconnect (closes the CDC link). */
    fun disconnect() = detach()

    /** Register attach/detach/permission receivers; caller unregisters the returned receiver. */
    fun register(ctx: Context): BroadcastReceiver {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> scan(c)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        @Suppress("DEPRECATION")
                        val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        if (d == null || isFlipper(d)) detach()
                    }
                    ACTION_PERM -> {
                        val mgr = c.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
                        @Suppress("DEPRECATION")
                        val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        if (d != null && isFlipper(d) &&
                            i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        ) {
                            open(mgr, d)
                        }
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERM)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(rx, f)
        }
        return rx
    }
}
