package com.foxfw.companion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxLogo
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.GradientBar
import com.foxfw.companion.ui.GradientText
import com.foxfw.companion.ui.Pill
import com.foxfw.companion.ui.Proto
import com.foxfw.companion.ui.ProtocolBadge
import com.foxfw.companion.ui.SignalBars
import com.foxfw.companion.ui.protoFiat
import com.foxfw.companion.ui.protoHitag
import com.foxfw.companion.ui.protoKeeloq
import com.foxfw.companion.ui.protoKia
import com.foxfw.companion.ui.protoPandora
import com.foxfw.companion.ui.protoPsa
import com.foxfw.companion.ui.protoVag
import kotlinx.coroutines.delay


@Composable
private fun ScreenScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

/* ------------------------------- DEVICE ------------------------------- */
@Composable
fun DeviceScreen(fle: FlipperBle, onConnect: () -> Unit, onOpenDecode: () -> Unit) = ScreenScroll {
    val connected = fle.conn == Conn.CONNECTED
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var battery by remember { mutableStateOf<String?>(null) }
    var captures by remember { mutableStateOf(0) }
    LaunchedEffect(connected) {
        captures = ctx.getExternalFilesDir(null)?.listFiles { f -> f.name.endsWith(".sub", true) }?.size ?: 0
        battery = if (connected) {
            val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fle.powerInfo() }
            p?.firstOrNull { it.first.contains("charge", true) && !it.first.contains("voltage", true) }?.second?.let { "$it%" }
        } else null
    }
    val pill = when (fle.conn) {
        Conn.CONNECTED -> Triple("Linked", Fox.live, Fox.liveDim)
        Conn.SCANNING -> Triple("Scanning", Fox.amber, Fox.amberDim)
        Conn.CONNECTING -> Triple("Linking", Fox.amber, Fox.amberDim)
        Conn.DISCONNECTED -> Triple("Offline", Fox.faint, Fox.surface2)
    }
    FoxCard(
        border = if (connected) Fox.violet.copy(alpha = 0.42f) else Fox.line,
        modifier = Modifier.clickable { onConnect() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FoxLogo(56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fle.deviceName ?: "Fox One", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Pill(pill.first, pill.second, pill.third, dot = true)
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    if (connected) "FoxFW 2.1 · f7 · BLE RPC · region unlocked" else "Tap to connect over BLE",
                    color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp,
                )
            }
            if (connected) Text(battery ?: "—", color = Fox.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            else Icon(Icons.Filled.ChevronRight, null, tint = Fox.faint, modifier = Modifier.size(22.dp))
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(captures.toString(), "Saved .sub", Modifier.weight(1f))
        StatTile(DecodeFeed.decodedToday.toString(), "Decoded", Modifier.weight(1f), accent = true)
        StatTile(ArkeEngine.PROTOCOL_COUNT.toString(), "Protocols", Modifier.weight(1f))
    }

    FoxCard {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Live decode"); Spacer(Modifier.weight(1f))
                Text("OPEN →", color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onOpenDecode() })
            }
            Spacer(Modifier.height(12.dp))
            val recent = DecodeFeed.items.take(3)
            if (recent.isEmpty()) {
                Text("No captures yet — open Decode to capture a fob via the Flipper.",
                    color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp)
            } else recent.forEachIndexed { i, e ->
                if (i > 0) Divider()
                DecodeRow(DecodeEntry(e.proto, e.title, e.meta, "", e.bars, agoLabel(e.tMs)))
            }
        }
    }

    FoxCard {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Receiver"); Spacer(Modifier.weight(1f))
                Pill(if (connected) "Ready" else "Offline", if (connected) Fox.live else Fox.faint, if (connected) Fox.liveDim else Fox.surface2, dot = connected)
            }
            Spacer(Modifier.height(12.dp))
            Text(if (connected) "Flipper CC1101 · internal / external" else "Connect to capture over the Flipper",
                color = Fox.fg, fontFamily = FoxMono, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            com.foxfw.companion.ui.GraticuleScreen(Modifier.fillMaxWidth().height(52.dp)) {
                Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 10.dp)) { Waveform() }
            }
        }
    }
}

/* ------------------------------- DECODE ------------------------------- */
data class DecodeEntry(val proto: Proto, val title: String, val meta: String, val rssi: String, val bars: Int, val ago: String)

