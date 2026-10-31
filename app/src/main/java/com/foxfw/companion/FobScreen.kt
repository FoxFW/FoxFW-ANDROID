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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun presetIdx(p: String) = when (p) {
    "AM270" -> FlipperRf.AM270
    "FM238" -> FlipperRf.FM238
    "FM476" -> FlipperRf.FM476
    else -> FlipperRf.AM650
}

/**
 * Tap a decoded sub → work the fob. Per-button pad + a live counter; where we hold the cipher
 * we forge a fresh valid frame per press (Route A phone-rendered, or Route B via ProtoPirate)
 * and the Flipper TXes it on the CC1101. Decode-only protocols fall back to a clearly-labelled
 * replay of the capture.
 */
@Composable
fun FobScreen(item: DecodeFeed.Item, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val f = item.frame
    var fob by remember { mutableStateOf<FobInfo?>(null) }
    var loadErr by remember { mutableStateOf(false) }
    var counter by remember { mutableStateOf(fobNextCounter(f.cnt)) }
    var devIdx by remember { mutableStateOf(FlipperRf.DEV_AUTO) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(item) {
        val fi = withContext(Dispatchers.Default) { ArkeEngine.fobInfo(f.make) }
        fob = fi
        loadErr = fi == null
    }

    val canForge = fob?.hasCipher == true && fob?.route != "replay" && f.serialHex != null

    fun send(code: Int) {
        val fi = fob ?: return
        if (busy) return
        scope.launch {
            busy = true
            status = "forging…"
            if (!canForge) {
                // replay the captured .sub (carries its own freq+preset; FAP honors it)
                val r = FlipperRf.tx(item.subText, fi.freqHz, devIdx)
                status = "replay: ${r.label}"
                busy = false
                return@launch
            }
            val res = withContext(Dispatchers.Default) {
                ArkeEngine.forge(f.make, f.serialHex ?: "0", counter, code)
            }
            when (res) {
                is ForgeResult.Raw -> {
                    val r = FlipperRf.tx(res.sub, fi.freqHz, devIdx, presetIdx(fi.preset))
                    status = r.label
                    if (r is TxResult.Ok) counter += 1 // advance only on a CONFIRMED TX (not Launched)
                }
                is ForgeResult.Protocol -> {
                    // Route B parks a .sub for ProtoPirate (no TX from here) — don't auto-advance
                    val r = FlipperRf.txProtocol(res.sub)
                    status = r.label
                }
                is ForgeResult.Err -> status = "forge: ${res.msg}"
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "‹ Back", color = Fox.amber, fontFamily = FoxMono, fontSize = 13.sp,
            modifier = Modifier.clickable { onBack() },
        )
        Text(f.make, color = Fox.fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            f.serialHex?.let { Text("serial 0x$it", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp) }
            fob?.let { Pill(routeLabel(it, canForge)) }
        }

        when {
            loadErr -> Text("Fob engine unavailable.", color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp)
            fob == null -> Text("loading…", color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp)
            else -> {
                // CC1101 selector
                Eyebrow("CC1101")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Int" to FlipperRf.DEV_INT, "Ext" to FlipperRf.DEV_EXT, "Auto" to FlipperRf.DEV_AUTO)
                        .forEach { (lbl, d) -> Chip(lbl, d == devIdx, Modifier.weight(1f)) { devIdx = d } }
                }

                if (canForge) {
                    // live counter + manual bump (re-sync if the car rolled ahead)
                    Eyebrow("Counter")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("0x%X".format(counter), color = Fox.fg, fontFamily = FoxMono, fontSize = 18.sp)
                        Spacer(Modifier.weight(1f))
                        Chip("−1", false) { if (counter > 0) counter -= 1 }
                        Chip("+1", false) { counter += 1 }
                    }
                    Eyebrow("Buttons — forge next valid code & TX")
                    fob!!.buttons.chunked(2).forEach { rowBtns ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowBtns.forEach { b ->
                                PadButton(b.label, Modifier.weight(1f), enabled = !busy) { send(b.code) }
                            }
                            if (rowBtns.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    Text(
                        "Replay only — this protocol's cipher isn't on the phone, so a re-sent capture " +
                            "won't open a rolling receiver (fixed-code gates/old fobs still work).",
                        color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp,
                    )
                    PadButton(if (busy) "sending…" else "Replay capture", Modifier.fillMaxWidth(), enabled = !busy) { send(0) }
                }
                if (status.isNotEmpty()) Text(status, color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp)
                if (!FlipperRf.connected) {
                    Text("Flipper not connected — TX will report 'not connected'.",
                        color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun routeLabel(fi: FobInfo, canForge: Boolean): String = when {
    !canForge -> "REPLAY"
    fi.route == "B" -> "FORGE · via ProtoPirate"
    else -> "FORGE"
}

@Composable
private fun Pill(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Fox.amberDim)
            .border(1.dp, Fox.amber.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Text(text, color = Fox.amber, fontFamily = FoxMono, fontSize = 9.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Eyebrow(text: String) =
    Text(text.uppercase(), color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp, letterSpacing = 1.sp)

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(9.dp))
            .background(if (selected) Fox.amber else Fox.surface2)
            .border(1.dp, if (selected) Fox.amber else Fox.line2, RoundedCornerShape(9.dp))
            .clickable { onClick() }.padding(vertical = 9.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Fox.amberInk else Fox.muted, fontFamily = FoxMono, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier = Modifier, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier.height(54.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Fox.amber else Fox.surface2)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Fox.amberInk else Fox.muted, fontFamily = FoxMono, fontSize = 14.sp,
            fontWeight = FontWeight.Bold)
    }
}
