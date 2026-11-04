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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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

    // Auto-clear a finished send result (Sent! / Send Failure) after 2s; "Sending…" stays until replaced.
    LaunchedEffect(status) {
        if (status.isNotEmpty() && !status.contains("Sending")) {
            kotlinx.coroutines.delay(2000)
            status = ""
        }
    }

    // No rolling counter in the decode => a fixed-code protocol (gates / old fobs): it simply
    // retransmits the captured signal. A rolling protocol we ALSO hold an on-phone encoder for can
    // additionally forge the next valid code; a rolling protocol without an encoder is replay-only.
    val isFixed = f.cnt == null
    val canEncodeRolling = !isFixed && fob?.hasCipher == true && fob?.route != "replay" && f.serialHex != null

    fun subFreq(sub: String): Int =
        sub.lineSequence().firstOrNull { it.startsWith("Frequency:") }?.substringAfter(":")?.trim()
            ?.toIntOrNull() ?: (fob?.freqHz ?: 433920000)

    // Which CC1101 the send went out on, prefixed to the status. In AUTO the FAP uses the external
    // module if one is attached, else the internal radio — so resolve it the same way from the last
    // receiver probe (cc1101Ext "ready" => external present). Unknown => plain [AUTO].
    fun devTag(): String = when (devIdx) {
        FlipperRf.DEV_INT -> "[INT] "
        FlipperRf.DEV_EXT -> "[EXT] "
        else -> when (CompanionCache.rx?.cc1101Ext) {
            "ready" -> "[AUTO:EXT] "
            null -> "[AUTO] "
            else -> "[AUTO:INT] "
        }
    }

    // Send the captured signal as-is (the RAW .sub). Available for every protocol.
    fun sendRaw() {
        if (fob == null || busy) return
        val tag = devTag()
        scope.launch {
            busy = true
            status = tag + "Sending…"
            val r = FlipperRf.tx(item.subText, subFreq(item.subText), devIdx)
            status = tag + if (r.ok) "Sent!" else "Send Failure — ${r.label}"
            busy = false
        }
    }

    // Generate + send the next valid rolling code — only when we hold the cipher on the phone.
    fun sendRolling(code: Int) {
        val fi = fob ?: return
        val serial = f.serialHex ?: return   // only reachable when canEncodeRolling (serial present)
        if (busy) return
        val tag = devTag()
        scope.launch {
            busy = true
            status = tag + "Sending…"
            val res = withContext(Dispatchers.Default) { ArkeEngine.forge(f.make, serial, counter, code) }
            when (res) {
                is ForgeResult.Raw -> {
                    val r = FlipperRf.tx(res.sub, fi.freqHz, devIdx, presetIdx(fi.preset))
                    status = tag + if (r.ok) "Sent!" else "Send Failure — ${r.label}"
                    if (r is TxResult.Ok) counter += 1 // advance only on a CONFIRMED TX
                }
                is ForgeResult.Protocol -> {
                    val r = FlipperRf.txProtocol(res.sub)
                    status = tag + r.label
                }
                is ForgeResult.Err -> status = tag + "Send Failure — ${res.msg}"
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
        f.serialHex?.let { Text("serial 0x$it", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp) }

        when {
            loadErr -> Text("Fob engine unavailable.", color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp)
            fob == null -> Text("loading…", color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp)
            else -> {
                Text(
                    when {
                        isFixed -> "Fixed Code Protocol — transmits the captured signal."
                        canEncodeRolling -> "Rolling code — send the next valid code, or replay the capture."
                        else -> "Rolling code — no on-phone encoder; replay the capture only."
                    },
                    color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp,
                )
                // CC1101 selector
                Eyebrow("CC1101")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Int" to FlipperRf.DEV_INT, "Ext" to FlipperRf.DEV_EXT, "Auto" to FlipperRf.DEV_AUTO)
                        .forEach { (lbl, d) -> Chip(lbl, d == devIdx, Modifier.weight(1f)) { devIdx = d } }
                }

                if (canEncodeRolling) {
                    // live counter + manual bump (re-sync if the receiver rolled ahead)
                    Eyebrow("Counter")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("0x%X".format(counter), color = Fox.fg, fontFamily = FoxMono, fontSize = 18.sp)
                        Spacer(Modifier.weight(1f))
                        Chip("−1", false) { if (counter > 0) counter -= 1 }
                        Chip("+1", false) { counter += 1 }
                    }
                }
                // Every protocol gets the D-pad: centre = RAW replay; the directions carry the fob's
                // buttons (rolling generate+TX) where it has them, else inert arrows.
                Eyebrow(if (canEncodeRolling) "Buttons generate next valid code & TX" else "Send", center = canEncodeRolling)
                FobDpad(fob!!.buttons, canEncodeRolling, busy, onSend = { sendRolling(it) }, onRaw = { sendRaw() })
                if (status.isNotEmpty()) Text(status, color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (!FlipperRf.connected) {
                    Text("Flipper not connected — TX will report 'not connected'.",
                        color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
                }
            }
        }
    }
}

/** The fob D-pad — shown on every protocol. Fixed positions by function: top Lock, bottom Unlock,
 *  left Trunk, right Panic; the centre always replays the RAW capture. A position the protocol doesn't
 *  have (or any fixed-code page) shows an inert arrow in the same amber box. Rolling buttons that don't
 *  map to one of the four (e.g. "Remote") stack underneath. `canSend` gates the rolling directions. */
@Composable
private fun FobDpad(buttons: List<FobButton>, canSend: Boolean, busy: Boolean, onSend: (Int) -> Unit, onRaw: () -> Unit) {
    val top = if (canSend) buttons.firstOrNull { it.label.equals("Lock", true) } else null
    val bottom = if (canSend) buttons.firstOrNull { it.label.equals("Unlock", true) } else null
    val left = if (canSend) buttons.firstOrNull { it.label.contains("Trunk", true) } else null
    val right = if (canSend) buttons.firstOrNull { it.label.contains("Panic", true) } else null
    val used = listOfNotNull(top, bottom, left, right).map { it.code }.toSet()
    val extras = if (canSend) buttons.filter { it.code !in used } else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f)); DpadCell(top, 0f, busy, Modifier.weight(1f), onSend); Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DpadCell(left, -90f, busy, Modifier.weight(1f), onSend)
            PadButton("RAW", Modifier.weight(1f), enabled = !busy) { onRaw() }
            DpadCell(right, 90f, busy, Modifier.weight(1f), onSend)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f)); DpadCell(bottom, 180f, busy, Modifier.weight(1f), onSend); Spacer(Modifier.weight(1f))
        }
        extras.chunked(2).forEach { rowBtns ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowBtns.forEach { b -> PadButton(b.label, Modifier.weight(1f), enabled = !busy) { onSend(b.code) } }
                if (rowBtns.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One directional cell: a live fob button, or an inert arrow slot (same amber box, grey glyph). */
@Composable
private fun DpadCell(b: FobButton?, rot: Float, busy: Boolean, modifier: Modifier = Modifier, onSend: (Int) -> Unit) {
    if (b == null) {
        // Inert slot: the whole amber button is faded out (disabled look), keeping the orange but
        // dimming it, with the arrow in the same dark ink as the live labels — no clashing grey.
        Box(
            modifier.height(54.dp).clip(RoundedCornerShape(12.dp)).alpha(0.4f).background(Fox.amber),
            contentAlignment = Alignment.Center,
        ) {
            // one triangle rotated per direction, so all four are identical in size/shape
            Text("▲", color = Fox.amberInk, fontFamily = FoxMono,
                fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.rotate(rot))
        }
    } else {
        PadButton(b.label, modifier, enabled = !busy) { onSend(b.code) }
    }
}

@Composable
private fun Eyebrow(text: String, center: Boolean = false) =
    Text(text.uppercase(), color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp, letterSpacing = 1.sp,
        modifier = if (center) Modifier.fillMaxWidth() else Modifier,
        textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null)

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
