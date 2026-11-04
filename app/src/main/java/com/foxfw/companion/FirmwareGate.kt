package com.foxfw.companion

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Fox

/**
 * FoxFW-only gate. This companion is built exclusively for FoxFW v2.1+ (the first version carrying
 * the companion IO app + the core firmware work the app relies on), so on connect it verifies:
 *   1. the Flipper runs FoxFW v2.1 or newer  (device_info key `fox_version`, added by FoxFW)
 *   2. the companion IO app is installed      (the fox_android_companion.fap on SD)
 * No FoxFW, or too old -> a blocking dialog pointing at the FoxFW download. FoxFW but no IO app ->
 * a (dismissable) notice that it's needed for most actions.
 */
object FoxGate {
    const val IO_FAP = "/ext/apps/System/fox_android_companion.fap"
    const val FOXFW_URL = "https://github.com/FoxFW"
    private const val MIN_MAJOR = 2
    private const val MIN_MINOR = 1

    data class Result(val isFoxFw: Boolean, val versionOk: Boolean, val version: String?, val ioInstalled: Boolean)

    /** True when a FoxFW version string is >= 2.1 (handles "2.1", "2.1.3", "2.10", "v2.2", …). */
    fun versionAtLeast(v: String?): Boolean {
        if (v == null) return false
        val nums = Regex("""\d+""").findAll(v).map { it.value.toInt() }.toList()
        if (nums.isEmpty()) return false
        val major = nums[0]
        val minor = nums.getOrElse(1) { 0 }
        return major > MIN_MAJOR || (major == MIN_MAJOR && minor >= MIN_MINOR)
    }

    private fun String.normKey() = lowercase().filter { it.isLetterOrDigit() }

    suspend fun check(fle: FlipperBle): Result? {
        // If device_info can't be read (flaky RPC right after connect), DON'T gate — returning a
        // "not FoxFW" result here falsely blocked a real FoxFW Flipper. null => show nothing.
        val info = (FlipperTransport.active ?: fle).deviceInfo() ?: return null
        val foxVer = info.firstOrNull { it.first.normKey() == "foxversion" }?.second
        val io = runCatching { (FlipperTransport.active ?: fle).storageStat(IO_FAP) }.getOrDefault(false)
        return Result(isFoxFw = foxVer != null, versionOk = versionAtLeast(foxVer), version = foxVer, ioInstalled = io)
    }
}

/**
 * Runs the FoxGate check whenever a Flipper connects and shows the appropriate dialog. Drop this once
 * into the app's root Box; it renders nothing until a check fails.
 */
@Composable
fun FoxGateDialogs(fle: FlipperBle) {
    val ctx = LocalContext.current
    var result by remember { mutableStateOf<FoxGate.Result?>(null) }
    var ioDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(fle.conn) {
        result = if (fle.conn == Conn.CONNECTED) {
            ioDismissed = false
            runCatching { FoxGate.check(fle) }.getOrNull()
        } else null
    }

    fun openFoxFw() = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FoxGate.FOXFW_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val r = result ?: return

    if (!r.isFoxFw || !r.versionOk) {
        // Back = escape hatch: close this dialog and drop the link (BLE disconnects; USB has no software
        // disconnect — the cable owns it — so we close the app instead).
        BackHandler(enabled = true) {
            result = null
            if (UsbLink.usbConnected) (ctx as? android.app.Activity)?.finish()
            else if (fle.conn == Conn.CONNECTED) fle.disconnect()
        }
        // Hard gate — no FoxFW (or pre-2.1) = no app.
        AlertDialog(
            onDismissRequest = { /* non-dismissable: this app only works on FoxFW */ },
            title = { Text("FoxFW required", color = Fox.crit) },
            text = {
                Text(
                    if (r.isFoxFw)
                        "This Flipper runs FoxFW ${r.version ?: "?"}, but the companion needs FoxFW v2.1 or newer. Please update FoxFW on your Flipper Zero to continue."
                    else
                        "This companion works only with FoxFW. Please install FoxFW on your Flipper Zero to continue.",
                    color = Fox.fg, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = { TextButton(onClick = { openFoxFw() }) { Text("Get FoxFW", color = Fox.amber) } },
            containerColor = Fox.surface,
        )
    } else if (!r.ioInstalled && !ioDismissed) {
        // FoxFW is fine, but the IO app isn't installed.
        AlertDialog(
            onDismissRequest = { ioDismissed = true },
            title = { Text("Companion app required", color = Fox.amber) },
            text = {
                Text(
                    "The FoxFW companion app isn't installed on this Flipper. It's required for most actions " +
                        "(it runs quietly in the background to bridge the app to your Flipper). Install it from the FoxFW release.",
                    color = Fox.fg, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = { TextButton(onClick = { openFoxFw() }) { Text("Get it", color = Fox.amber) } },
            dismissButton = { TextButton(onClick = { ioDismissed = true }) { Text("Later", color = Fox.muted) } },
            containerColor = Fox.surface,
        )
    }
}
