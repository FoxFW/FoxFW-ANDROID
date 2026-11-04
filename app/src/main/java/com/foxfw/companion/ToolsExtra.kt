package com.foxfw.companion

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- shared little widgets ----------
@Composable
private fun ToolHeader(title: String, sub: String, busy: Boolean, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column { Text(title, color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Eyebrow(sub) }
        Spacer(Modifier.weight(1f))
        Pill(if (busy) "Busy" else "Ready", if (busy) Fox.amber else Fox.live, if (busy) Fox.amberDim else Fox.liveDim, dot = true)
    }
}

@Composable
private fun TxButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(11.dp))
        .background(if (enabled) Fox.surface2 else Fox.surface2.copy(alpha = 0.4f))
        .border(1.dp, Fox.line, RoundedCornerShape(11.dp))
        .clickable(enabled = enabled) { onClick() }.padding(vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Fox.fg else Fox.faint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun savedSubs(ctx: android.content.Context): List<File> =
    ctx.getExternalFilesDir(null)?.listFiles { f -> f.isFile && f.name.endsWith(".sub", true) }?.sortedBy { it.name } ?: emptyList()

private fun subFreqHz(text: String): Int =
    text.lineSequence().firstOrNull { it.startsWith("Frequency:") }?.substringAfter(":")?.trim()?.toIntOrNull() ?: 433920000

// ================= KeeLoq Crack (decode a .sub via ArkeEngine) =================
@Composable
fun KeeLoqCrackScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sub by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (ArkeEngine.available) "Paste a Flipper .sub (or load a saved one) and Crack." else "Decoder engine not started.") }
    var frames by remember { mutableStateOf<List<ArkeEngine.Frame>>(emptyList()) }
    val connected = FlipperRf.connected
    var forgeBtn by remember { mutableStateOf(-1) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 2.dp, 16.dp, 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolHeader("KeeLoq Crack", "DECODE / RECOVER — ${ArkeEngine.decoderCount()} decoders", busy, onClose)
        FoxCard { Column {
            Eyebrow("Capture (.sub)"); Spacer(Modifier.height(6.dp))
            OutlinedTextField(sub, { sub = it }, Modifier.fillMaxWidth().heightIn(min = 90.dp),
                placeholder = { Text("Filetype: Flipper SubGhz RAW/Key File …", color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp) })
        } }
        val subs = savedSubs(ctx)
        if (subs.isNotEmpty()) FoxCard { Column {
            Eyebrow("Saved .sub — ${subs.size}"); Spacer(Modifier.height(4.dp))
            subs.take(10).forEach { f ->
                Row(Modifier.fillMaxWidth().clickable { sub = f.readText(); status = "Loaded ${f.name}." }.padding(vertical = 5.dp)) {
                    Text(f.name, color = Fox.fg, fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("load", color = Fox.amber, fontFamily = FoxMono, fontSize = 10.sp)
                }
            }
        } }
        TxButton(if (busy) "Cracking…" else "Crack / Decode", !busy && ArkeEngine.available && sub.isNotBlank()) {
            busy = true; status = "Decoding…"; frames = emptyList()
            scope.launch {
                val (fr, err) = withContext(Dispatchers.Default) { ArkeEngine.decode(sub) }
                frames = fr
                status = err ?: if (fr.isEmpty()) "No protocol matched." else "${fr.size} frame(s) decoded."
                busy = false
            }
        }
        frames.forEach { f ->
            FoxCard { Column {
                Text(f.make, color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                f.serialHex?.let { KvLine("serial", it) }
                f.cnt?.let { KvLine("counter", it.toString()) }
                f.btnName?.let { KvLine("button", it) } ?: f.btn?.let { KvLine("button", it.toString()) }
                f.next?.let { KvLine("next code", it) }
                f.gate?.let { KvLine("gate", it) }
                f.crcOk?.let { KvLine("crc", if (it) "ok" else "bad") }
            } }
        }
        if (sub.isNotBlank()) FoxCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Forge next rolling code & transmit")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                GenChip("Same btn", forgeBtn < 0) { forgeBtn = -1 }
                GenChip("0x1", forgeBtn == 1) { forgeBtn = 1 }
                GenChip("0x2", forgeBtn == 2) { forgeBtn = 2 }
                GenChip("0x4", forgeBtn == 4) { forgeBtn = 4 }
                GenChip("0x8", forgeBtn == 8) { forgeBtn = 8 }
            }
            Eyebrow("TX radio")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                GenChip("Int", FlipperRf.txDevice == FlipperRf.DEV_INT) { FlipperRf.txDevice = FlipperRf.DEV_INT }
                GenChip("Ext", FlipperRf.txDevice == FlipperRf.DEV_EXT) { FlipperRf.txDevice = FlipperRf.DEV_EXT }
                GenChip("Auto", FlipperRf.txDevice == FlipperRf.DEV_AUTO) { FlipperRf.txDevice = FlipperRf.DEV_AUTO }
            }
            TxButton(if (busy) "Working…" else "⚡ Forge next code & TX", !busy && connected && sub.isNotBlank()) {
                busy = true; status = "Forging next code…"
                scope.launch {
                    val f = withContext(Dispatchers.Default) { ArkeEngine.Keeloq.forge(sub, forgeBtn, -1) }
                    if (f.ok && f.sub != null) {
                        val r = FlipperRf.tx(f.sub, f.freqHz, FlipperRf.txDevice, FlipperRf.AM650)
                        status = if (r.ok) "⚡ Sent next code · ${f.mf} · btn 0x${f.btn.toString(16)} · cnt ${f.cnt}" else "TX failed: ${r.label}"
                    } else status = "Forge: ${f.err}"
                    busy = false
                }
            }
            Text(if (connected) "Recovers the device key from the 204-key store, encrypts counter+1, and transmits the next valid code via the Flipper."
                 else "Connect the Flipper (Device tab) to transmit.",
                color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, lineHeight = 13.sp)
        } }
        FoxCard { Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp) }
    }
}