private val decodeFreqs = listOf(
    "300.00" to 300000000, "303.875" to 303875000, "304.25" to 304250000, "310.00" to 310000000,
    "315.00" to 315000000, "318.00" to 318000000, "330.00" to 330000000, "345.00" to 345000000,
    "348.00" to 348000000, "387.00" to 387000000, "390.00" to 390000000, "418.00" to 418000000,
    "433.075" to 433075000, "433.42" to 433420000, "433.777" to 433777000, "433.905" to 433905000,
    "433.92" to 433920000, "434.42" to 434420000,
    "434.775" to 434775000, "438.90" to 438900000, "464.00" to 464000000, "779.00" to 779000000,
    "868.35" to 868350000, "868.95" to 868950000, "915.00" to 915000000, "925.00" to 925000000,
)
// 433.777 = Chacon 84181, 433.905 = Meiantech/Atlantics/Aidebao (from the PandwaRF remote library).
private val DEFAULT_FREQ_IDX = 16   // 433.92
// (label, fox_subghz_io preset index): 0=AM270 1=AM650 2=FM238 3=FM476
private val decodeMods = listOf("AM650" to 1, "AM270" to 0, "FM238" to 2, "FM476" to 3)
private val decodeDevNames = listOf("Int" to 0, "Ext" to 1, "Auto" to 2)

@Composable
fun DecodeScreen() {
    var openFob by remember { mutableStateOf<DecodeFeed.Item?>(null) }
    val fob = openFob
    if (fob != null) {
        FobScreen(fob, onBack = { openFob = null })
        return
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var freqIdx by remember { mutableStateOf(DEFAULT_FREQ_IDX) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        SpecHandoff.tuneHz?.let { hz ->
            decodeFreqs.indices.minByOrNull { kotlin.math.abs(decodeFreqs[it].second - hz) }?.let { freqIdx = it }
            SpecHandoff.tuneHz = null
        }
    }
    var modIdx by remember { mutableStateOf(0) }
    var devIdx by remember { mutableStateOf(2) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (ArkeEngine.available) "Engine ready · capture a fob via the Flipper" else "decode engine loading…") }
    val items = DecodeFeed.items

    fun listen() {
        if (!FlipperRf.connected) { status = "Connect the Flipper (Remote Screen) to capture."; return }
        busy = true
        val (fLabel, fHz) = decodeFreqs[freqIdx]
        val dev = decodeDevNames[devIdx].second
        val (modLabel, preset) = decodeMods[modIdx]
        status = "Listening 4s on $fLabel MHz $modLabel… press the fob now."
        scope.launch {
            val sub = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperRf.rx(fHz, dev, 4000, preset) }
            if (sub == null) { busy = false; status = "Capture failed · ${FlipperRf.lastRxDiag}"; return@launch }
            val (frames, err) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ArkeEngine.decode(sub) }
            busy = false
            when {
                err != null -> status = "decode error: $err"
                frames.isEmpty() -> status = "No known protocol in the capture — try again, fob closer."
                else -> { DecodeFeed.add(frames, sub); status = "Decoded ${frames.size} frame(s) · ${frames.first().make}" }
            }
        }
    }

    ScreenScroll {
        FoxCard {
            Column {
                com.foxfw.companion.ui.GraticuleScreen(Modifier.fillMaxWidth().height(96.dp)) {
                    Row(Modifier.align(Alignment.Center).fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("TUNED", color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(decodeFreqs[freqIdx].first, color = Fox.phos, fontFamily = FoxMono, fontSize = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
                                Spacer(Modifier.width(6.dp))
                                Text("MHz", color = Fox.muted, fontFamily = FoxMono, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text("${decodeMods[modIdx].first} · CC1101", color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp, letterSpacing = 1.sp)
                        }
                        Box(Modifier.weight(0.85f)) { Waveform() }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Frequency (MHz)"); Spacer(Modifier.height(6.dp))
                        PickDropdown(decodeFreqs.map { it.first }, freqIdx) { freqIdx = it }
                    }
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Modulation"); Spacer(Modifier.height(6.dp))
                        PickDropdown(decodeMods.map { it.first }, modIdx) { modIdx = it }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Eyebrow("CC1101")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    decodeDevNames.forEachIndexed { i, (lbl, _) ->
                        PickChip(lbl, i == devIdx, Modifier.weight(1f)) { devIdx = i }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val capHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (busy) Fox.surface2 else Fox.amber).clickable(enabled = !busy) {
                        capHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        listen()
                    }
                    .padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                    Text(if (busy) "listening…" else "Capture 4s via Flipper", color = if (busy) Fox.muted else Fox.amberInk,
                        fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Decoded fobs — ${items.size}"); Spacer(Modifier.weight(1f))
            Pill(if (busy) "RX" else "Idle", Fox.amber, Fox.amberDim, dot = busy)
        }
        FoxCard {
            Column {
                if (items.isEmpty()) Text("No captures yet. Pick a frequency and capture a fob.", color = Fox.faint, fontFamily = FoxMono, fontSize = 12.sp)
                items.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    Box(Modifier.fillMaxWidth().clickable { openFob = e }) {
                        DecodeRow(DecodeEntry(e.proto, e.title, e.meta, "", e.bars, agoLabel(e.tMs)))
                    }
                }
            }
        }
    }
}

@Composable
private fun PickDropdown(options: List<String>, index: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Fox.surface2)
                .border(1.dp, Fox.line2, RoundedCornerShape(9.dp)).clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.getOrElse(index) { "?" }, color = Fox.fg, fontFamily = FoxMono, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, null, tint = Fox.muted, modifier = Modifier.size(18.dp))
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEachIndexed { i, opt ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(opt, fontFamily = FoxMono, fontSize = 13.sp, color = if (i == index) Fox.amber else Fox.fg) },
                    onClick = { open = false; onSelect(i) })
            }
        }
    }
}

