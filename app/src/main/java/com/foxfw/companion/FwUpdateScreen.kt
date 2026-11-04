package com.foxfw.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * FoxFW firmware update. The latest release is auto-downloaded in the background (see [FwRepo]) so
 * it's ready to flash unprompted; the user can also pick any of the last 25 releases from the
 * dropdown and download + install it manually. Two install paths — RPC (.tgz) and Flipper (.tar).
 */
private const val UPDATE_ROOT = "/ext/update"
private const val DAEMON_FAP = "/ext/apps/System/fox_android_companion.fap"
// Earliest firmware that supports this companion app. Flashing anything older breaks the app link.
private const val MIN_APP_FW = "v2.1"

private fun codeMeaning(code: Int): String = when (code) {
    0 -> "OK — validated and ready to flash"
    1 -> "manifest path invalid"
    2 -> "manifest folder not found (upload incomplete?)"
    3 -> "manifest invalid / corrupt"
    4 -> "a stage file is missing (upload incomplete?)"
    5 -> "integrity check failed (corrupt upload) — re-run, prefer USB"
    6 -> "couldn't create the update pointer"
    7 -> "wrong hardware target — this build isn't for this Flipper"
    8 -> "manifest version too old for this firmware"
    9 -> "internal storage full"
    -1 -> "no response (update RPC unsupported? loader busy?)"
    else -> "unspecified error ($code)"
}

private fun lastCheckedStr(ms: Long): String =
    if (ms == 0L) "never"
    else java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

