package com.foxfw.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
 * SubGHz Viewer / Trimmer — native port of FoxLAB's SubGHz viewer. Lists the .sub RAW captures on the
 * connected Flipper (/ext/subghz), renders the pulse waveform, lets you drag-select a span and trim to
 * it, then save the trimmed capture to the phone or back to the Flipper. Mirrors the web tool: signed
 * microsecond durations (+ = mark/high, − = space/low), RAW_Data reconstruction.
 */
private data class SubParsed(val meta: Map<String, String>, val durations: IntArray)

private fun parseSubRaw(text: String): SubParsed {
    val meta = LinkedHashMap<String, String>()
    val durs = ArrayList<Int>()
    for (raw in text.split("\n")) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("RAW_Data:")) {
            line.substring(9).trim().split(Regex("\\s+")).forEach { t -> t.toIntOrNull()?.let { durs.add(it) } }
        } else {
            val idx = line.indexOf(':')
            if (idx > 0) meta[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
    }
    return SubParsed(meta, durs.toIntArray())
}

private fun buildSubText(meta: Map<String, String>, durations: IntArray): String = listOf(
    "Filetype: Flipper SubGhz RAW File",
    "Version: 1",
    "Frequency: " + (meta["Frequency"] ?: "433920000"),
    "Preset: " + (meta["Preset"] ?: "FuriHalSubGhzPresetOok650Async"),
    "Protocol: RAW",
    "RAW_Data: " + durations.joinToString(" "),
).joinToString("\n") + "\n"

