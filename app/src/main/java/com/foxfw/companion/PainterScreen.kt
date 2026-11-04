package com.foxfw.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.launch

/**
 * FoxFW Wallpaper Painter — native port of FoxLAB's wallpaper_painter.html. A 128×64 1bpp canvas with
 * the locked status-bar/border bands the device overdraws, pencil/eraser/line/fill tools, undo/redo,
 * and save: either copy the .xbm to the clipboard, or write it straight to /ext/wallpapers on the
 * connected Flipper and mark it active (same path + .activate marker the web tool uses).
 */
private const val PW = 128
private const val PH = 64
private const val PROW = 16
private const val PSIZE = 1024
private const val TOP_BAND = 11
private const val SIDE_BAND = 3
private const val BOTTOM_BAND = 3

private fun pLocked(x: Int, y: Int) = y < TOP_BAND || y >= PH - BOTTOM_BAND || x < SIDE_BAND || x >= PW - SIDE_BAND
private fun pGet(a: ByteArray, x: Int, y: Int) = (a[y * PROW + (x shr 3)].toInt() shr (x and 7)) and 1
private fun pSet(a: ByteArray, x: Int, y: Int, v: Int) {
    val idx = y * PROW + (x shr 3); val bit = 1 shl (x and 7)
    if (v != 0) a[idx] = (a[idx].toInt() or bit).toByte() else a[idx] = (a[idx].toInt() and bit.inv()).toByte()
}

private fun sanitizeName(raw: String) = raw.trim().replace(Regex("[\\\\/:*?\"<>|]+"), "").take(40).ifBlank { "MyWallpaper" }

/** The exact XBM text FoxFW ships/reads; locked bands are forced blank (the device draws its own chrome). */
private fun toXbmText(user: ByteArray): String {
    val bits = ByteArray(PSIZE)
    for (y in 0 until PH) for (x in 0 until PW) {
        if (!pLocked(x, y) && pGet(user, x, y) == 1) pSet(bits, x, y, 1)
    }
    val sb = StringBuilder("#define wallpaper_width 128\n#define wallpaper_height 64\nstatic unsigned char wallpaper_bits[] = {\n")
    var i = 0
    while (i < bits.size) {
        val end = minOf(i + 12, bits.size)
        sb.append("   ")
        for (j in i until end) {
            sb.append("0x").append((bits[j].toInt() and 0xff).toString(16).padStart(2, '0'))
            if (j < end - 1) sb.append(", ") else sb.append(",")
        }
        sb.append("\n"); i = end
    }
    sb.append("};\n")
    return sb.toString()
}

/** Parse a 128×64 1-bpp image (.xbm header text, or monochrome .bmp) into the painter's 1024-byte
 *  XBM buffer. Returns the buffer, or null if it isn't a 128×64 monochrome image we can read. */