@Composable
private fun KvLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(k, color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.width(72.dp))
        Text(v, color = Fox.fg, fontFamily = FoxMono, fontSize = 11.sp)
    }
}

// ================= Signal Gen (replay/TX a saved .sub via the Flipper) =================
@Composable
fun SignalGenScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val connected = FoxApplication.instance.ble.conn == Conn.CONNECTED
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (connected) "Replay a capture, or generate a gate attack." else "Connect the Flipper (Device tab) to transmit.") }

    // --- generator state (fixed-code gate protocols via the bridged barriers engine) ---
    val protos = remember { ArkeEngine.Barriers.protocols() }
    var proto by remember { mutableStateOf(protos.firstOrNull()) }
    var pickOpen by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("debruijn") }
    var codeTxt by remember { mutableStateOf("") }
    var freqIdx by remember { mutableStateOf(0) }
    var presetSel by remember { mutableStateOf(FlipperRf.AM650) }
    val freqs = listOf("433.92" to 433920000, "315" to 315000000, "868.35" to 868350000, "915" to 915000000)
    val presets = listOf("AM650" to FlipperRf.AM650, "AM270" to FlipperRf.AM270, "FM238" to FlipperRf.FM238, "FM476" to FlipperRf.FM476)

    fun generateTx() {
        val pr = proto ?: return
        if (!connected) { status = "Connect the Flipper first."; return }
        busy = true; status = "Generating ${pr.name} ($mode)…"
        scope.launch {
            val freq = freqs[freqIdx].second
            val sub = withContext(Dispatchers.Default) {
                when (mode) {
                    "brute" -> ArkeEngine.Barriers.bruteSub(pr.name, freq)
                    "single" -> ArkeEngine.Barriers.oneSub(pr.name, codeTxt.trim().removePrefix("0x").toIntOrNull(16) ?: codeTxt.trim().toIntOrNull() ?: 0, freq)
                    else -> ArkeEngine.Barriers.debruijnSub(pr.name, freq)
                }
            }
            if (sub == null) { status = "✗ ${pr.name}: can't $mode this one (code space too large — use Single)."; busy = false; return@launch }
            status = "Transmitting ${pr.name} ($mode, ${sub.length / 1024} KB)…"
            val r = FlipperRf.tx(sub, freq, FlipperRf.txDevice, presetSel)
            status = if (r.ok) "✓ Sent ${pr.name} ($mode)." else "✗ TX ${r.label}."
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 2.dp, 16.dp, 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolHeader("Signal Gen", "REPLAY · GENERATE · TX", busy, onClose)

        FoxCard { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Eyebrow("Gate / fixed-code generator")
            Row(Modifier.fillMaxWidth().clickable { pickOpen = !pickOpen }, verticalAlignment = Alignment.CenterVertically) {
                Text(proto?.let { "${it.name} · ${it.bits}-bit · ${it.codes} codes" } ?: "no gate protocols", color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(if (pickOpen) "▲" else "▼", color = Fox.amber, fontSize = 12.sp)
            }
            if (pickOpen) Column(Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
                protos.forEach { pr ->
                    Text("${pr.name}  ·  ${pr.codes} codes", color = if (pr == proto) Fox.amber else Fox.muted,
                        fontFamily = FoxMono, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().clickable { proto = pr; pickOpen = false }.padding(vertical = 6.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                GenChip("De Bruijn", mode == "debruijn") { mode = "debruijn" }
                GenChip("Brute", mode == "brute") { mode = "brute" }
                GenChip("Single", mode == "single") { mode = "single" }
            }
            if (mode == "single") OutlinedTextField(codeTxt, { codeTxt = it }, singleLine = true,
                label = { Text("code (hex 0x.. or decimal)", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth())
            Eyebrow("Frequency")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { freqs.forEachIndexed { i, fp -> GenChip(fp.first, freqIdx == i) { freqIdx = i } } }
            Eyebrow("Modulation")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { presets.forEach { pp -> GenChip(pp.first, presetSel == pp.second) { presetSel = pp.second } } }
            Eyebrow("TX radio")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                GenChip("Int", FlipperRf.txDevice == FlipperRf.DEV_INT) { FlipperRf.txDevice = FlipperRf.DEV_INT }
                GenChip("Ext", FlipperRf.txDevice == FlipperRf.DEV_EXT) { FlipperRf.txDevice = FlipperRf.DEV_EXT }
                GenChip("Auto", FlipperRf.txDevice == FlipperRf.DEV_AUTO) { FlipperRf.txDevice = FlipperRf.DEV_AUTO }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                .background(if (connected && !busy) Fox.amber else Fox.surface2)
                .clickable(enabled = connected && !busy) { generateTx() }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                Text(if (busy) "WORKING…" else "GENERATE & TRANSMIT", color = if (connected && !busy) Fox.amberInk else Fox.faint,
                    fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("De Bruijn sends one short stream covering every code; Brute steps all codes; Single sends one. Fixed-code gates only.",
                color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, lineHeight = 13.sp)
        } }

        val subs = savedSubs(ctx)
        FoxCard { Column {
            Eyebrow("Replay saved captures — ${subs.size}")
            if (subs.isEmpty()) { Spacer(Modifier.height(6.dp)); Text("No .sub files yet. Capture in Decode or forge in Pandora / Car & Gate / Tag Maker.", color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp) }
            subs.take(20).forEach { f ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy && connected) {
                    busy = true; status = "Transmitting ${f.name}…"
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { f.readText() }
                        val r = FlipperRf.tx(text, subFreqHz(text), FlipperRf.txDevice)
                        status = if (r.ok) "✓ Transmitted ${f.name}." else "✗ TX ${r.label}."
                        busy = false
                    }
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, color = Fox.fg, fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(if (connected) "TX →" else "—", color = if (connected) Fox.amber else Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
                }
            }
        } }
        FoxCard { Text(status, color = if (status.startsWith("✗")) Fox.amber else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp) }
    }
}

@Composable
private fun GenChip(label: String, sel: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(9.dp)).background(if (sel) Fox.amber else Fox.surface2)
            .border(1.dp, if (sel) Fox.amber else Fox.line, RoundedCornerShape(9.dp))
            .clickable { onClick() }.padding(horizontal = 11.dp, vertical = 7.dp),
    ) { Text(label, color = if (sel) Fox.amberInk else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

// ================= Freq Analyzer (launch the Flipper's analyzer) =================
private data class SpecBand(val label: String, val start: Int, val stop: Int, val step: Int)

private val specBands = listOf(
    SpecBand("433 ISM", 433050000, 434790000, 25000),
    SpecBand("315", 314000000, 316000000, 25000),
    SpecBand("868", 868000000, 870000000, 25000),
    SpecBand("915", 915000000, 917000000, 25000),
)

/**
 * On-phone Spectrum Analyzer + Frequency Finder (PandwaRF-style), driven through the Flipper's
 * CC1101: the FAP SWEEPs the band reading RSSI per channel, we render it live and mark the peak.
 */
@Composable
fun FreqAnalyzerScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connected = FlipperRf.connected
    var bandIdx by remember { mutableStateOf(0) }
    var devIdx by remember { mutableStateOf(FlipperRf.DEV_AUTO) }
    var running by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<List<SpecPoint>>(emptyList()) }
    val maxR = remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }   // per-channel max
    val avgR = remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }   // per-channel running avg
    val avgSum = remember { HashMap<Int, Long>() }
    val avgCnt = remember { HashMap<Int, Int>() }
    var peak by remember { mutableStateOf<SpecPoint?>(null) }
    var status by remember { mutableStateOf(if (connected) "Pick a band, then Live or Single." else "Connect the Flipper (Device tab) first.") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var deploying by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }

    fun reset() { live = emptyList(); maxR.clear(); avgR.clear(); avgSum.clear(); avgCnt.clear(); peak = null }

    fun updateFap() {
        if (!FlipperRf.connected) { status = "Flipper not connected."; return }
        if (deploying) return
        deploying = true; status = "Pushing fox_subghz_io.fap to the Flipper…"
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ctx.assets.open("fox_android_companion.fap").readBytes() }.getOrNull()
            }
            if (bytes == null) { deploying = false; status = "FAP asset missing from the app."; return@launch }
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperRf.deployFap(bytes) }
            deploying = false
            status = "FAP update: $res" + if (res.endsWith("OK")) " — now tap Single/Live." else "."
        }
    }

    suspend fun oneSweep(): Boolean {
        val b = specBands[bandIdx]
        val pts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            FlipperRf.specAn(b.start, b.stop, b.step, devIdx)
        } ?: return false
        if (pts.isEmpty()) return false
        live = pts
        for (p in pts) {
            maxR[p.freqHz] = maxOf(maxR[p.freqHz] ?: -200, p.rssi)
            avgSum[p.freqHz] = (avgSum[p.freqHz] ?: 0L) + p.rssi
            avgCnt[p.freqHz] = (avgCnt[p.freqHz] ?: 0) + 1
            avgR[p.freqHz] = (avgSum[p.freqHz]!! / avgCnt[p.freqHz]!!).toInt()
        }
        peak = spectrumPeak(pts)
        return true
    }

    fun ingest(pts: List<SpecPoint>) {
        live = pts
        for (p in pts) {
            maxR[p.freqHz] = maxOf(maxR[p.freqHz] ?: -200, p.rssi)
            avgSum[p.freqHz] = (avgSum[p.freqHz] ?: 0L) + p.rssi
            avgCnt[p.freqHz] = (avgCnt[p.freqHz] ?: 0) + 1
            avgR[p.freqHz] = (avgSum[p.freqHz]!! / avgCnt[p.freqHz]!!).toInt()
        }
        peak = spectrumPeak(pts)
    }
    fun startLive() {
        if (!FlipperRf.connected) { status = "Flipper not connected."; return }
        reset(); running = true; status = "Live sweeping ${specBands[bandIdx].label}…"
        scope.launch {
            val b = specBands[bandIdx]
            // Preferred: launch ONE resident sweeper and poll its latest frame (smooth, no per-frame appStart).
            val cont = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FlipperRf.specAnStart(b.start, b.stop, b.step, devIdx)
            }
            if (!cont) { status = "Sweep failed · ${FlipperRf.lastSpecDiag}"; running = false; return@launch }
            var empties = 0; var gotAny = false; var fellBack = false
            while (running) {
                kotlinx.coroutines.delay(220)
                // old/one-shot FAP (no continuous mode) exits after one pass -> fall back to relaunch loop
                if (!gotAny && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperRf.specAnFapExited() }) { fellBack = true; break }
                val pts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperRf.specAnLatest() }
                if (pts.isNullOrEmpty()) { if (++empties > 25) { fellBack = true; break }; continue }
                empties = 0; gotAny = true
                if (!held) { ingest(pts); status = "Live · ${live.size} ch · peak ${peak?.let { "%.3f MHz".format(it.freqHz / 1e6) } ?: "—"}" }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperRf.specAnStop() }
            if (fellBack && running) {
                status = "Live (compat) · ${specBands[bandIdx].label}…"
                while (running) {
                    if (held) { kotlinx.coroutines.delay(200); continue }
                    if (!oneSweep()) { status = "Sweep failed · ${FlipperRf.lastSpecDiag}"; running = false; break }
                    status = "Live · ${live.size} ch · peak ${peak?.let { "%.3f MHz".format(it.freqHz / 1e6) } ?: "—"}"
                }
            }
        }
    }
    fun singleSweep() {
        if (!FlipperRf.connected) { status = "Flipper not connected."; return }
        running = false; reset(); status = "Sweeping…"
        scope.launch { status = if (oneSweep()) "Swept ${live.size} channels." else "Sweep failed · ${FlipperRf.lastSpecDiag}" }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            running = false
            // Leaving the screen cancels `scope`, so the specAnStop() inside startLive()'s loop never
            // runs — the resident sweep FAP would keep sweeping and hold the Flipper's loader, blocking
            // ALL later TX/RX. Stop it from a detached scope that survives this composition's cancel.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
                .launch { FlipperRf.specAnStop() }
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 2.dp, 16.dp, 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolHeader("Spectrum Analyzer", "live RSSI sweep via CC1101", running, onClose)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            specBands.forEachIndexed { i, b -> SpecChip(b.label, i == bandIdx, Modifier.weight(1f)) { if (i != bandIdx) { running = false; bandIdx = i; reset() } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Int" to FlipperRf.DEV_INT, "Ext" to FlipperRf.DEV_EXT, "Auto" to FlipperRf.DEV_AUTO)
                .forEach { (lbl, d) -> SpecChip(lbl, d == devIdx, Modifier.weight(1f)) { devIdx = d } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpecChip(if (running) "■ Stop" else "▶ Live", running, Modifier.weight(1f)) { if (running) running = false else startLive() }
            SpecChip("Single", false, Modifier.weight(1f)) { if (!running) singleSweep() }
            SpecChip(if (held) "▶ Resume" else "❚❚ Hold", held, Modifier.weight(1f)) { held = !held }
        }
        peak?.let {
            FoxCard {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("STRONGEST  ·  FREQ FINDER", color = Fox.muted, fontFamily = FoxMono, fontSize = 9.sp, letterSpacing = 1.sp)
                    Text("%.3f MHz".format(it.freqHz / 1e6), color = Fox.amber, fontFamily = FoxMono, fontSize = 22.sp)
                    Text("${it.rssi} dBm", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
                }
            }
        }
        val peaks = topPeaks(live, 5)
        if (peaks.size > 1) FoxCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow("Top signals — tap to tune Decode")
            peaks.forEach { pk ->
                Row(Modifier.fillMaxWidth().clickable {
                    SpecHandoff.tuneHz = pk.freqHz
                    status = "→ %.3f MHz sent to Decode — open the Decode tab to capture.".format(pk.freqHz / 1e6)
                }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("%.3f MHz".format(pk.freqHz / 1e6), color = Fox.fg, fontFamily = FoxMono, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${pk.rssi} dBm", color = Fox.amber, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp)); Text("tune →", color = Fox.live, fontFamily = FoxMono, fontSize = 10.sp)
                }
            }
        } }
        if (live.isNotEmpty()) SpectrumChart(live, maxR, avgR, peak, specBands[bandIdx])
        FoxCard { Text(status, color = if (status.contains("failed") || status.contains("not connected") || status.contains("no data") || status.contains("busy")) Fox.amber else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp) }
        // Push the app-matched helper FAP to the Flipper (fixes "FAP launched but wrote nothing").
        SpecChip(if (deploying) "Updating FAP…" else "⬆ Update Flipper FAP", false, Modifier.fillMaxWidth()) { updateFap() }
    }
}