@Composable
private fun PickChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.clickable { onClick() }, shape = RoundedCornerShape(9.dp),
        color = if (selected) Fox.amber else Fox.surface2, border = BorderStroke(1.dp, if (selected) Fox.amber else Fox.line2)) {
        Box(Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) Fox.amberInk else Fox.muted, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/* ------------------------------- ARCHIVE ------------------------------- */
data class SavedFile(val file: java.io.File, val meta: String, val freqHz: Int)

private fun scanSavedSubs(ctx: android.content.Context): List<SavedFile> {
    val dir = ctx.getExternalFilesDir(null) ?: return emptyList()
    return (dir.listFiles { f -> f.isFile && f.name.endsWith(".sub", true) } ?: emptyArray())
        .sortedByDescending { it.lastModified() }
        .map { f ->
            var freq = 433920000
            runCatching {
                f.bufferedReader().useLines { seq ->
                    seq.firstOrNull { it.startsWith("Frequency:") }?.substringAfter(":")?.trim()?.toIntOrNull()?.let { freq = it }
                }
            }
            val kb = (f.length() + 512) / 1024
            SavedFile(f, "%.2f MHz · %d KB".format(freq / 1e6, kb), freq)
        }
}

@Composable
fun ArchiveScreen() {
    var browsing by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val files = remember { mutableStateListOf<SavedFile>() }
    var status by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    fun refresh() { files.clear(); files.addAll(scanSavedSubs(ctx)) }
    LaunchedEffect(Unit) { refresh() }

    if (browsing) { SdBrowserScreen(onClose = { browsing = false; refresh() }); return }

    ScreenScroll {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Saved .sub — ${files.size}"); Spacer(Modifier.weight(1f))
            Text("BROWSE SD", color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { browsing = true })
            Spacer(Modifier.width(16.dp))
            Text("REFRESH", color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { refresh() })
        }
        if (files.isEmpty()) {
            FoxCard { Text("No saved captures yet. Forge in Pandora / Car & Gate Clone (OUTPUT: Save .sub), or capture in Decode.",
                color = Fox.muted, fontSize = 13.sp) }
        }
        FoxCard {
            Column {
                files.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    val open = expanded == f.file.name
                    Row(Modifier.fillMaxWidth().clickable { expanded = if (open) null else f.file.name }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                            .background(Fox.surface2).border(1.dp, Fox.line2, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.GraphicEq, null, tint = Fox.amber, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.file.name, color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(f.meta, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = Fox.faint, modifier = Modifier.size(18.dp))
                    }
                    if (open) {
                        Column(Modifier.padding(bottom = 10.dp)) {
                            Text(if (FlipperRf.connected) "Transmit via Flipper CC1101:" else "Connect the Flipper to transmit.",
                                color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Int" to 0, "Ext" to 1, "Auto" to 2).forEach { (lbl, dev) ->
                                    PickChip(lbl, false, Modifier.weight(1f)) {
                                        if (!FlipperRf.connected) { status = "Flipper not connected."; return@PickChip }
                                        scope.launch {
                                            status = "Transmitting ${f.file.name} on ${"%.2f".format(f.freqHz / 1e6)} MHz…"
                                            val txt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { f.file.readText() }.getOrNull() }
                                            val r = if (txt == null) TxResult.NotConnected else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperRf.tx(txt, f.freqHz, dev) }
                                            status = if (r.ok) "Sent ${f.file.name} via Flipper (${lbl})." else "TX ${r.label}."
                                        }
                                    }
                                }
                                PickChip("Delete", false, Modifier.weight(1f)) { runCatching { f.file.delete() }; refresh(); expanded = null; status = "Deleted ${f.file.name}" }
                            }
                        }
                    }
                }
            }
        }
        if (status.isNotEmpty()) Text(status, color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp)
    }
}

