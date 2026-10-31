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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DiagResult(val name: String, val pass: Boolean, val detail: String)

@Composable
fun DiagnosticsScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<DiagResult>() }
    var ran by remember { mutableStateOf(false) }

    fun runAll() {
        running = true; ran = true; results.clear()
        scope.launch {
            val out = withContext(Dispatchers.Default) { diagRun() }
            results.clear(); results.addAll(out); running = false
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
                Eyebrow("ON-DEVICE SELF-TEST")
                Text("Diagnostics", color = Fox.fg, fontFamily = FoxMono, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(14.dp))

        FoxCard(Modifier.fillMaxWidth()) {
            Column {
                Text("Runs the native crypto engines against known test vectors on THIS phone — " +
                    "proves the cracker actually works on your hardware (not just on the build machine).",
                    color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (running) Fox.surface2 else Fox.amber)
                    .clickable(enabled = !running) { runAll() }.padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center) {
                    Text(if (running) "Running…" else "RUN SELF-TESTS",
                        color = if (running) Fox.faint else Fox.amberInk, fontFamily = FoxMono,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (ran) {
            val allPass = results.isNotEmpty() && results.all { it.pass }
            FoxCard(Modifier.fillMaxWidth(), border = if (allPass) Fox.live.copy(alpha = 0.5f) else Fox.line) {
                Column {
                    Eyebrow(if (running) "RUNNING" else if (allPass) "ALL PASS" else "RESULTS",
                        if (allPass) Fox.live else Fox.faint)
                    Spacer(Modifier.height(8.dp))
                    results.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(if (r.pass) "PASS" else "FAIL",
                                color = if (r.pass) Fox.live else Fox.warn,
                                fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(54.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
                                Text(r.detail, color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp)
                            }
                        }
                    }
                    if (results.isEmpty() && !running)
                        Text("No results.", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// --- the actual tests (pure native, no Flipper needed) ---
private fun diagRun(): List<DiagResult> {
    val out = ArrayList<DiagResult>()

    if (!NativeSolver.available) {
        out.add(DiagResult("Native engine (libfoxsolver)", false, "failed to load — JNI unavailable"))
        return out
    }
    out.add(DiagResult("Native engine (libfoxsolver)", true, "loaded on arm64"))

    // 1) Fiat dictionary — UID 0x0ABCDEF0 must resolve to B8 92 80 AE CC 37 @ epoch 15360
    run {
        val btn = intArrayOf(2, 2, 2, 2)
        val ctrl = intArrayOf(528, 529, 530, 531)
        val hop = longArrayOf(0x149FD72BL, 0x989FDCB1L, 0x1A9F4995L, 0x969ECFF8L)
        val d = NativeSolver.dict(0x0ABCDEF0, btn, ctrl, hop, Runtime.getRuntime().availableProcessors())
        if (d == null || d.size < 10) {
            out.add(DiagResult("Fiat dictionary (0x0ABCDEF0)", false, "no hit"))
        } else {
            val key = (0..5).joinToString(" ") { "%02X".format(d[it].toInt() and 0xFF) }
            val ep = ((d[6].toInt() and 0xFF) shl 24) or ((d[7].toInt() and 0xFF) shl 16) or
                ((d[8].toInt() and 0xFF) shl 8) or (d[9].toInt() and 0xFF)
            val ok = key == "B8 92 80 AE CC 37" && ep == 15360
            out.add(DiagResult("Fiat dictionary (0x0ABCDEF0)", ok, "key $key · epoch $ep"))
        }
    }

    // 2) Renault seed round-trip — forge, recover, confirm cnt/btn + re-encode
    run {
        val serial = 0x7FA10044
        val f = NativeSolver.seedForge(serial, 4660, 4, 0x1F3D)
        val iv = NativeSolver.seedRecover(f)
        if (iv == null || iv.size < 4) {
            out.add(DiagResult("Renault seed round-trip", false, "recover failed"))
        } else {
            val i0 = iv[0].toInt() and 0xFF; val i1 = iv[1].toInt() and 0xFF
            val i2 = iv[2].toInt() and 0xFF; val i3 = iv[3].toInt() and 0xFF
            val btn = i0 and 0x0F
            val cnt = (i0 ushr 4 and 0x0F) or (i1 shl 4) or ((i2 and 0x0F) shl 12)
            val seed = ((i2 and 0xF0) shl 8) or i3
            val reForge = NativeSolver.seedForge(serial, cnt, btn, seed)
            val matches = reForge.contentEquals(f)
            val ok = cnt == 4660 && btn == 4 && matches
            out.add(DiagResult("Renault seed round-trip", ok, "cnt $cnt · btn $btn · seed 0x%04X · re-encode %s".format(seed and 0xFFFF, if (matches) "ok" else "MISMATCH")))
        }
    }

    // 3) Renault prediction — recovered seed must predict the true next code
    run {
        val serial = 0x7FA10044
        val f0 = NativeSolver.seedForge(serial, 4660, 4, 0x1F3D)
        val iv = NativeSolver.seedRecover(f0)
        if (iv == null || iv.size < 4) {
            out.add(DiagResult("Renault next-code prediction", false, "recover failed"))
        } else {
            val i0 = iv[0].toInt() and 0xFF; val i1 = iv[1].toInt() and 0xFF
            val i2 = iv[2].toInt() and 0xFF; val i3 = iv[3].toInt() and 0xFF
            val btn = i0 and 0x0F
            val cnt = (i0 ushr 4 and 0x0F) or (i1 shl 4) or ((i2 and 0x0F) shl 12)
            val seed = ((i2 and 0xF0) shl 8) or i3
            val predicted = NativeSolver.seedForge(serial, cnt + 1, btn, seed)
            val trueNext = NativeSolver.seedForge(serial, 4661, 4, 0x1F3D)
            val ok = predicted.contentEquals(trueNext)
            out.add(DiagResult("Renault next-code prediction", ok, if (ok) "cnt 4661 matches true next code" else "prediction != true next"))
        }
    }

    return out
}
