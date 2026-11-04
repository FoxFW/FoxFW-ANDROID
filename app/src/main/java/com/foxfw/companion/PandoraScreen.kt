package com.foxfw.companion

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
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

private fun hexToBytes(s: String): ByteArray? {
    val h = s.filter { it.isLetterOrDigit() }
    if (h.length % 2 != 0) return null
    return try { ByteArray(h.length / 2) { ((h.substring(it * 2, it * 2 + 2)).toInt(16)).toByte() } }
    catch (_: Throwable) { null }
}
private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

@Composable
fun PandoraScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf(0) }            // 0=D-605, 1=MAX
    var capture by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("7DFC0969") }
    var counter by remember { mutableStateOf("1") }
    var status by remember { mutableStateOf(if (PandoraNative.available) "Emulator loaded. Decode a capture or forge a command." else "libpandora not available") }
    var onair by remember { mutableStateOf("") }
    var subPath by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dev by remember { mutableStateOf(-1) }   // -1 = save .sub; 0/1/2 = Flipper int/ext/auto
    var crackMsg by remember { mutableStateOf("") }

    // 0=D-605 (ARM), 1=MAX (ARM), 2=DXL-5000 (PIC). ARM supports decode/forge/TX;
    // DXL-5000 supports crack + emulate (on-phone OOK clone deferred).
    val modelNames = listOf("D-605", "MAX", "DXL-5000")
    val assetNames = listOf("pandora_d605.bin", "pandora_max.bin", "pandora_dxl5000.bin")
    fun fwBytes(): ByteArray = ctx.assets.open(assetNames[model]).use { it.readBytes() }

    fun doCrack() {
        scope.launch {
            busy = true
            crackMsg = ""
            status = if (model < 2) "Scanning firmware for the knock-code…"
                     else "Recovering PIN (reads the compare literals; brute if needed)…"
            val fw = withContext(Dispatchers.IO) { fwBytes() }
            val res = withContext(Dispatchers.Default) { PandoraCracker.crack(fw, assetNames[model]) }
            crackMsg = res.display
            val f = File(ctx.getExternalFilesDir(null), "${modelNames[model]}_code.txt")
            runCatching { f.writeText(res.display + "\n") }
            status = "Recovered → ${res.display}  (saved ${f.name})"
            busy = false
        }
    }

    fun doDecode() {
        val b = hexToBytes(capture)
        if (b == null || b.size < 9) { status = "Capture must be 9 hex bytes (the on-air data)"; return }
        scope.launch {
            busy = true
            val d = withContext(Dispatchers.Default) { PandoraNative.decodeFrame(b) }
            busy = false
            if (d == null) { status = "Decode failed"; return@launch }
            serial = d.serialHex
            counter = (d.counter + 1).toString()          // next rolling counter
            status = "Decoded: serial=${d.serialHex} cmd=0x%02X counter=${d.counter} ${if (d.cksOk) "cksum OK" else "cksum?"}  → counter set to ${d.counter + 1}".format(d.cmdCode)
        }
    }

    fun doCapture(capDev: Int) {
        if (!FlipperRf.connected) { status = "Connect the Flipper first (Remote Screen) to capture."; return }
        scope.launch {
            busy = true
            status = "Listening 4s on 433.92 via Flipper CC1101 (${devName(capDev)})… press the fob now."
            val sub = withContext(Dispatchers.Default) { FlipperRf.rx(433920000, capDev, 4000) }
            if (sub == null) { busy = false; status = "Capture failed (Flipper RX)."; return@launch }
            val cands = withContext(Dispatchers.Default) { PandoraDemod.candidates(sub, model) }
            if (cands.isEmpty()) {
                busy = false
                status = "No ${PandoraNative.MODEL_NAMES[model]} frame found in the capture — try again, fob closer."
                return@launch
            }
            capture = cands.first()
            busy = false
            doDecode()   // recover serial + counter from the captured frame
        }
    }

    fun doForge(cmd: Int, label: String) {
        val ser = hexToBytes(serial)
        if (ser == null || ser.size < 4) { status = "Serial must be 4 hex bytes"; return }
        val ctr = counter.toIntOrNull() ?: 1
        scope.launch {
            busy = true
            val frame = withContext(Dispatchers.Default) {
                PandoraNative.forgeFrame(ctx, model, ser.copyOf(4), ctr, cmd)
            }
            if (frame == null) { busy = false; status = "Forge failed"; return@launch }
            onair = frame.hex()
            // Pandora keyfobs transmit OOK (D-605/MAX bit-bang OOK on the ARM DATA pin;
            // DXL-5000 on LATB) — the field-proven waveform (PandoraARM/T-Embed + pandora_gen.py).
            // The 2-FSK attempt was wrong and is reverted: synthesize OOK Manchester and TX at AM650.
            val pulses = withContext(Dispatchers.Default) { PandoraNative.pulses(model, frame) }
            val sub = withContext(Dispatchers.Default) { PandoraNative.subFile(pulses) }
            if (dev >= 0 && FlipperRf.connected) {
                status = "$label forged (counter $ctr, OOK) → sending to Flipper…"
                val r = FlipperRf.tx(sub, 433920000, dev, FlipperRf.AM650)
                status = if (r.ok) "$label TX live via Flipper CC1101 (${devName(dev)}), counter $ctr."
                         else "$label forged but Flipper TX ${r.label}."
            } else {
                val f = File(ctx.getExternalFilesDir(null), "pandora_${PandoraNative.MODEL_NAMES[model]}_${label}_c$ctr.sub")
                runCatching { f.writeText(sub) }
                subPath = f.absolutePath
                status = "$label forged (counter $ctr) → ${frame.size}-byte frame. .sub saved."
            }
            busy = false
            counter = (ctr + 1).toString()                // advance for next press
        }
    }

    Column(
        Modifier.fillMaxWidth().background(Fox.page).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Eyebrow("CAR ALARM FOB · ROLLING CODE")
                Text("Pandora Clone", color = Fox.fg, fontFamily = FoxMono, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(16.dp))

        // model selector
        FoxCard(Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("KEYFOB MODEL")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modelNames.forEachIndexed { i, nm ->
                        Chip(nm, selected = model == i, modifier = Modifier.weight(1f)) { model = i; crackMsg = "" }
                    }
                }
                Spacer(Modifier.height(6.dp))
                LabelS(if (model < 2) "ARM EFM32 (Thumb-2) — emulate · crack knock · clone/TX"
                       else "PIC18 (DXL-5000) — emulate · crack PIN · (on-phone clone deferred)")
            }
        }
        Spacer(Modifier.height(12.dp))

        // code-grabber: recover the unlock code from the firmware (native)
        FoxCard(Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("CODE-GRABBER · RECOVER UNLOCK CODE")
                Spacer(Modifier.height(8.dp))
                Chip(if (model < 2) "Recover knock-code (static scan)" else "Recover PIN (0000–9999)",
                    selected = false, accent = true, enabled = !busy && PandoraCracker.available,
                    modifier = Modifier.fillMaxWidth()) { doCrack() }
                if (crackMsg.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(crackMsg, color = Fox.live, fontFamily = FoxMono, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                LabelS(if (model < 2) "scans the real ARM firmware for the knock sequence"
                       else "runs the real PIC18 PIN validation to recover the numeric PIN")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (model < 2) {
        // decode a capture (ARM D-605/MAX)
        FoxCard(Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("DECODE CAPTURE  (9 on-air hex bytes)")
                Spacer(Modifier.height(8.dp))
                Field(capture, { capture = it }, "83 02 F7 97 DE FE FE F3 78")
                Spacer(Modifier.height(8.dp))
                Chip("Decode → recover serial + counter", selected = false, accent = true, enabled = !busy) { doDecode() }
                Spacer(Modifier.height(10.dp))
                Eyebrow("CAPTURE LIVE VIA FLIPPER CC1101" + if (!FlipperRf.connected) "  (not connected)" else "")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Int", selected = false, enabled = !busy, modifier = Modifier.weight(1f)) { doCapture(0) }
                    Chip("Ext", selected = false, enabled = !busy, modifier = Modifier.weight(1f)) { doCapture(1) }
                    Chip("Auto", selected = false, enabled = !busy, modifier = Modifier.weight(1f)) { doCapture(2) }
                }
                Spacer(Modifier.height(4.dp))
                LabelS("listens 4s @433.92, demodulates the on-air frame, recovers serial + counter")
            }
        }
        Spacer(Modifier.height(12.dp))

        // serial + counter
        FoxCard(Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("FOB IDENTITY")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { LabelS("Serial (4B hex)"); Field(serial, { serial = it }, "7DFC0969") }
                    Column(Modifier.weight(1f)) { LabelS("Counter"); Field(counter, { counter = it }, "1", number = true) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // output: save .sub, or TX live via Flipper CC1101 (int/ext/auto)
        FoxCard(Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("OUTPUT" + if (dev >= 0 && !FlipperRf.connected) "  (Flipper not connected)" else "")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Save .sub", selected = dev == -1, modifier = Modifier.weight(1f)) { dev = -1 }
                    Chip("Int", selected = dev == 0, modifier = Modifier.weight(1f)) { dev = 0 }
                    Chip("Ext", selected = dev == 1, modifier = Modifier.weight(1f)) { dev = 1 }
                    Chip("Auto", selected = dev == 2, modifier = Modifier.weight(1f)) { dev = 2 }
                }
                if (dev >= 0) { Spacer(Modifier.height(6.dp)); LabelS("transmits live via Flipper CC1101 (fox_subghz_io.fap launched over RPC)") }
            }
        }
        Spacer(Modifier.height(12.dp))

        // commands
        FoxCard(Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("SEND COMMAND  (forges next rolling frame)")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("CMD 1", selected = false, accent = true, enabled = !busy, modifier = Modifier.weight(1f)) { doForge(1, "cmd1") }
                    Chip("CMD 2", selected = false, accent = true, enabled = !busy, modifier = Modifier.weight(1f)) { doForge(2, "cmd2") }
                    Chip("CMD 3", selected = false, accent = true, enabled = !busy, modifier = Modifier.weight(1f)) { doForge(3, "cmd3") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        } else {
            // DXL-5000 (PIC): clone/TX deferred
            FoxCard(Modifier.fillMaxWidth()) {
                Column {
                    Eyebrow("DXL-5000 CLONE")
                    Spacer(Modifier.height(8.dp))
                    LabelS("the PIC firmware emulates + cracks the PIN on-phone; the on-air OOK clone/TX is deferred (the fw only bit-bangs the radio from an interactive command state). Use the Flipper's Pandora PIC app to transmit.")
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // result
        FoxCard(Modifier.fillMaxWidth(), border = if (onair.isNotEmpty()) Fox.live.copy(alpha = 0.5f) else Fox.line) {
            Column {
                Eyebrow("RESULT")
                Spacer(Modifier.height(8.dp))
                Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                if (onair.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LabelS("On-air frame")
                    Text(onair, color = Fox.live, fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                if (subPath.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LabelS(".sub (transfer to Flipper /ext/subghz, play RAW @433.92)")
                    Text(subPath, color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LabelS(t: String) { Text(t.uppercase(), color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, letterSpacing = 1.2.sp) }

private fun devName(d: Int) = when (d) { 0 -> "internal"; 1 -> "external"; 2 -> "auto"; else -> "save" }

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val fg = when { !enabled -> Fox.faint; selected -> Fox.amberInk; accent -> Fox.amber; else -> Fox.fg }
    val bg = when { selected -> Fox.amber; else -> Fox.surface2 }
    Surface(
        modifier = modifier.clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(10.dp), color = bg,
        border = BorderStroke(1.dp, if (selected) Fox.amber else Fox.line2)
    ) {
        Box(Modifier.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = fg, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, number: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, color = Fox.faint, fontFamily = FoxMono, fontSize = 12.sp) },
        textStyle = TextStyle(color = Fox.fg, fontFamily = FoxMono, fontSize = 13.sp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Fox.amber, unfocusedBorderColor = Fox.line2,
            focusedContainerColor = Fox.surface, unfocusedContainerColor = Fox.surface,
            cursorColor = Fox.amber
        )
    )
}