/* ------------------------------- SD BROWSER ------------------------------- */
private fun sizeLabel(n: Long): String =
    if (n < 1024) "$n B" else if (n < 1024 * 1024) "%.1f KB".format(n / 1024.0) else "%.1f MB".format(n / 1048576.0)

/** Browse the Flipper's microSD over BLE RPC and pull files down to the phone. */
@Composable
fun SdBrowserScreen(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var path by remember { mutableStateOf("/ext") }
    var entries by remember { mutableStateOf<List<com.foxfw.companion.Proto.FileEntry>?>(null) }  // null = loading
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }

    fun load(p: String) {
        entries = null; error = null; expanded = null
        scope.launch {
            if (!FlipperRf.connected) { error = "Connect the Flipper to browse its SD."; entries = emptyList(); return@launch }
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperTransport.active?.storageList(p) }
            if (r == null) { error = "Couldn't read $p"; entries = emptyList() } else entries = r
        }
    }
    LaunchedEffect(path) { load(path) }

    fun goUp() { if (path != "/ext") path = path.substringBeforeLast('/').ifEmpty { "/ext" } else onClose() }
    androidx.activity.compose.BackHandler { goUp() }

    ScreenScroll {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Fox.surface)
                .border(1.dp, Fox.line, RoundedCornerShape(9.dp)).clickable { goUp() },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, "Up", tint = Fox.fg, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("Flipper SD")
                Spacer(Modifier.height(3.dp))
                Text(path, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("CLOSE", color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onClose() })
        }

        FoxCard {
            Column {
                val list = entries
                when {
                    list == null -> Text("reading…", color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                    error != null -> Text(error!!, color = Fox.warn, fontFamily = FoxMono, fontSize = 12.sp)
                    list.isEmpty() -> Text("Empty folder.", color = Fox.faint, fontFamily = FoxMono, fontSize = 12.sp)
                    else -> list.forEachIndexed { i, e ->
                        if (i > 0) Divider()
                        val isOpen = !e.isDir && expanded == e.name
                        Row(Modifier.fillMaxWidth().clickable {
                            if (e.isDir) path = "$path/${e.name}" else expanded = if (isOpen) null else e.name
                        }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))
                                .background(Fox.surface2).border(1.dp, Fox.line2, RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center) {
                                Icon(if (e.isDir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, null,
                                    tint = if (e.isDir) Fox.amber else Fox.muted, modifier = Modifier.size(17.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name, color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                Text(if (e.isDir) "folder" else sizeLabel(e.size), color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp)
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = Fox.faint, modifier = Modifier.size(18.dp))
                        }
                        if (isOpen) {
                            Column(Modifier.padding(bottom = 10.dp)) {
                                PickChip("Load to phone", false, Modifier.fillMaxWidth()) {
                                    val full = "$path/${e.name}"
                                    scope.launch {
                                        status = "Loading ${e.name}…"
                                        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperTransport.active?.storageRead(full) }
                                        if (bytes == null) { status = "Load failed — ${e.name}"; return@launch }
                                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            runCatching { java.io.File(ctx.getExternalFilesDir(null), e.name).writeBytes(bytes) }.isSuccess
                                        }
                                        status = if (ok) "Loaded ${e.name} (${sizeLabel(bytes.size.toLong())}) · .sub files appear in Archive"
                                        else "Couldn't save ${e.name} to phone"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (status.isNotEmpty()) Text(status, color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp)
    }
}

/* ------------------------------- SETTINGS ------------------------------- */
@Composable
fun SettingsScreen(fle: FlipperBle) {
    var showFox by remember { mutableStateOf(false) }
    if (showFox) { FoxSettingsScreen(onClose = { showFox = false }); return }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val connected = fle.conn == Conn.CONNECTED
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }

    val deviceInfo = remember { mutableStateListOf<Pair<String, String>>() }
    val powerInfo = remember { mutableStateListOf<Pair<String, String>>() }
    var clock by remember { mutableStateOf("—") }
    var devBusy by remember { mutableStateOf(false) }
    var dynamicColor by remember { mutableStateOf(prefs.getBoolean("dynamic_color", false)) }
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    var toast by remember { mutableStateOf("") }

    fun refresh() {
        if (!connected) return
        devBusy = true
        scope.launch {
            val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fle.deviceInfo() }
            val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fle.powerInfo() }
            val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fle.getDateTime() }
            deviceInfo.clear(); d?.let { deviceInfo.addAll(it) }
            powerInfo.clear(); p?.let { powerInfo.addAll(it) }
            clock = c?.let { "%04d-%02d-%02d  %02d:%02d:%02d".format(it.year, it.month, it.day, it.hour, it.minute, it.second) } ?: "—"
            devBusy = false
        }
    }
    LaunchedEffect(connected) { if (connected) refresh() }

    ScreenScroll {
        // Full FoxFW settings entry
        FoxCard(Modifier.clickable { showFox = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FoxFW Settings", color = Fox.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("display · locale · night shift · RGB · desktop · power · system — 35 fields over BLE",
                        color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = Fox.amber, modifier = Modifier.size(18.dp))
            }
        }

        if (!connected) {
            FoxCard { Text("Connect the Flipper to read device info, battery, clock and controls.",
                color = Fox.muted, fontSize = 13.sp) }
        } else {
            // DEVICE
            FoxCard {
                Column {
                    Eyebrow("DEVICE", color = Fox.amber); Spacer(Modifier.height(8.dp))
                    if (deviceInfo.isEmpty()) Text(if (devBusy) "reading…" else "—", color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                    deviceInfo.take(8).forEach { (k, v) -> InfoRow(prettify(k), v) }
                }
            }
            // POWER / BATTERY
            FoxCard {
                Column {
                    Eyebrow("POWER / BATTERY", color = Fox.amber); Spacer(Modifier.height(8.dp))
                    if (powerInfo.isEmpty()) Text(if (devBusy) "reading…" else "—", color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                    powerInfo.filter { it.first.contains("charge", true) || it.first.contains("volt", true) || it.first.contains("health", true) || it.first.contains("temp", true) }
                        .take(6).forEach { (k, v) -> InfoRow(prettify(k), v) }
                }
            }
            // CLOCK
            FoxCard {
                Column {
                    Eyebrow("CLOCK", color = Fox.amber); Spacer(Modifier.height(8.dp))
                    Text(clock, color = Fox.fg, fontFamily = FoxMono, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    BigButton("Sync clock to phone time") {
                        scope.launch {
                            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fle.setDateTime() }
                            toast = if (ok) "Clock synced" else "Sync failed"; if (ok) refresh()
                        }
                    }
                }
            }
            // CONTROLS
            FoxCard {
                Column {
                    Eyebrow("CONTROLS", color = Fox.amber); Spacer(Modifier.height(8.dp))
                    BigButton("Find my Flipper") {
                        scope.launch { val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fle.playAlert() }; toast = if (ok) "Alert sent" else "Failed" }
                    }
                    Spacer(Modifier.height(8.dp))
                    BigButton("Reboot") { confirm = Triple("Reboot", "Reboot the Flipper now?") { fle.reboot(0); toast = "Rebooting…" } }
                    Spacer(Modifier.height(8.dp))
                    BigButton("Reboot to DFU") { confirm = Triple("Reboot to DFU", "Reboot into DFU (firmware flashing) mode?") { fle.reboot(1); toast = "Rebooting to DFU…" } }
                    Spacer(Modifier.height(6.dp))
                    Text("To update firmware, connect the Flipper to your PC with a USB data cable (charge-only cables won't work) and flash with qFlipper. Flashing can't be done over Bluetooth.",
                        color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().clickable {
                        confirm = Triple("Factory reset", "This ERASES all data and settings on the Flipper and cannot be undone. Continue?") { fle.factoryReset(); toast = "Factory reset sent" }
                    }, contentAlignment = Alignment.Center) {
                        Text("Factory reset…", color = Color(0xFFFF5C78), fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }

        // APP
        FoxCard {
            Column {
                Eyebrow("APP", color = Fox.amber); Spacer(Modifier.height(4.dp))
                SettingToggle("Dynamic color", "match the phone's Material You palette", dynamicColor) {
                    dynamicColor = it; prefs.edit().putBoolean("dynamic_color", it).apply()
                }
            }
        }
        Text("FoxFW Companion 2.1 · dark by design", color = Fox.faint, fontFamily = FoxMono,
            fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))

        if (toast.isNotEmpty()) {
            LaunchedEffect(toast) { kotlinx.coroutines.delay(1800); toast = "" }
            Text(toast, color = Fox.live, fontFamily = FoxMono, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }

    confirm?.let { (title, msg, action) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title, color = Fox.fg) },
            text = { Text(msg, color = Fox.muted) },
            containerColor = Fox.surface,
            confirmButton = { Text(title, color = Color(0xFFFF5C78), fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { action(); confirm = null }.padding(8.dp)) },
            dismissButton = { Text("Cancel", color = Fox.muted, modifier = Modifier.clickable { confirm = null }.padding(8.dp)) },
        )
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Fox.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(v, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
    }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Fox.amber).clickable {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        onClick()
    }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Fox.amberInk, fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private fun prettify(k: String): String = k.replace('_', ' ').split(' ')
    .joinToString(" ") { w -> w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() } }

/* ------------------------------- shared ------------------------------- */
@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Fox.line))

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Surface(modifier = modifier, shape = RoundedCornerShape(11.dp), color = Fox.surface, border = BorderStroke(1.dp, Fox.line)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 13.dp)) {
            Text(value, color = if (accent) Fox.amber else Fox.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text(label.uppercase(), color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun DecodeRow(e: DecodeEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ProtocolBadge(e.proto)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(e.meta, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.rssi, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(7.dp))
                SignalBars(e.bars)
            }
            Spacer(Modifier.height(6.dp))
            Text(e.ago, color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
        }
    }
}

@Composable
private fun Waveform() {
    val bars = remember { List(34) { (12 + (it * 37 % 80)) } }
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        bars.forEach { h ->
            Box(Modifier.weight(1f).height((h * 0.4f).dp).clip(RoundedCornerShape(1.dp)).background(Fox.gradient()))
        }
    }
}

@Composable
private fun SettingToggle(key: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(key, color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(sub, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
            }
        }
        FoxSwitch(checked, onChange)
    }
}

@Composable
private fun FoxSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val thumbX by androidx.compose.animation.core.animateDpAsState(
        if (checked) 19.dp else 0.dp,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "thumb",
    )
    val track by androidx.compose.animation.animateColorAsState(
        if (checked) Fox.amber else Fox.surface2,
        androidx.compose.animation.core.tween(180), label = "track",
    )
    val edge by androidx.compose.animation.animateColorAsState(
        if (checked) Fox.amber else Fox.line2,
        androidx.compose.animation.core.tween(180), label = "edge",
    )
    Box(
        Modifier.width(42.dp).height(25.dp).clip(RoundedCornerShape(999.dp))
            .background(track).border(1.dp, edge, RoundedCornerShape(999.dp))
            .clickable {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onChange(!checked)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.padding(horizontal = 2.dp).offset(x = thumbX).size(19.dp)
            .clip(RoundedCornerShape(999.dp)).background(Color.White))
    }
}