@Composable
private fun SpecChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
            .background(if (selected) Fox.amber else Fox.surface2)
            .clickable { onClick() }.padding(vertical = 9.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(label, color = if (selected) Fox.amberInk else Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
    }
}

// PandwaRF-style multi-line spectrum: live RSSI (green) over per-channel Max (red) and Avg (yellow),
// with a freq X-axis, a dBm Y-axis range, a peak marker and a legend.
private val SpecMaxColor = androidx.compose.ui.graphics.Color(0xFFE0483B)   // red  — Max-ever
private val SpecAvgColor = androidx.compose.ui.graphics.Color(0xFFE0B020)   // amber-yellow — Avg

@Composable
private fun SpectrumChart(live: List<SpecPoint>, maxR: Map<Int, Int>, avgR: Map<Int, Int>, peak: SpecPoint?, band: SpecBand) {
    val all = live.map { it.rssi } + maxR.values + avgR.values
    val lo = (all.minOrNull() ?: -110) - 2
    val hi = (all.maxOrNull() ?: -20) + 2
    val span = (hi - lo).coerceAtLeast(1)
    val freqs = live.map { it.freqHz }
    val pulse by rememberInfiniteTransition(label = "pk").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    FoxCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.foxfw.companion.ui.GraticuleScreen(Modifier.fillMaxWidth().height(170.dp)) {
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val w = size.width; val h = size.height
                fun y(r: Int) = h - ((r - lo).toFloat() / span) * h
                fun x(i: Int, n: Int) = if (n <= 1) 0f else i.toFloat() / (n - 1) * w
                fun pathOf(points: List<Int>): androidx.compose.ui.graphics.Path {
                    val p = androidx.compose.ui.graphics.Path()
                    points.forEachIndexed { i, r -> val xx = x(i, points.size); val yy = y(r); if (i == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy) }
                    return p
                }
                // avg + max as faint ridges behind the live trace
                if (avgR.isNotEmpty()) drawPath(pathOf(freqs.map { avgR[it] ?: lo }), SpecAvgColor.copy(alpha = 0.30f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
                drawPath(pathOf(freqs.map { maxR[it] ?: lo }), SpecMaxColor.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.3f))
                // live RSSI: gradient area fill, then a soft glow (stacked translucent strokes), then a crisp core
                val liveVals = live.map { it.rssi }
                if (liveVals.size >= 2) {
                    val fill = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, h)
                        liveVals.forEachIndexed { i, r -> lineTo(x(i, liveVals.size), y(r)) }
                        lineTo(w, h); close()
                    }
                    drawPath(fill, brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Fox.live.copy(alpha = 0.34f), 1f to Fox.live.copy(alpha = 0f)))
                    val lp = pathOf(liveVals)
                    drawPath(lp, Fox.live.copy(alpha = 0.10f), style = androidx.compose.ui.graphics.drawscope.Stroke(9f))
                    drawPath(lp, Fox.live.copy(alpha = 0.16f), style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                    drawPath(lp, Fox.live, style = androidx.compose.ui.graphics.drawscope.Stroke(2.3f))
                }
                // peak: dashed vertical guide + a pulsing amber glow dot
                peak?.let { pk ->
                    val i = freqs.indexOf(pk.freqHz)
                    if (i >= 0) {
                        val px = x(i, freqs.size); val py = y(pk.rssi)
                        drawLine(Fox.amber.copy(alpha = 0.32f), androidx.compose.ui.geometry.Offset(px, 0f), androidx.compose.ui.geometry.Offset(px, h), 1f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f, 4f)))
                        drawCircle(Fox.amber.copy(alpha = 0.18f), 7f + pulse * 4f, androidx.compose.ui.geometry.Offset(px, py))
                        drawCircle(Fox.amber, 3.6f, androidx.compose.ui.geometry.Offset(px, py))
                    }
                }
            }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("%.2f MHz".format(band.start / 1e6), color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp)
                Text("%d … %d dBm".format(lo, hi), color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp)
                Text("%.2f MHz".format(band.stop / 1e6), color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot(Fox.live, "RSSI"); LegendDot(SpecMaxColor, "Max"); LegendDot(SpecAvgColor, "Avg")
            }
        }
    }
}

@Composable
private fun LegendDot(c: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(9.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(c)
        )
        Text(label, color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp)
    }
}
