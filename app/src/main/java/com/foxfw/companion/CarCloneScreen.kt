package com.foxfw.companion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CarCloneScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val families = remember { if (ArkeEngine.available) ArkeEngine.families() else emptyList() }
    // picker: "Auto", "Barriers", then every make family
    val picker = remember { listOf("Auto", "Barriers") + families }
    var sel by remember { mutableStateOf("Auto") }

    Column(
        Modifier.fillMaxWidth().background(Fox.page).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Fox.surface)
                .border(1.dp, Fox.line, RoundedCornerShape(10.dp)).clickable { onClose() },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, "back", tint = Fox.fg, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Eyebrow("CARS · GATES · BARRIERS · ARKE ENGINE")
                Text("Car & Gate Clone", color = Fox.fg, fontFamily = FoxMono, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(16.dp))

        // protocol picker
        FoxCard(Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("PROTOCOL  (${families.size} makes + gates)")
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    picker.forEach { p ->
                        PickChip(p, selected = sel == p) { sel = p }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (sel == "Barriers") BarrierPanel(ctx, scope)
        else DecodePanel(sel, scope)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DecodePanel(family: String, scope: kotlinx.coroutines.CoroutineScope) {
    var sub by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var frames by remember { mutableStateOf<List<ArkeEngine.Frame>>(emptyList()) }
    var status by remember {
        mutableStateOf(if (ArkeEngine.available) "Paste a Flipper RAW .sub and decode." else "Engine not started")
    }
    fun filt(list: List<ArkeEngine.Frame>) =
        if (family == "Auto") list else list.filter { it.make.contains(family, true) || family.contains(it.make.split(" ")[0], true) }

    fun run(fromSample: Boolean) {
        scope.launch {
            busy = true; status = if (fromSample) "Decoding sample…" else "Decoding ($family)…"
            val (res, err) = withContext(Dispatchers.Default) { if (fromSample) ArkeEngine.decodeSample() else ArkeEngine.decode(sub) }
            busy = false
            val shown = filt(res)
            frames = shown
            status = when { err != null -> "No match: $err"; shown.isEmpty() -> "No ${if (family == "Auto") "" else "$family "}match"; else -> "Decoded ${shown.size} frame(s)" }
        }
    }

    fun doLiveRx(dev: Int) {
        scope.launch {
            busy = true; status = "Capturing via Flipper (${if (dev == 0) "internal" else "external"})…"
            val subText = withContext(Dispatchers.Default) { FlipperRf.rx(433920000, dev, 4000) }
            if (subText == null) { busy = false; status = "Flipper capture failed (connected? fox_subghz_io.fap on SD?)"; return@launch }
            sub = subText
            val (res, err) = withContext(Dispatchers.Default) { ArkeEngine.decode(subText) }
            busy = false
            val shown = filt(res)
            frames = shown
            status = when { err != null -> "No match: $err"; shown.isEmpty() -> "Captured but no protocol matched"; else -> "Live RX → decoded ${shown.size} frame(s)" }
        }
    }

    FoxCard(Modifier.fillMaxWidth()) {
        Column {
            Eyebrow("RAW CAPTURE  (Flipper .sub text)")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sub, onValueChange = { sub = it },
                placeholder = { Text("Filetype: Flipper SubGhz RAW File …", color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp) },
                textStyle = TextStyle(color = Fox.fg, fontFamily = FoxMono, fontSize = 11.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 170.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Fox.amber, unfocusedBorderColor = Fox.line2,
                    focusedContainerColor = Fox.surface, unfocusedContainerColor = Fox.surface, cursorColor = Fox.amber)
            )
            Spacer(Modifier.height(8.dp))
            WideChip(if (busy) "Decoding…" else "Decode" + if (family == "Auto") " — all makes + gates" else " — $family", !busy && ArkeEngine.available) { if (sub.isBlank()) status = "Paste a .sub first" else run(false) }
            Spacer(Modifier.height(8.dp))
            WideChip("Try bundled VAG sample", !busy && ArkeEngine.available) { run(true) }
            Spacer(Modifier.height(10.dp))
            Eyebrow("LIVE RX via Flipper CC1101  @433.92")
            Spacer(Modifier.height(6.dp))
            WideChip("Capture 4s via Flipper — Internal", !busy && FlipperRf.connected) { doLiveRx(FlipperRf.DEV_INT) }
            Spacer(Modifier.height(6.dp))
            WideChip("Capture 4s via Flipper — External", !busy && FlipperRf.connected) { doLiveRx(FlipperRf.DEV_EXT) }
        }
    }
    Spacer(Modifier.height(12.dp))
    FoxCard(Modifier.fillMaxWidth(), border = if (frames.isNotEmpty()) Fox.live.copy(alpha = 0.5f) else Fox.line) {
        Column {
            Eyebrow("RESULT")
            Spacer(Modifier.height(8.dp))
            Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
            frames.forEach { f -> FrameCard(f) }
        }
    }
}

@Composable
private fun FrameCard(f: ArkeEngine.Frame) {
    Spacer(Modifier.height(10.dp))
    Surface(shape = RoundedCornerShape(10.dp), color = Fox.surface2, border = BorderStroke(1.dp, Fox.line2), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(f.make, color = Fox.amber, fontFamily = FoxMono, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                f.btnName?.let { Text(it.uppercase(), color = Fox.fg, fontFamily = FoxMono, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(4.dp))
            f.serialHex?.let { if (it.isNotBlank()) KV("serial / code", "0x$it") }
            f.cnt?.let { KV("counter", "0x%X".format(it)) }
            f.btn?.let { KV("button", "$it" + (f.btnName?.let { n -> " ($n)" } ?: "")) }
            f.next?.let { KV("next code", "0x$it", Fox.live) }
            f.nextCnt?.let { KV("next counter", "0x%X".format(it), Fox.live) }
            f.crcOk?.let { KV("crc", if (it) "ok" else "fail", if (it) Fox.live else Fox.warn) }
            f.gate?.let { KV("type", it) }
        }
    }
}

@Composable
private fun BarrierPanel(ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
    val protos = remember { if (ArkeEngine.available) ArkeEngine.Barriers.protocols() else emptyList() }
    var proto by remember { mutableStateOf(protos.firstOrNull()) }
    var freq by remember { mutableStateOf(433920000) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Pick a gate protocol, then brute-force all codes or send one.") }
    var savedPath by remember { mutableStateOf("") }
    var dev by remember { mutableStateOf(-1) }   // -1 = save .sub; 0/1/2 = Flipper int/ext/auto

    suspend fun emit(fileName: String, text: String, what: String) {
        if (dev >= 0 && FlipperRf.connected) {
            status = "$what → sending to Flipper…"
            val r = FlipperRf.tx(text, freq, dev)
            status = if (r.ok) "$what → TX live via Flipper CC1101 (${if (dev == 0) "int" else if (dev == 1) "ext" else "auto"}) @ ${freq / 1_000_000.0}MHz." else "$what → Flipper TX ${r.label}."
        } else {
            val f = File(ctx.getExternalFilesDir(null), fileName)
            runCatching { f.writeText(text) }
            savedPath = f.absolutePath
            status = "$what → .sub saved (${text.length / 1024}KB). Play RAW on Flipper."
        }
    }

    fun save(name: String, text: String) {
        val f = File(ctx.getExternalFilesDir(null), name)
        runCatching { f.writeText(text) }
        savedPath = f.absolutePath
    }

    FoxCard(Modifier.fillMaxWidth()) {
        Column {
            Eyebrow("GATE / BARRIER PROTOCOL")
            Spacer(Modifier.height(8.dp))
            protos.forEach { p ->
                val on = proto?.name == p.name
                Surface(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { proto = p },
                    shape = RoundedCornerShape(10.dp), color = if (on) Fox.amber.copy(alpha = 0.15f) else Fox.surface2,
                    border = BorderStroke(1.dp, if (on) Fox.amber else Fox.line2)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(p.name, color = if (on) Fox.amber else Fox.fg, fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${p.codes} codes · ${p.te}µs", color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Eyebrow("FREQUENCY")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickChip("433.92", freq == 433920000) { freq = 433920000 }
                PickChip("868.35", freq == 868350000) { freq = 868350000 }
                PickChip("40.685", freq == 40685000) { freq = 40685000 }
            }
            Spacer(Modifier.height(8.dp))
            Eyebrow("OUTPUT" + if (dev >= 0 && !FlipperRf.connected) "  (Flipper not connected)" else "")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickChip("Save .sub", dev == -1) { dev = -1 }
                PickChip("Int", dev == 0) { dev = 0 }
                PickChip("Ext", dev == 1) { dev = 1 }
                PickChip("Auto", dev == 2) { dev = 2 }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    FoxCard(Modifier.fillMaxWidth()) {
        Column {
            Eyebrow("BRUTE-FORCE")
            Spacer(Modifier.height(8.dp))
            WideChip(if (busy) "Working…" else (if (dev >= 0) "Brute-force ALL ${proto?.codes ?: 0} codes → Flipper" else "Brute-force ALL ${proto?.codes ?: 0} codes"), !busy && proto != null) {
                val p = proto ?: return@WideChip
                scope.launch {
                    busy = true; status = "Generating ${p.codes}-code sweep…"
                    val sub = withContext(Dispatchers.Default) { ArkeEngine.Barriers.bruteSub(p.name, freq) }
                    if (sub == null) { busy = false; status = "Failed (space too large?)"; return@launch }
                    emit("barrier_${p.name.replace(" ", "_")}_brute.sub", sub, "${p.name} sweep (${p.codes} codes)")
                    busy = false
                }
            }
            Spacer(Modifier.height(8.dp))
            WideChip(if (busy) "Working…" else "De Bruijn sweep (shorter) → Flipper", !busy && proto != null) {
                val p = proto ?: return@WideChip
                scope.launch {
                    busy = true; status = "Generating De Bruijn sweep…"
                    val sub = withContext(Dispatchers.Default) { ArkeEngine.Barriers.debruijnSub(p.name, freq) }
                    if (sub == null) { busy = false; status = "Failed (space too large?)"; return@launch }
                    emit("barrier_${p.name.replace(" ", "_")}_debruijn.sub", sub, "${p.name} De Bruijn sweep")
                    busy = false
                }
            }
            Spacer(Modifier.height(12.dp))
            Eyebrow("SEND ONE CODE")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code, onValueChange = { code = it }, singleLine = true,
                    placeholder = { Text("code (hex)", color = Fox.faint, fontFamily = FoxMono, fontSize = 12.sp) },
                    textStyle = TextStyle(color = Fox.fg, fontFamily = FoxMono, fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Fox.amber, unfocusedBorderColor = Fox.line2,
                        focusedContainerColor = Fox.surface, unfocusedContainerColor = Fox.surface, cursorColor = Fox.amber)
                )
                WideChipFixed("Send") {
                    val p = proto ?: return@WideChipFixed
                    val c = code.removePrefix("0x").toIntOrNull(16) ?: return@WideChipFixed
                    scope.launch {
                        busy = true
                        val sub = withContext(Dispatchers.Default) { ArkeEngine.Barriers.oneSub(p.name, c, freq) }
                        if (sub != null) emit("barrier_${p.name.replace(" ", "_")}_0x%X.sub".format(c), sub, "${p.name} code 0x%X".format(c))
                        busy = false
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    FoxCard(Modifier.fillMaxWidth(), border = if (savedPath.isNotEmpty()) Fox.live.copy(alpha = 0.5f) else Fox.line) {
        Column {
            Eyebrow("STATUS")
            Spacer(Modifier.height(8.dp))
            Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
            if (savedPath.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(savedPath, color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun KV(k: String, v: String, vColor: Color = Fox.fg) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(k.uppercase(), color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp, modifier = Modifier.width(120.dp))
        Text(v, color = vColor, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PickChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(Modifier.clickable { onClick() }, shape = RoundedCornerShape(999.dp),
        color = if (selected) Fox.amber else Fox.surface2, border = BorderStroke(1.dp, if (selected) Fox.amber else Fox.line2)) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(text, color = if (selected) Fox.amberInk else Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun WideChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }, shape = RoundedCornerShape(10.dp),
        color = Fox.surface2, border = BorderStroke(1.dp, if (enabled) Fox.amber else Fox.line2)) {
        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, color = if (enabled) Fox.amber else Fox.faint, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun WideChipFixed(text: String, onClick: () -> Unit) {
    Surface(Modifier.clickable { onClick() }, shape = RoundedCornerShape(10.dp), color = Fox.amber) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(text, color = Fox.amberInk, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
