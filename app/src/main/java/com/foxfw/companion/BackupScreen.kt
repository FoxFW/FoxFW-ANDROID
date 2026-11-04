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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup & Restore over existing RPC (no firmware changes):
 *  - SD data: recursively pull /ext (your captures) to the phone.
 *  - Device: native backup_create/restore of /int (pairing, settings, dolphin) as a .tar, pulled/pushed.
 */
private const val SD_MAX_FILE = 4L * 1024 * 1024  // skip files >4MB in the bulk pull (grab in File Manager)
private const val DEV_TMP = "/ext/.foxfw_devbackup.tar"

@Composable
fun BackupScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val ble = FoxApplication.instance.ble
    val connected = ble.conn == Conn.CONNECTED

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (connected) "Ready." else "Connect the Flipper (Device tab) first.") }
    var progress by remember { mutableStateOf("") }
    var stopReq by remember { mutableStateOf(false) }

    fun ts() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    fun human(n: Long) = when {
        n >= 1 shl 20 -> "%.1f MB".format(n / 1_048_576.0)
        n >= 1 shl 10 -> "%.1f KB".format(n / 1024.0)
        else -> "$n B"
    }

    fun backupSd() {
        if (!connected || busy) { if (!connected) status = "Connect the Flipper first."; return }
        busy = true; stopReq = false; status = "Backing up SD…"
        scope.launch {
            val local = File(ctx.getExternalFilesDir(null), "sd_backup/${ts()}")
            val r = BackupBackend.backupSd(ble, "/ext", local, SD_MAX_FILE, { stopReq }) { files, bytes, cur ->
                progress = "$files files · ${human(bytes)} · $cur"
            }
            progress = ""
            status = buildString {
                append(if (stopReq) "■ Stopped. " else "✓ SD backup done. ")
                append("${r.files} files (${human(r.bytes)}) → ${local.absolutePath}")
                if (r.skipped > 0) append("  ·  skipped ${r.skipped} >4MB (grab in Files)")
                if (r.failed > 0) append("  ·  ${r.failed} failed")
            }
            busy = false
        }
    }

    fun backupDevice() {
        if (!connected || busy) { if (!connected) status = "Connect the Flipper first."; return }
        busy = true; status = "Archiving /int on the Flipper…"
        scope.launch {
            val ok = ble.storageBackupCreate(DEV_TMP)
            if (!ok) { status = "✗ Device backup failed (loader busy? hold Back)."; busy = false; return@launch }
            status = "Pulling backup to phone…"
            val bytes = ble.storageRead(DEV_TMP)
            ble.storageDelete(DEV_TMP, recursive = false)
            if (bytes == null) { status = "✗ Couldn't read the backup off the SD."; busy = false; return@launch }
            val f = File(ctx.getExternalFilesDir(null), "device_backup/device-${ts()}.tar").apply { parentFile?.mkdirs() }
            runCatching { f.writeBytes(bytes) }
                .onSuccess { status = "✓ Device backup (${human(bytes.size.toLong())}) → ${f.absolutePath}" }
                .onFailure { status = "✗ Couldn't save the backup on the phone." }
            busy = false
        }
    }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; status = "Reading backup from phone…"
        scope.launch {
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null) { status = "✗ Couldn't read that file."; busy = false; return@launch }
            status = "Pushing backup to the Flipper…"
            if (!ble.storageWrite(DEV_TMP, bytes)) { status = "✗ Upload to the Flipper failed."; busy = false; return@launch }
            status = "Restoring /int…"
            val ok = ble.storageBackupRestore(DEV_TMP)
            ble.storageDelete(DEV_TMP, recursive = false)
            status = if (ok) "✓ Device restored — reboot the Flipper to apply." else "✗ Restore failed."
            busy = false
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Backup & Restore", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Eyebrow("SD DATA + DEVICE /INT") }
        }

        FoxCard { Column {
            Eyebrow("SD data — your captures (/ext)")
            Spacer(Modifier.height(4.dp))
            Text("Recursively copies the whole SD to the phone. Files over 4 MB are skipped (pull those individually in File Manager).",
                color = Fox.muted, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(if (busy) "Working…" else "Back up SD → phone", enabled = connected && !busy) { backupSd() }
                if (busy) Btn("Stop", enabled = true, accent = false) { stopReq = true }
            }
            if (progress.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(progress, color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp) }
        } }

        FoxCard { Column {
            Eyebrow("Device — pairing, settings, dolphin (/int)")
            Spacer(Modifier.height(4.dp))
            Text("The qFlipper-style device backup. Restore needs a reboot to take effect.",
                color = Fox.muted, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Back up device → phone", enabled = connected && !busy) { backupDevice() }
                Btn("Restore from phone", enabled = connected && !busy, accent = false) { restorePicker.launch(arrayOf("*/*")) }
            }
        } }

        FoxCard { Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (connected) "LINKED" else "OFFLINE", if (connected) Fox.live else Fox.faint, Fox.surface2)
            Spacer(Modifier.width(8.dp))
            Text(status, color = if (status.startsWith("✗")) Fox.warn else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
        } }
    }
}

@Composable
private fun Btn(label: String, enabled: Boolean, accent: Boolean = true, onClick: () -> Unit) {
    val bg = if (!enabled) Fox.surface2 else if (accent) Fox.amber else Fox.surface
    val fg = if (!enabled) Fox.faint else if (accent) Fox.amberInk else Fox.fg
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
        .border(1.dp, Fox.line, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled) { onClick() }
        .padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