private fun human(n: Long) = when {
    n >= 1 shl 20 -> "%.1f MB".format(n / 1_048_576.0)
    n >= 1 shl 10 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

/** Friendly release label: "foxfw-v2.1" / "v2.0.13" → "FoxFW v2.1" / "FoxFW v2.0.13". */
private fun fwLabel(tag: String): String = "FoxFW " + tag.replace(Regex("(?i)^foxfw[-_]?"), "")

@Composable
fun FwUpdateScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var connected by remember { mutableStateOf(FlipperTransport.active?.connected == true) }
    LaunchedEffect(Unit) { while (true) { connected = FlipperTransport.active?.connected == true; delay(800) } }
    val onUsb = UsbLink.usbConnected

    var tick by remember { mutableStateOf(0) }
    // Auto-check on open (replaces the old button): throttled, so no network if we checked recently.
    // Then make sure BOTH of the latest release's packages are downloaded, and tick so the two
    // install buttons appear together instead of one-then-the-other.
    LaunchedEffect(Unit) {
        FwRepo.load(ctx)
        runCatching { FwRepo.checkOnly(ctx) }
        tick++
        runCatching { FwRepo.ensureDownloaded(ctx) }
        tick++
    }

    var installed by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showPath by remember { mutableStateOf(false) }

    // download progress for the selected release
    var dl by remember { mutableStateOf<FwRepo.DlProgress?>(null) }
    var dlTag by remember { mutableStateOf<String?>(null) }
    // install progress (staging .tgz files over RPC, or sending the .tar)
    var inst by remember { mutableStateOf<FwRepo.DlProgress?>(null) }
    var instLabel by remember { mutableStateOf("") }
    var showDowngrade by remember { mutableStateOf(false) }
    var pendingUseTar by remember { mutableStateOf(false) }

    LaunchedEffect(connected) {
        FwRepo.load(ctx)
        if (connected) {
            var v: String? = null
            var tries = 0
            while (v == null && tries < 3) {   // USB handshake may still be settling — retry the version read
                v = runCatching {
                    FlipperTransport.active?.deviceInfo()?.firstOrNull { it.first.lowercase().filter(Char::isLetterOrDigit) == "foxversion" }?.second
                }.getOrNull()
                if (v == null) delay(600)
                tries++
            }
            if (v != null) installed = v
        }
    }

    val releases = remember(tick) { FwRepo.releases }
    val latestTag = remember(tick) { FwRepo.latestTag }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    // keep the latest selected by default when the list (re)populates
    LaunchedEffect(releases) {
        if (releases.isNotEmpty() && (selectedTag == null || releases.none { it.tag == selectedTag })) {
            selectedTag = releases.first().tag
        }
    }
    val selected = remember(tick, selectedTag) { releases.firstOrNull { it.tag == selectedTag } }
    val selDownloaded = remember(tick, selectedTag) { selectedTag?.let { FwRepo.isDownloaded(ctx, it) } ?: false }

    val updateReady = remember(tick, installed) {
        latestTag != null && FwRepo.isDownloaded(ctx, latestTag) && FwRepo.isNewer(latestTag, installed)
    }

    fun downloadSelected() {
        val rel = selected ?: return
        scope.launch {
            dlTag = rel.tag
            dl = FwRepo.DlProgress(0, (rel.tgzSize + rel.tarSize).coerceAtLeast(1), 0)
            val ok = FwRepo.downloadRelease(ctx, rel) { p -> dl = p }
            tick++
            if (ok) {
                dl = FwRepo.DlProgress((rel.tgzSize + rel.tarSize).coerceAtLeast(1), (rel.tgzSize + rel.tarSize).coerceAtLeast(1), 0)
                delay(3000); dl = null; dlTag = null       // show 100% briefly, then hide
            } else {
                dl = null; dlTag = null; status = FwRepo.lastError ?: "Download failed."
            }
        }
    }

    // RPC install: unpack the .tgz on the phone, stage files to /ext/update, validate, reboot.
    suspend fun runInstallRpc(l: FlipperLink, tgz: File) {
        status = "Unpacking the update…"
        val items = runCatching { tgz.inputStream().use { TarGz.extract(it) } }.getOrNull()
        if (items.isNullOrEmpty()) { status = "✗ Couldn't unpack the update."; return }
        val manifest = items.firstOrNull { it.path.substringAfterLast('/') == "update.fuf" }
            ?: run { status = "✗ No update.fuf inside the package."; return }
        val total = items.sumOf { it.bytes.size.toLong() }.coerceAtLeast(1)
        instLabel = "Installing (RPC)"
        status = "Staging ${items.size} files (${human(total)}) to the SD…"
        val dirs = items.map { "$UPDATE_ROOT/" + it.path.substringBeforeLast('/') }.toSortedSet(compareBy { it.count { c -> c == '/' } })
        l.storageMkdir(UPDATE_ROOT)
        for (d in dirs) l.storageMkdir(d)
        var base = 0L; var failed = 0
        var lastT = System.currentTimeMillis(); var lastB = 0L; var speed = 0L
        for (it in items) {
            val ok = l.storageWrite("$UPDATE_ROOT/${it.path}", it.bytes) { w, _ ->
                val d = (base + w).coerceAtMost(total)
                val now = System.currentTimeMillis()
                if (now - lastT >= 250) { // throttle UI updates (~4/s) so thousands of chunks don't flood recomposition
                    speed = ((d - lastB) * 1000) / (now - lastT); lastB = d; lastT = now
                    inst = FwRepo.DlProgress(d, total, speed)
                }
            }
            if (!ok) failed++
            base += it.bytes.size
            inst = FwRepo.DlProgress(base.coerceAtMost(total), total, speed)
        }
        inst = null
        if (failed > 0) { status = "✗ $failed file(s) failed to upload — retry (prefer USB)."; return }
        status = "Validating on the Flipper…"
        val code = l.systemUpdate("$UPDATE_ROOT/${manifest.path}")
        if (code == 0) {
            status = "✓ Validated. Flashing now — the Flipper reboots into the updater."
            delay(600); l.systemReboot(2)
        } else status = "✗ Rejected: ${codeMeaning(code)}"
    }

    // Flipper install: send the whole .tar to the SD; the companion daemon extracts it on-device.
    suspend fun runInstallFlipper(l: FlipperLink, tar: File) {
        val remote = "$UPDATE_ROOT/${tar.name}"
        l.storageMkdir(UPDATE_ROOT)
        val bytes = runCatching { tar.readBytes() }.getOrNull()
            ?: run { status = "✗ Couldn't read the downloaded .tar."; return }
        instLabel = "Sending to Flipper${if (onUsb) " (USB)" else " (BLE)"}"
        status = "Sending ${human(tar.length())}${if (onUsb) " over USB" else " over BLE — slow; USB is much faster"}…"
        var lastT = System.currentTimeMillis(); var lastB = 0L; var speed = 0L
        val ok = l.storageWrite(remote, bytes) { w, t ->
            val now = System.currentTimeMillis()
            if (now - lastT >= 250) { // throttle UI updates (~4/s)
                speed = ((w - lastB) * 1000) / (now - lastT); lastB = w; lastT = now
                inst = FwRepo.DlProgress(w, t.coerceAtLeast(1), speed)
            }
        }
        inst = null
        if (!ok) { status = "✗ Transfer failed — use a USB cable and retry."; return }
        status = "Extracting on the Flipper and handing off to the updater…"
        val okA = l.appStartSafe(DAEMON_FAP, "UPDATE $remote")
        status = if (okA) "✓ The Flipper is extracting and will reboot into the updater to flash."
                 else "✗ Couldn't start the on-Flipper installer (loader busy — hold Back, retry)."
    }

    // useTar=false → RPC install (.tgz); useTar=true → Flipper on-device install (.tar).
    fun doInstall(useTar: Boolean) = scope.launch {
        val tag = selectedTag ?: return@launch
        val l = FlipperTransport.active ?: run { status = "Connect the Flipper first."; return@launch }
        busy = true
        if (useTar) {
            val tar = FwRepo.localTarFor(ctx, tag)
            if (tar != null) runInstallFlipper(l, tar) else status = "No .tar downloaded for $tag — tap Download first."
        } else {
            val tgz = FwRepo.localTgzFor(ctx, tag)
            if (tgz != null) runInstallRpc(l, tgz) else status = "No .tgz downloaded for $tag — tap Download first."
        }
        busy = false
    }

    fun installSelected(useTar: Boolean) {
        val tag = selectedTag ?: return
        if (FwRepo.isNewer(MIN_APP_FW, tag)) { pendingUseTar = useTar; showDowngrade = true } // older than v2.1 → warn first
        else doInstall(useTar)
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Firmware Update", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Eyebrow("LATEST FOXFW, STRAIGHT FROM GITHUB") }
        }

        // version / status card
        FoxCard(modifier = Modifier.fillMaxWidth()) { Column {
            Row {
                Text("Installed", color = Fox.muted, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                Text(installed ?: if (connected) "reading…" else "connect to check", color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text("Latest", color = Fox.muted, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                Text(latestTag ?: "checking…", color = if (updateReady) Fox.amber else Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text("Last checked", color = Fox.muted, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                Text(lastCheckedStr(FwRepo.lastChecked), color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
            }
        } }

        // 3) up-to-date / update message
        if (updateReady)
            FoxCard(modifier = Modifier.fillMaxWidth(), border = Fox.amber.copy(alpha = 0.4f), bg = Fox.amberDim) {
                Text("Update $latestTag is downloaded and ready to install below. Keep the Flipper charged and connected the whole time; it only flashes on the reboot, and a bad upload is rejected, not flashed.",
                    color = Fox.amber, fontSize = 12.sp, lineHeight = 16.sp) }
        else if (installed != null)
            FoxCard(modifier = Modifier.fillMaxWidth(), border = Fox.live.copy(alpha = 0.4f), bg = Fox.liveDim) {
                Text("You're on the latest FoxFW. 👍", color = Fox.live, fontSize = 12.sp, lineHeight = 16.sp) }
        else
            FoxCard(modifier = Modifier.fillMaxWidth()) {
                Text("Connect your Flipper to compare versions. The latest release is downloaded in the background so it's ready to install.",
                    color = Fox.muted, fontSize = 12.sp, lineHeight = 16.sp) }

        // 4) GITHUB card — release picker + download/install
        FoxCard(modifier = Modifier.fillMaxWidth()) { Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Pill("GITHUB", Fox.amber, Fox.surface2)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ReleaseDropdown(releases, selectedTag, selDownloaded) { selectedTag = it }
                }
            }

            val err = FwRepo.lastError
            if (err != null && status.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(err, color = Fox.warn, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(status, color = if (status.startsWith("✗")) Fox.warn else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                when {
                    selected == null -> {
                        FwBtn("Download", enabled = false) {}
                        Spacer(Modifier.width(10.dp))
                        FwBtn("Install", enabled = false) {}
                    }
                    selDownloaded -> {
                        // Both install paths, each shown when that package is present for the selected release.
                        val tgzReady = selectedTag?.let { FwRepo.localTgzFor(ctx, it) != null } ?: false
                        val tarReady = selectedTag?.let { FwRepo.localTarFor(ctx, it) != null } ?: false
                        if (tgzReady) FwBtn(if (busy) "Working…" else "Install (RPC)", enabled = connected && !busy) { installSelected(false) }
                        if (tgzReady && tarReady) Spacer(Modifier.width(10.dp))
                        if (tarReady) FwBtn(if (busy) "Working…" else "Install on Flipper", enabled = connected && !busy) { installSelected(true) }
                    }
                    else -> FwBtn(if (dlTag == selectedTag && dl != null) "Downloading…" else "Download", enabled = dl == null) { downloadSelected() }
                }
            }
        } }

        // live progress card — download, or install (staging .tgz files / sending the .tar)
        dl?.let { ProgressCard("Downloading ${dlTag ?: ""}", it) }
        inst?.let { ProgressCard(instLabel, it) }

        // where files live (tap the info row)
        Text("Saved under ${FwRepo.storageDir(ctx)}/<release>/", color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().clickable { showPath = true })

        if (showPath) AlertDialog(
            onDismissRequest = { showPath = false },
            title = { Text("Downloaded firmware", color = Fox.fg) },
            text = { Text("Each release is kept in its own folder so nothing gets confused:\n\n${FwRepo.storageDir(ctx)}\n   <release-tag>/flipper-z-f7-update-…tgz / .tar",
                color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp) },
            confirmButton = { TextButton(onClick = { showPath = false }) { Text("OK", color = Fox.amber) } },
            containerColor = Fox.surface,
        )

        if (showDowngrade) AlertDialog(
            onDismissRequest = { showDowngrade = false },
            title = { Text("Install older firmware?", color = Fox.fg) },
            text = { Text("$selectedTag is older than $MIN_APP_FW. $MIN_APP_FW is the earliest firmware that supports this companion app — installing an earlier version will make this app stop working with the Flipper.",
                color = Fox.muted, fontSize = 12.sp, lineHeight = 16.sp) },
            confirmButton = { TextButton(onClick = { showDowngrade = false; doInstall(pendingUseTar) }) { Text("Install anyway", color = Fox.warn) } },
            dismissButton = { TextButton(onClick = { showDowngrade = false }) { Text("Cancel", color = Fox.muted) } },
            containerColor = Fox.surface,
        )
    }
}

@Composable
private fun ReleaseDropdown(releases: List<FwRepo.Release>, selectedTag: String?, selDownloaded: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Fox.surface2)
                .border(1.dp, Fox.line, RoundedCornerShape(8.dp))
                .clickable(enabled = releases.isNotEmpty()) { open = true }
                .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selectedTag?.let { fwLabel(it) } ?: if (releases.isEmpty()) "no releases" else "select…",
                color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
            if (selectedTag != null) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.width(7.dp).height(7.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (selDownloaded) Fox.live else Fox.faint))
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ArrowDropDown, "Choose release", tint = Fox.muted, modifier = Modifier.width(18.dp).height(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Fox.surface)) {
            releases.forEach { r ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fwLabel(r.tag), color = if (r.tag == selectedTag) Fox.amber else Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
                            if (r.tag == releases.firstOrNull()?.tag) {
                                Spacer(Modifier.width(6.dp)); Text("latest", color = Fox.live, fontFamily = FoxMono, fontSize = 9.sp)
                            }
                        }
                    },
                    onClick = { onSelect(r.tag); open = false },
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(label: String, p: FwRepo.DlProgress) {
    FoxCard(modifier = Modifier.fillMaxWidth(), border = Fox.amber.copy(alpha = 0.4f)) { Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Fox.fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(0.dp).weight(1f))
            Text("${p.pct}%", color = Fox.amber, fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Fox.surface2)) {
            Box(Modifier.fillMaxWidth((p.pct / 100f).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Fox.amber))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${human(p.done)} / ${human(p.total)}" + if (p.bytesPerSec > 0 && !p.finished) " · ${human(p.bytesPerSec)}/s" else if (p.finished) " · done" else "",
            color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp,
        )
    } }
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
