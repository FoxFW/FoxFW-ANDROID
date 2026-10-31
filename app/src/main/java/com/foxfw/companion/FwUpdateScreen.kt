package com.foxfw.companion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.launch

/**
 * Install a FoxFW firmware update straight from the phone: pick the release .tgz, the app unpacks it,
 * stages it to /ext/update/<pkg>/ over RPC, and calls system_update_request. The firmware runs a full
 * target + CRC integrity check BEFORE it arms anything — nothing flashes unless that passes, and only
 * on the reboot you confirm. No firmware change; works over BLE or (much faster) USB.
 */
private const val UPDATE_ROOT = "/ext/update"

private fun codeMeaning(code: Int): String = when (code) {
    0 -> "OK — validated and ready to flash"
    1 -> "manifest path invalid"
    2 -> "manifest folder not found (upload incomplete?)"
    3 -> "manifest invalid / corrupt"
    4 -> "a stage file is missing (upload incomplete?)"
    5 -> "integrity check failed (corrupt upload) — re-run, prefer USB"
    6 -> "couldn't create the update pointer"
    7 -> "wrong hardware target — this .tgz isn't for this Flipper"
    8 -> "manifest version too old for this firmware"
    9 -> "internal storage full"
    -1 -> "no response (update RPC unsupported? loader busy?)"
    else -> "unspecified error ($code)"
}

@Composable
fun FwUpdateScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val link = FlipperTransport.active
    // reactive: FlipperTransport.active is a plain getter, so poll it or the screen stays stuck on the
    // state it had at open (e.g. "connect first" even after the Flipper links over BLE while open).
    var connected by remember { mutableStateOf(FlipperTransport.active?.connected == true) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { connected = FlipperTransport.active?.connected == true; kotlinx.coroutines.delay(800) }
    }
    val onUsb = UsbLink.usbConnected

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (connected) "Pick a FoxFW .tgz to install." else "Connect the Flipper (Device tab) first.") }
    var progress by remember { mutableStateOf("") }
    var manifestRemote by remember { mutableStateOf<String?>(null) }  // set once prepare returns OK -> enables flash

    fun human(n: Long) = when {
        n >= 1 shl 20 -> "%.1f MB".format(n / 1_048_576.0)
        n >= 1 shl 10 -> "%.0f KB".format(n / 1024.0)
        else -> "$n B"
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val l = link
        if (uri == null || l == null) return@rememberLauncherForActivityResult
        busy = true; manifestRemote = null; status = "Unpacking update…"; progress = ""
        scope.launch {
            val items = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { TarGz.extract(it) }
            }.getOrNull()
            if (items.isNullOrEmpty()) { status = "✗ Couldn't read/unpack that .tgz."; busy = false; return@launch }
            val manifest = items.firstOrNull { it.path.substringAfterLast('/') == "update.fuf" }
            if (manifest == null) { status = "✗ No update.fuf inside — not a FoxFW update bundle."; busy = false; return@launch }
            val total = items.sumOf { it.bytes.size.toLong() }
            status = "Staging ${items.size} files (${human(total)}) to the SD…"

            // make the dirs (shortest paths first), then write every file
            val dirs = items.map { UPDATE_ROOT + "/" + it.path.substringBeforeLast('/') }.toSortedSet(compareBy { it.count { c -> c == '/' } })
            l.storageMkdir(UPDATE_ROOT)
            for (d in dirs) l.storageMkdir(d)
            var done = 0L; var failed = 0
            for (it in items) {
                val remote = "$UPDATE_ROOT/${it.path}"
                progress = "${it.path.substringAfterLast('/')} · ${human(done)}/${human(total)}"
                if (!l.storageWrite(remote, it.bytes)) failed++
                done += it.bytes.size
            }
            progress = ""
            if (failed > 0) { status = "✗ $failed file(s) failed to upload — retry (prefer USB)."; busy = false; return@launch }

            val remoteManifest = "$UPDATE_ROOT/${manifest.path}"
            status = "Validating on the Flipper…"
            val code = l.systemUpdate(remoteManifest)
            if (code == 0) { manifestRemote = remoteManifest; status = "✓ Validated. Ready to flash — tap below." }
            else status = "✗ Update rejected: ${codeMeaning(code)}"
            busy = false
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .border(1.dp, Fox.line, RoundedCornerShape(10.dp)).clickable { onClose() },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, null, tint = Fox.fg, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column { Text("Firmware Update", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Eyebrow("INSTALL A .TGZ FROM THE PHONE") }
        }

        FoxCard { Column {
            Text("Keep the Flipper charged and connected the whole time. The firmware verifies the upload (hardware target + CRC) before it flashes, and only flashes on the reboot you confirm — a bad upload is rejected, not flashed.",
                color = Fox.muted, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(if (onUsb) "Linked over USB — fast upload. 👍" else "Linked over BLE — the upload is several MB and can take many minutes. A USB cable is much faster and safer.",
                color = if (onUsb) Fox.live else Fox.amber, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
        } }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FwBtn(if (busy) "Working…" else "Pick FoxFW .tgz", enabled = connected && !busy) { picker.launch(arrayOf("*/*")) }
            if (manifestRemote != null && !busy)
                FwBtn("⚠ Reboot & flash now", enabled = true) {
                    status = "Rebooting into the updater — the Flipper will flash now, then restart."
                    scope.launch { link?.systemReboot(2); manifestRemote = null }
                }
        }

        if (progress.isNotEmpty()) FoxCard { Text(progress, color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp) }

        FoxCard { Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (!connected) "OFFLINE" else if (onUsb) "USB" else "BLE", if (connected) Fox.live else Fox.faint, Fox.surface2)
            Spacer(Modifier.width(8.dp))
            Text(status, color = if (status.startsWith("✗")) Fox.warn else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
        } }
    }
}

@Composable
private fun FwBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (!enabled) Fox.surface2 else Fox.amber
    val fg = if (!enabled) Fox.faint else Fox.amberInk
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
        .border(1.dp, Fox.line, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled) { onClick() }
        .padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