private fun parseMonoImage(bytes: ByteArray): ByteArray? {
    val out = ByteArray(PSIZE)
    // XBM: C header with "_width 128", "_height 64", "_bits[] = { 0x.., ... }".
    val text = String(bytes, Charsets.ISO_8859_1)
    if (text.contains("_bits")) {
        val w = Regex("_width\\s+(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val h = Regex("_height\\s+(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (w != PW || h != PH) return null
        val hex = Regex("0[xX]([0-9a-fA-F]{1,2})").findAll(text).map { it.groupValues[1].toInt(16) }.toList()
        if (hex.size < PSIZE) return null
        for (i in 0 until PSIZE) out[i] = hex[i].toByte()
        return out
    }
    // Monochrome (1-bpp) BMP.
    if (bytes.size > 62 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
        fun le16(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8)
        fun le32(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8) or
            ((bytes[o + 2].toInt() and 0xff) shl 16) or ((bytes[o + 3].toInt() and 0xff) shl 24)
        val dataOff = le32(10)
        val w = le32(18); val hRaw = le32(22); val bpp = le16(28)
        val h = kotlin.math.abs(hRaw)
        if (w != PW || h != PH || bpp != 1) return null
        val topDown = hRaw < 0
        // Palette entry 0 (BGRA) at offset 54 → decide which bit value is the dark pixel.
        val lum0 = (bytes[54].toInt() and 0xff) + (bytes[55].toInt() and 0xff) + (bytes[56].toInt() and 0xff)
        val oneIsDark = lum0 > 384 // index 0 light ⇒ a set bit (index 1) is the dark pixel
        val rowBytes = ((w + 31) / 32) * 4 // 1-bpp rows padded to 4 bytes
        for (ry in 0 until PH) {
            val srcRow = if (topDown) ry else (PH - 1 - ry) // BMP rows are bottom-up
            val base = dataOff + srcRow * rowBytes
            if (base + rowBytes > bytes.size) return null
            for (x in 0 until PW) {
                val bit = (bytes[base + (x shr 3)].toInt() shr (7 - (x and 7))) and 1 // BMP is MSB-first
                val dark = if (oneIsDark) bit == 1 else bit == 0
                if (dark) { val idx = ry * PROW + (x shr 3); out[idx] = (out[idx].toInt() or (1 shl (x and 7))).toByte() }
            }
        }
        return out
    }
    return null
}

@Composable
fun PainterScreen(fle: FlipperBle, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = fle.conn == Conn.CONNECTED

    val user = remember { PainterState.user }           // persists across navigation (don't lose art)
    var rev by remember { mutableStateOf(0) }           // bump to force a redraw after edits
    var tool by remember { mutableStateOf("pencil") }   // pencil | eraser | line | fill
    var name by remember { mutableStateOf(PainterState.name) }
    var status by remember { mutableStateOf("Draw in the open area — the grey bands are where the device draws its own status bar + border.") }
    var busy by remember { mutableStateOf(false) }

    val undo = remember { ArrayDeque<ByteArray>() }
    val redo = remember { ArrayDeque<ByteArray>() }
    var histRev by remember { mutableStateOf(0) }       // bump when undo/redo stacks change
    fun pushUndo() { undo.addLast(user.copyOf()); if (undo.size > 50) undo.removeFirst(); redo.clear(); histRev++ }
    fun restore(from: ByteArray) { System.arraycopy(from, 0, user, 0, PSIZE); rev++ }

    // line-tool preview (pixel coords), null when not dragging a line
    var lineFrom by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var lineTo by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun paintAt(x: Int, y: Int, v: Int): Boolean {
        if (x < 0 || x >= PW || y < 0 || y >= PH || pLocked(x, y)) return false
        if (pGet(user, x, y) == v) return false
        pSet(user, x, y, v); return true
    }
    fun lineBits(x0: Int, y0: Int, x1: Int, y1: Int, v: Int): Boolean {
        var cx = x0; var cy = y0; var changed = false
        val dx = kotlin.math.abs(x1 - x0); val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            if (paintAt(cx, cy, v)) changed = true
            if (cx == x1 && cy == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; cx += sx }
            if (e2 <= dx) { err += dx; cy += sy }
        }
        return changed
    }
    fun floodFill(sx: Int, sy: Int, v: Int): Boolean {
        if (sx < 0 || sx >= PW || sy < 0 || sy >= PH || pLocked(sx, sy)) return false
        val start = pGet(user, sx, sy); if (start == v) return false
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.addLast(sx to sy); var changed = false
        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeLast()
            if (x < 0 || x >= PW || y < 0 || y >= PH || pLocked(x, y)) continue
            if (pGet(user, x, y) != start) continue
            pSet(user, x, y, v); changed = true
            stack.addLast(x + 1 to y); stack.addLast(x - 1 to y); stack.addLast(x to y + 1); stack.addLast(x to y - 1)
        }
        return changed
    }

    fun offsetToPixel(off: Offset, size: Size): Pair<Int, Int> {
        val px = (off.x / (size.width / PW)).toInt()
        val py = (off.y / (size.height / PH)).toInt()
        return px.coerceIn(0, PW - 1) to py.coerceIn(0, PH - 1)
    }

    fun saveToFlipper() {
        if (!connected) { status = "Connect the Flipper (Device tab) first."; return }
        busy = true; status = "Saving to the Flipper…"
        scope.launch {
            try {
                fle.storageMkdir("/ext/wallpapers")
                val base = sanitizeName(name)
                var file = "$base.xbm"; var n = 2
                while (fle.storageStat("/ext/wallpapers/$file")) { file = "$base($n).xbm"; n++ }
                val data = toXbmText(user).toByteArray()
                val ok = fle.storageWrite("/ext/wallpapers/$file", data) { sent, total ->
                    status = "Saving… ($sent/$total)"
                }
                if (ok) {
                    fle.storageWrite("/ext/wallpapers/.activate", file.toByteArray(), null)
                    status = "Saved wallpapers/$file and set active — shows next time the Flipper returns to its desktop."
                } else status = "Save failed — is the Flipper on its desktop (loader free)?"
            } catch (e: Exception) {
                status = "Save failed: ${e.message}"
            } finally { busy = false }
        }
    }
    fun saveToPhone() {
        val fname = sanitizeName(name) + ".xbm"
        val xbm = toXbmText(user)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // Scoped storage: drop it straight into the public Downloads folder.
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: run { status = "Couldn't create the file in Downloads."; return }
                resolver.openOutputStream(uri)?.use { it.write(xbm.toByteArray()) }
                values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                status = "Saved $fname to your Downloads folder."
            } else {
                val f = java.io.File(ctx.getExternalFilesDir(null), fname)
                f.writeText(xbm)
                status = "Saved to ${f.absolutePath}"
            }
        } catch (e: Exception) { status = "Save to phone failed: ${e.message}" }
    }

    // Persist the name, and live-mirror the whiteboard to the Flipper while the painter is open.
    androidx.compose.runtime.LaunchedEffect(name) { PainterState.name = name }
    // Keyed on `connected` so it (re)launches the moment the link is up — not only on first compose,
    // which is why the very first open used to leave the Flipper blank. After launching, push the
    // frame on a heartbeat: unconditionally through a warm-up window (the FAP needs ~1.5s to boot its
    // read-loop, so a single immediate push is lost), then only when the whiteboard actually changes.
    androidx.compose.runtime.LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        PainterLink.start(fle, user.copyOf())
        var last = user.copyOf()                                        // start() already pushed this
        var up = true                                                   // FAP believed resident
        var warmUntil = android.os.SystemClock.elapsedRealtime() + 4000
        var nextStat = android.os.SystemClock.elapsedRealtime() + 800
        while (true) {
            val now = android.os.SystemClock.elapsedRealtime()
            // Learn if the user closed it on the Flipper (Back). Poll ONLY while we think it's up — once
            // closed we go silent (no RPC, no relaunch) until they actually draw again.
            if (up && now >= nextStat) {
                if (PainterLink.isGone(fle)) up = false
                nextStat = now + 800
            }
            val frame = user.copyOf()
            val changed = !frame.contentEquals(last)
            if (changed && !up) {
                // They drew after closing it on the Flipper — reopening is intentional, so the wheel is OK here.
                PainterLink.start(fle, frame); up = true; warmUntil = now + 4000; last = frame
            } else if (now < warmUntil || changed) {
                PainterLink.push(fle, frame); last = frame              // just the new frame — no relaunch, no wheel
            }
            kotlinx.coroutines.delay(300)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { PainterLink.stopAsync(fle) } }

    // Open a 128×64 .xbm / monochrome .bmp onto the whiteboard.
    val openImgLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) { status = "Couldn't read that file."; return@launch }
            val parsed = parseMonoImage(bytes)
            if (parsed == null) status = "The image needs to be 128px × 64px — .xbm or monochrome .bmp."
            else { pushUndo(); System.arraycopy(parsed, 0, user, 0, PSIZE); rev++; status = "Loaded image onto the whiteboard." }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Painter", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow("WALLPAPER · 128×64 · .xbm")
            }
            Pill(if (connected) "FLPR" else "offline", if (connected) Fox.live else Fox.muted,
                if (connected) Fox.liveDim else Fox.line)
        }

        // Canvas
        FoxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(PW.toFloat() / PH).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0C0D10))) {
                    Canvas(
                        Modifier.fillMaxWidth().aspectRatio(PW.toFloat() / PH)
                            .pointerInput(tool) {
                                val cs = size.toSize()
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    var (lx, ly) = offsetToPixel(down.position, cs)
                                    when (tool) {
                                        "fill" -> { pushUndo(); if (floodFill(lx, ly, 1)) rev++ else { undo.removeLastOrNull(); histRev++ } }
                                        "line" -> { lineFrom = lx to ly; lineTo = lx to ly }
                                        else -> { pushUndo(); val v = if (tool == "eraser") 0 else 1; if (paintAt(lx, ly, v)) rev++ }
                                    }
                                    down.consume()
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        if (!ch.pressed) break
                                        val (px2, py2) = offsetToPixel(ch.position, cs)
                                        when (tool) {
                                            "line" -> { lineTo = px2 to py2 }
                                            "fill" -> {}
                                            else -> { val v = if (tool == "eraser") 0 else 1; if (lineBits(lx, ly, px2, py2, v)) rev++; lx = px2; ly = py2 }
                                        }
                                        ch.consume()
                                    }
                                    if (tool == "line") {
                                        val f = lineFrom; val t = lineTo
                                        if (f != null && t != null) { pushUndo(); if (lineBits(f.first, f.second, t.first, t.second, 1)) rev++ else { undo.removeLastOrNull(); histRev++ } }
                                        lineFrom = null; lineTo = null
                                    }
                                }
                            },
                    ) {
                        rev.let {} // read the revision so the draw re-runs after every edit
                        val cw = size.width / PW; val chh = size.height / PH
                        for (y in 0 until PH) for (x in 0 until PW) {
                            val col = when {
                                pLocked(x, y) -> Color(0xFF7D818A)
                                pGet(user, x, y) == 1 -> Color(0xFF0C0D10)
                                else -> Color(0xFFECEEF1)
                            }
                            drawRect(col, Offset(x * cw, y * chh), Size(cw, chh))
                        }
                        val f = lineFrom; val t = lineTo
                        if (f != null && t != null) {
                            drawLine(Fox.amber.copy(alpha = 0.9f), Offset((f.first + 0.5f) * cw, (f.second + 0.5f) * chh),
                                Offset((t.first + 0.5f) * cw, (t.second + 0.5f) * chh), strokeWidth = maxOf(1f, cw / 2))
                        }
                    }
                }
                // toolbar
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val _h = histRev // read so undo/redo enablement recomposes
                    PTool("✏", tool == "pencil") { tool = "pencil" }
                    PTool("▱", tool == "eraser") { tool = "eraser" }
                    PTool("╱", tool == "line") { tool = "line" }
                    PTool("▣", tool == "fill") { tool = "fill" }
                    Spacer(Modifier.weight(1f))
                    PTool("📂", false) { openImgLauncher.launch(arrayOf("*/*")) }
                    PTool("↶", false, enabled = undo.isNotEmpty() && _h >= 0) {
                        if (undo.isNotEmpty()) { redo.addLast(user.copyOf()); restore(undo.removeLast()); histRev++ }
                    }
                    PTool("↷", false, enabled = redo.isNotEmpty()) {
                        if (redo.isNotEmpty()) { undo.addLast(user.copyOf()); restore(redo.removeLast()); histRev++ }
                    }
                    PTool("🗑", false) {
                        if (user.any { it.toInt() != 0 }) { pushUndo(); user.fill(0); rev++ }
                    }
                }
            }
        }

        Text(status, color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)

        // Saving
        FoxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Eyebrow("SAVE")
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    label = { Text("Wallpaper name", fontSize = 11.sp) },
                    textStyle = TextStyle(fontFamily = FoxMono, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MBtn("Save to Phone", Modifier.weight(1f)) { saveToPhone() }
                    MBtn(if (busy) "Saving…" else "Save to Flipper", Modifier.weight(1f), accent = connected && !busy) {
                        if (!busy) saveToFlipper()
                    }
                }
                Text("Save to Phone drops the .xbm in your Downloads. Save to Flipper writes it into /ext/wallpapers and sets it active (needs FoxFW v2.0.4+). Turn it on under Fox Settings → Custom Wallpaper if it isn't already.",
                    color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
private fun PTool(glyph: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.width(40.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (active) Fox.amber else Fox.line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = if (!enabled) Fox.faint else if (active) Fox.amber else Fox.fg, fontSize = 15.sp) }
}

/** Placeholder for FoxLAB tools not yet ported natively, so their tiles work while they're built. */
@Composable
fun FoxLabComingSoon(title: String, note: String, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        FoxCard { Text(note, color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}
