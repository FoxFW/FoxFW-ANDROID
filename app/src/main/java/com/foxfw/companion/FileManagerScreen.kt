package com.foxfw.companion

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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

/**
 * Flipper SD file browser over the existing storage RPC (list/read/write/delete/mkdir/rename).
 * Download pulls a file to the app's external files dir (sd_pull/); Upload writes a phone file to
 * the current directory via the SAF picker. No firmware changes — pure RPC.
 */
private fun joinPath(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"
private fun parentOf(dir: String): String = dir.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
private fun humanSize(n: Long): String = when {
    n >= 1 shl 20 -> "%.1f MB".format(n / 1_048_576.0)
    n >= 1 shl 10 -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}

private fun uriDisplayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
}

@Composable
fun FileManagerScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // Use whichever Flipper transport is live (BLE or USB-to-Flipper), re-read reactively so the
    // screen works over USB too and updates when the link comes/goes while open.
    val ble = FlipperTransport.active
    var connected by remember { mutableStateOf(FlipperTransport.active?.connected == true) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { connected = FlipperTransport.active?.connected == true; kotlinx.coroutines.delay(800) }
    }

    var cur by remember { mutableStateOf("/ext") }
    val entries = remember { mutableStateListOf<Proto.FileEntry>() }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (connected) "Loading…" else "Connect the Flipper (Device tab) first.") }

    var renameTarget by remember { mutableStateOf<Proto.FileEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Proto.FileEntry?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var mkdirText by remember { mutableStateOf("") }

    fun reload() {
        if (!connected) { status = "Connect the Flipper first."; return }
        loading = true
        scope.launch {
            val l = ble?.storageList(cur)
            entries.clear()
            if (l == null) status = "✗ Couldn't list $cur (loader busy? hold Back on the Flipper)."
            else { entries.addAll(l); status = "${l.count { it.isDir }} folders · ${l.count { !it.isDir }} files" }
            loading = false
        }
    }

    fun open(dir: String) { cur = dir; reload() }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = uriDisplayName(ctx, uri)
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null) { status = "✗ Couldn't read $name from phone."; return@launch }
            status = "Uploading $name (${humanSize(bytes.size.toLong())})…"
            val ok = ble?.storageWrite(joinPath(cur, name), bytes) ?: false
            status = if (ok) "✓ Uploaded $name → $cur" else "✗ Upload of $name failed."
            reload()
        }
    }

    fun download(e: Proto.FileEntry) {
        scope.launch {
            status = "Downloading ${e.name}…"
            val b = ble?.storageRead(joinPath(cur, e.name))
            if (b == null) { status = "✗ Couldn't read ${e.name}."; return@launch }
            val dir = File(ctx.getExternalFilesDir(null), "sd_pull").apply { mkdirs() }
            val f = File(dir, e.name)
            runCatching { f.writeBytes(b) }
                .onSuccess { status = "✓ Saved ${e.name} (${humanSize(b.size.toLong())}) → ${f.absolutePath}" }
                .onFailure { status = "✗ Couldn't save ${e.name} on the phone." }
        }
    }

    fun doRename() {
        val t = renameTarget ?: return
        val newName = renameText.trim()
        renameTarget = null
        if (newName.isEmpty() || newName == t.name || newName.contains('/')) return
        scope.launch {
            status = "Renaming…"
            val ok = ble?.storageRename(joinPath(cur, t.name), joinPath(cur, newName)) ?: false
            status = if (ok) "✓ Renamed to $newName" else "✗ Rename failed."
            reload()
        }
    }

    fun doDelete() {
        val t = deleteTarget ?: return
        deleteTarget = null
        scope.launch {
            status = "Deleting ${t.name}…"
            val ok = ble?.storageDelete(joinPath(cur, t.name), recursive = t.isDir) ?: false
            status = if (ok) "✓ Deleted ${t.name}" else "✗ Delete failed."
            reload()
        }
    }

    fun doMkdir() {
        val name = mkdirText.trim()
        showMkdir = false; mkdirText = ""
        if (name.isEmpty() || name.contains('/')) return
        scope.launch {
            status = "Creating $name…"
            val ok = ble?.storageMkdir(joinPath(cur, name)) ?: false
            status = if (ok) "✓ Created $name" else "✗ Couldn't create $name."
            reload()
        }
    }

    remember(connected) { if (connected && entries.isEmpty()) reload(); connected }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Files", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow("FLIPPER SD BROWSER")
            }
            IconBox(Icons.Filled.Upload, enabled = connected) { uploadPicker.launch(arrayOf("*/*")) }
            Spacer(Modifier.width(8.dp))
            IconBox(Icons.Filled.CreateNewFolder, enabled = connected) { showMkdir = true }
            Spacer(Modifier.width(8.dp))
            IconBox(Icons.Filled.Refresh) { reload() }
        }

        // Path bar
        FoxCard { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cur, color = Fox.amber, fontFamily = FoxMono, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (cur != "/") Text("↑ up", color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp,
                modifier = Modifier.clickable { open(parentOf(cur)) }.padding(start = 8.dp))
        } }

        FoxCard { Column {
            if (entries.isEmpty() && !loading) Text("empty", color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp)
            entries.forEach { e ->
                Row(Modifier.fillMaxWidth()
                    .clickable { if (e.isDir) open(joinPath(cur, e.name)) else download(e) }
                    .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (e.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null,
                        tint = if (e.isDir) Fox.amber else Fox.muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, color = Fox.fg, fontSize = 13.sp)
                        Text(if (e.isDir) "folder" else humanSize(e.size), color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
                    }
                    if (!e.isDir) { ActionText("get") { download(e) }; Spacer(Modifier.width(10.dp)) }
                    ActionText("ren") { renameTarget = e; renameText = e.name }
                    Spacer(Modifier.width(10.dp))
                    ActionText("del", Fox.warn) { deleteTarget = e }
                }
            }
        } }

        if (loading) FoxCard { Text("Working…", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp) }
        FoxCard { Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (connected) "LINKED" else "OFFLINE", if (connected) Fox.live else Fox.faint, Fox.surface2)
            Spacer(Modifier.width(8.dp))
            Text(status, color = if (status.startsWith("✗")) Fox.warn else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
        } }
    }

    renameTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            confirmButton = { TextButton(onClick = { doRename() }) { Text("Rename", color = Fox.amber) } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = Fox.muted) } },
            title = { Text("Rename ${t.name}", color = Fox.fg, fontSize = 15.sp) },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            containerColor = Fox.surface,
        )
    }
    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = { TextButton(onClick = { doDelete() }) { Text("Delete", color = Fox.warn) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = Fox.muted) } },
            title = { Text("Delete ${t.name}?", color = Fox.fg, fontSize = 15.sp) },
            text = { Text(if (t.isDir) "Deletes the folder and everything in it. This can't be undone." else "This can't be undone.", color = Fox.muted, fontSize = 13.sp) },
            containerColor = Fox.surface,
        )
    }
    if (showMkdir) {
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            confirmButton = { TextButton(onClick = { doMkdir() }) { Text("Create", color = Fox.amber) } },
            dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("Cancel", color = Fox.muted) } },
            title = { Text("New folder in $cur", color = Fox.fg, fontSize = 15.sp) },
            text = { OutlinedTextField(mkdirText, { mkdirText = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name", fontSize = 11.sp) }) },
            containerColor = Fox.surface,
        )
    }
}

@Composable
private fun IconBox(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
        .border(1.dp, Fox.line, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) Fox.fg else Fox.faint, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun ActionText(label: String, color: androidx.compose.ui.graphics.Color = Fox.amber, onClick: () -> Unit) {
    Text(label, color = color, fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.clickable { onClick() })
}