@Composable
fun SubGhzViewerScreen(fle: FlipperBle, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = fle.conn == Conn.CONNECTED

    val files = remember { mutableStateListOf<Pair<String, String>>() } // label -> full path
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (connected) "Loading .sub captures from /ext/subghz…" else "Connect the Flipper (Device tab) first.") }

    var meta by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var durations by remember { mutableStateOf(IntArray(0)) }
    var original by remember { mutableStateOf(IntArray(0)) }   // the capture as loaded, for Reset to restore after a trim
    var sourceName by remember { mutableStateOf("") }
    var viewStart by remember { mutableStateOf(0) }
    var viewEnd by remember { mutableStateOf(0) }
    // drag selection, as fractions (0..1) of the canvas width
    var selA by remember { mutableStateOf<Float?>(null) }
    var selB by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        if (loading || !connected) { if (!connected) status = "Connect the Flipper first."; return }
        loading = true; files.clear(); status = "Scanning /ext/subghz…"
        scope.launch {
            val root = "/ext/subghz"
            val top = fle.storageList(root) ?: emptyList()
            top.filter { !it.isDir && it.name.endsWith(".sub", true) }.forEach { files.add(it.name to "$root/${it.name}") }
            for (d in top.filter { it.isDir }) {
                val sub = fle.storageList("$root/${d.name}") ?: continue
                sub.filter { !it.isDir && it.name.endsWith(".sub", true) }.forEach { files.add("${d.name}/${it.name}" to "$root/${d.name}/${it.name}") }
            }
            loading = false
            status = if (files.isEmpty()) "No .sub files found in /ext/subghz." else "${files.size} capture(s). Tap one to view."
        }
    }
    LaunchedEffect(connected) { if (connected && files.isEmpty()) reload() }

    // Accept a RAW .sub handed over from the Decode page (Trim →): load it straight into the editor,
    // no Flipper file needed.
    LaunchedEffect(Unit) {
        SubTrimHandoff.text?.let { t ->
            val nm = (SubTrimHandoff.name ?: "capture").removeSuffix(".sub")
            SubTrimHandoff.text = null; SubTrimHandoff.name = null
            val parsed = parseSubRaw(t)
            meta = parsed.meta; durations = parsed.durations; original = parsed.durations; sourceName = nm
            viewStart = 0; viewEnd = parsed.durations.size; selA = null; selB = null
            status = if (parsed.durations.isEmpty()) "No RAW pulse data in the handed-over capture." else "Loaded $nm from Decode — drag to select, then Set trim."
        }
    }

    fun openFile(label: String, path: String) {
        busy = true; status = "Reading $label…"
        scope.launch {
            val bytes = fle.storageRead(path)
            busy = false
            if (bytes == null) { status = "Couldn't read $label."; return@launch }
            val parsed = parseSubRaw(bytes.toString(Charsets.UTF_8))
            meta = parsed.meta; durations = parsed.durations; original = parsed.durations; sourceName = label.substringAfterLast('/')
            viewStart = 0; viewEnd = parsed.durations.size; selA = null; selB = null
            status = if (parsed.durations.isEmpty()) "No RAW pulse data in $label." else "Loaded $label."
        }
    }

    fun visible(): IntArray = durations.copyOfRange(viewStart.coerceIn(0, durations.size), viewEnd.coerceIn(0, durations.size))

    // Destructive trim: replace the capture with just the selected span and redisplay it (what the user
    // expects — "delete everything outside the selection"). The drag fractions are relative to the
    // waveform as drawn, which renders visible(); map them onto that same array, slice, and make the slice
    // the new durations. Reset restores the originally-loaded capture.
    fun setTrimFromSelection() {
        val a = selA; val b = selB
        if (a == null || b == null || durations.isEmpty()) return
        val s = minOf(a, b); val e = maxOf(a, b)
        val vis = visible()
        if (vis.isEmpty()) return
        val total = vis.sumOf { kotlin.math.abs(it).toLong() }.coerceAtLeast(1)
        var acc = 0L; var startIdx = 0; var endIdx = vis.size; var startSet = false
        for (i in vis.indices) {
            val fracStart = acc.toFloat() / total
            acc += kotlin.math.abs(vis[i]).toLong()
            val fracEnd = acc.toFloat() / total
            if (!startSet && fracEnd > s) { startIdx = i; startSet = true }  // first pulse whose span crosses the start
            if (fracStart < e) endIdx = i + 1                                 // last pulse that begins before the end
        }
        if (endIdx <= startIdx) { selA = null; selB = null; status = "Selection too small — drag a wider span."; return }
        durations = vis.copyOfRange(startIdx, endIdx)
        viewStart = 0; viewEnd = durations.size; selA = null; selB = null
        status = "Trimmed to ${durations.size} pulses — Save to keep it, or Reset to undo."
    }

    fun outName() = (sourceName.ifBlank { "trimmed" }).replace(Regex("\\.(sub|ir)$", RegexOption.IGNORE_CASE), "") + "_trimmed.sub"

    fun saveToPhone() {
        if (durations.isEmpty()) return
        val text = buildSubText(meta, visible()); val fname = outName()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: run { status = "Couldn't create the file in Downloads."; return }
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                status = "Saved $fname to your Downloads folder."
            } else {
                val f = java.io.File(ctx.getExternalFilesDir(null), fname); f.writeText(text)
                status = "Saved to ${f.absolutePath}"
            }
        } catch (e: Exception) { status = "Save to phone failed: ${e.message}" }
    }

    fun saveToFlipper() {
        if (!connected) { status = "Connect the Flipper first."; return }
        if (durations.isEmpty()) return
        busy = true; status = "Saving to the Flipper…"
        scope.launch {
            try {
                val base = outName().removeSuffix(".sub")
                var file = "$base.sub"; var n = 2
                while (fle.storageStat("/ext/subghz/$file")) { file = "$base($n).sub"; n++ }
                val ok = fle.storageWrite("/ext/subghz/$file", buildSubText(meta, visible()).toByteArray()) { s, t -> status = "Saving… ($s/$t)" }
                status = if (ok) "Saved /ext/subghz/$file." else "Save failed — is the Flipper on its desktop?"
                if (ok) reload()
            } catch (e: Exception) { status = "Save failed: ${e.message}" } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SubGHz Viewer / Trimmer", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow("RAW .sub · VIEW · TRIM")
            }
            Box(Modifier.width(40.dp).height(34.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Fox.line, RoundedCornerShape(9.dp))
                .clickable { reload() }, contentAlignment = Alignment.Center) {
                Text("⟳", color = Fox.amber, fontSize = 16.sp)
            }
        }

        // waveform
        if (durations.isNotEmpty()) FoxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("WAVEFORM"); Spacer(Modifier.weight(1f))
                    if (original.isNotEmpty() && durations.size != original.size)
                        Text("reset", color = Fox.muted, fontSize = 10.5.sp, modifier = Modifier.clickable { durations = original; viewStart = 0; viewEnd = original.size; selA = null; selB = null })
                }
                val vis = visible()
                Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF080808))
                    .pointerInput(durations, viewStart, viewEnd) {
                        val wpx = size.width.toFloat().coerceAtLeast(1f)
                        detectDragGestures(
                            onDragStart = { off -> val f = (off.x / wpx).coerceIn(0f, 1f); selA = f; selB = f },
                            onDrag = { change, _ -> selB = (change.position.x / wpx).coerceIn(0f, 1f) },
                            onDragEnd = { },
                        )
                    }) {
                    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                        val w = size.width; val h = size.height
                        val total = vis.sumOf { kotlin.math.abs(it).toLong() }.coerceAtLeast(1)
                        val midY = h / 2f; val amp = h * 0.32f
                        val path = Path()
                        var x = 0f; var y = midY - amp
                        path.moveTo(0f, y)
                        for (d in vis) {
                            val segW = (kotlin.math.abs(d).toFloat() / total) * w
                            path.lineTo(x + segW, y)
                            val newY = if (d >= 0) midY - amp else midY + amp
                            path.lineTo(x + segW, newY)
                            x += segW; y = newY
                        }
                        drawPath(path, Color(0xFFE8750A), style = Stroke(width = 2f))
                        val a = selA; val b = selB
                        if (a != null && b != null) {
                            val s = minOf(a, b) * w; val e = maxOf(a, b) * w
                            drawRect(Fox.amber.copy(alpha = 0.18f), Offset(s, 0f), Size(e - s, h))
                        }
                    }
                }
                val totalMs = visible().sumOf { kotlin.math.abs(it).toLong() } / 1000.0
                val freq = meta["Frequency"]?.toLongOrNull()?.let { "  ·  %.3f MHz".format(it / 1e6) } ?: ""
                Text("${visible().size} pulses  ·  %.1f ms$freq".format(totalMs), color = Fox.muted, fontFamily = FoxMono, fontSize = 10.5.sp)
                Text("Drag across the waveform to select a span, then Set trim.", color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MBtn("Set trim", Modifier.weight(1f), accent = selA != null && selB != null) { setTrimFromSelection() }
                    MBtn("Save to Phone", Modifier.weight(1f)) { saveToPhone() }
                    MBtn(if (busy) "…" else "Save to Flipper", Modifier.weight(1f), accent = connected && !busy) { if (!busy) saveToFlipper() }
                }
            }
        }

        Text(status, color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)

        // file list
        FoxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("CAPTURES"); Spacer(Modifier.weight(1f))
                    Pill(if (connected) "FLPR" else "offline", if (connected) Fox.live else Fox.muted, if (connected) Fox.liveDim else Fox.line)
                }
                Spacer(Modifier.height(4.dp))
                if (files.isEmpty()) Text(if (loading) "Loading…" else "No .sub files — capture something on the Flipper first.", color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp)
                else Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    files.forEach { (label, path) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { openFile(label, path) }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = if (label.substringAfterLast('/') == sourceName) Fox.amber else Fox.fg, fontFamily = FoxMono, fontSize = 11.5.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("view →", color = Fox.amber, fontFamily = FoxMono, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
