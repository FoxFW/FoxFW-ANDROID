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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono

private data class RnResult(
    val serial: Int, val seed: Int, val cnt: Int, val btn: Int,
    val matches: Boolean, val codes: List<Pair<Int, String>>,
)

// A known round-trip test frame (serial 7FA10044, seed 1F3D, btn 4, cnt 4660).
private const val RN_SAMPLE = "7FA1004448D3229802328B"

@Composable
fun RenaultSeedScreen(onClose: () -> Unit) {
    var frameHex by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(
            if (NativeSolver.available) "Paste an 11-byte Renault V1 frame (22 hex) and recover the seed."
            else "Native engine unavailable on this device."
        )
    }
    var res by remember { mutableStateOf<RnResult?>(null) }
    val connected = FlipperRf.connected
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val presets = listOf(
        Triple("FM476", FlipperRf.FM476, "FuriHalSubGhzPreset2FSKDev476Async"),
        Triple("FM238", FlipperRf.FM238, "FuriHalSubGhzPreset2FSKDev238Async"),
        Triple("AM650", FlipperRf.AM650, "FuriHalSubGhzPresetOok650Async"),
        Triple("AM270", FlipperRf.AM270, "FuriHalSubGhzPresetOok270Async"),
    )
    var presetIdx by remember { mutableStateOf(0) }

    fun recover() {
        val clean = frameHex.filter { !it.isWhitespace() && it != ':' }.uppercase()
        if (clean.length != 22 || clean.any { Character.digit(it, 16) < 0 }) {
            status = "Need exactly 11 bytes — 22 hex chars."; res = null; return
        }
        if (!NativeSolver.available) { status = "Native engine unavailable."; res = null; return }
        val frame = ByteArray(11) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
        // The seed recovery is a native brute (~18-bit search) + 7 re-encodes — run it off the UI
        // thread so a RECOVER tap can't freeze/ANR the app.
        busy = true; status = "Recovering seed…"; res = null
        scope.launch {
            val out = withContext(Dispatchers.Default) {
                val iv = NativeSolver.seedRecover(frame)
                if (iv == null || iv.size < 4) return@withContext null
                val i0 = iv[0].toInt() and 0xFF; val i1 = iv[1].toInt() and 0xFF
                val i2 = iv[2].toInt() and 0xFF; val i3 = iv[3].toInt() and 0xFF
                val btn = i0 and 0x0F
                val cnt = (i0 ushr 4 and 0x0F) or (i1 shl 4) or ((i2 and 0x0F) shl 12)
                val seed = ((i2 and 0xF0) shl 8) or i3
                val serial = ((frame[0].toInt() and 0xFF) shl 24) or ((frame[1].toInt() and 0xFF) shl 16) or
                    ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
                val re = NativeSolver.seedForge(serial, cnt, btn, seed)
                val matches = re.contentEquals(frame)
                val codes = (1..6).map { k -> (cnt + k) to NativeSolver.seedForge(serial, cnt + k, btn, seed).toHexUpper() }
                RnResult(serial, seed, cnt, btn, matches, codes)
            }
            busy = false
            if (out == null) { status = "Seed NOT recovered — not a valid Renault V1 frame."; res = null } else {
                res = out
                status = if (out.matches) "Seed recovered — re-encode MATCHES the captured frame."
                else "Seed found but re-encode mismatch — double-check the frame bytes."
            }
        }
    }

    fun txCode(hex: String) {
        if (!connected) { status = "Connect the Flipper (Device tab) to transmit."; return }
        busy = true; status = "Rendering & transmitting…"
        scope.launch {
            val pp = presets[presetIdx]
            val sub = withContext(Dispatchers.Default) { ArkeEngine.Renault.renderSub(hex, 433920000, pp.third) }
            if (sub == null) { status = "Render failed."; busy = false; return@launch }
            val r = FlipperRf.tx(sub, 433920000, FlipperRf.txDevice, pp.second)
            status = if (r.ok) "⚡ Transmitted ${hex.take(8)}… (preset ${pp.first}). No response? try another modulation." else "TX failed: ${r.label}"
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().background(Fox.page).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Fox.surface)
                .border(1.dp, Fox.line, RoundedCornerShape(10.dp)).clickable { onClose() },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, "back", tint = Fox.fg, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Eyebrow("RENAULT V1 · HITAG2 SEED → CLONE")
                Text("Renault Seed", color = Fox.fg, fontFamily = FoxMono, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(14.dp))

        FoxCard(Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("CAPTURED FRAME  (11 bytes · 22 hex)")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Renault V1's keystream comes from the serial alone — one captured frame is enough to " +
                        "recover the ~18-bit seed, then predict every future code.",
                    color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = frameHex, onValueChange = { frameHex = it },
                    placeholder = { Text("e.g. 7FA1004448D3229802328B", color = Fox.faint, fontFamily = FoxMono, fontSize = 12.sp) },
                    textStyle = TextStyle(color = Fox.fg, fontFamily = FoxMono, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Fox.amber, unfocusedBorderColor = Fox.line2,
                        focusedContainerColor = Fox.surface, unfocusedContainerColor = Fox.surface, cursorColor = Fox.amber)
                )
                Spacer(Modifier.height(8.dp))
                RnWideChip("RECOVER SEED", NativeSolver.available) { recover() }
                Spacer(Modifier.height(6.dp))
                RnWideChip("Load self-test frame", true) { frameHex = RN_SAMPLE; status = "Loaded test frame — tap RECOVER (expect seed 103D, cnt 4660)."; res = null }
            }
        }
        Spacer(Modifier.height(12.dp))

        FoxCard(Modifier.fillMaxWidth(), border = if (res?.matches == true) Fox.live.copy(alpha = 0.5f) else Fox.line) {
            Column {
                Eyebrow("RESULT")
                Spacer(Modifier.height(8.dp))
                Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                res?.let { r ->
                    Spacer(Modifier.height(10.dp))
                    RnKV("serial", "0x%08X".format(r.serial))
                    RnKV("seed", "0x%04X".format(r.seed and 0xFFFF), Fox.live)
                    RnKV("counter", "%d (0x%X)".format(r.cnt, r.cnt))
                    RnKV("button", "${r.btn}")
                    RnKV("verify", if (r.matches) "re-encode matches" else "mismatch", if (r.matches) Fox.live else Fox.warn)
                    Spacer(Modifier.height(10.dp))
                    Eyebrow("NEXT CODES · btn ${r.btn}", Fox.live)
                    Spacer(Modifier.height(6.dp))
                    r.codes.forEach { (c, hex) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("CNT $c", color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp, modifier = Modifier.width(72.dp))
                            Text(hex, color = Fox.fg, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(if (connected && !busy) "TX →" else "—", color = if (connected && !busy) Fox.amber else Fox.faint,
                                fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.clickable(enabled = connected && !busy) { txCode(hex) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Eyebrow("TRANSMIT · modulation must match the fob", Fox.amber)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        presets.forEachIndexed { i, pp -> RnPresetChip(pp.first, i == presetIdx) { presetIdx = i } }
                    }
                    Spacer(Modifier.height(6.dp))
                    Eyebrow("TX radio")
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        RnPresetChip("Int", FlipperRf.txDevice == FlipperRf.DEV_INT) { FlipperRf.txDevice = FlipperRf.DEV_INT }
                        RnPresetChip("Ext", FlipperRf.txDevice == FlipperRf.DEV_EXT) { FlipperRf.txDevice = FlipperRf.DEV_EXT }
                        RnPresetChip("Auto", FlipperRf.txDevice == FlipperRf.DEV_AUTO) { FlipperRf.txDevice = FlipperRf.DEV_AUTO }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(if (connected) "Renders the Renault V1 Manchester frame (TE 125µs, 433.92 MHz) and transmits via the Flipper. Renault RKE is usually FSK — if the car doesn't respond, try another modulation."
                         else "Connect the Flipper (Device tab) to transmit.",
                        color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun ByteArray.toHexUpper(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append("%02X".format(b.toInt() and 0xFF))
    return sb.toString()
}

@Composable
private fun RnKV(k: String, v: String, vColor: Color = Fox.fg) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k.uppercase(), color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp, modifier = Modifier.width(96.dp))
        Text(v, color = vColor, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RnWideChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }, shape = RoundedCornerShape(10.dp),
        color = Fox.surface2, border = BorderStroke(1.dp, if (enabled) Fox.amber else Fox.line2)) {
        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, color = if (enabled) Fox.amber else Fox.faint, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RnPresetChip(text: String, sel: Boolean, onClick: () -> Unit) {
    Surface(Modifier.clickable { onClick() }, shape = RoundedCornerShape(9.dp),
        color = if (sel) Fox.amber else Fox.surface2, border = BorderStroke(1.dp, if (sel) Fox.amber else Fox.line)) {
        Box(Modifier.padding(horizontal = 11.dp, vertical = 7.dp)) {
            Text(text, color = if (sel) Fox.amberInk else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
