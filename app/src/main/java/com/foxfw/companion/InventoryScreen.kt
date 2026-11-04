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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.launch

/** SD Inventory: scan /ext/subghz and report what you have, grouped by protocol. */
@Composable
fun InventoryScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ble = FoxApplication.instance.ble
    val connected = ble.conn == Conn.CONNECTED

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (connected) "Scan to inventory the captures on your SD." else "Connect the Flipper (Device tab) first.") }
    var report by remember { mutableStateOf<SdInventory.Report?>(null) }
    val deep = remember { mutableStateListOf<Pair<String, Int>>() }

    fun scan() {
        if (!connected || busy) { if (!connected) status = "Connect the Flipper first."; return }
        busy = true; report = null; deep.clear(); status = "Scanning /ext/subghz…"
        scope.launch {
            val r = SdInventory.scan(ble) { n -> status = "Scanning… $n files" }
            report = r
            status = if (r == null) "✗ Couldn't list /ext/subghz (loader busy? hold Back)."
            else "✓ ${r.totalSubs} captures across ${r.byProtocol.size} protocols (${r.scanned} .sub scanned)."
            busy = false
        }
    }

    fun deepDecode() {
        val r = report ?: return
        if (r.rawPaths.isEmpty() || busy) return
        busy = true; deep.clear(); status = "Deep-decoding ${r.rawPaths.size} RAW captures…"
        scope.launch {
            val res = SdInventory.deepDecode(ble, r.rawPaths) { d, t -> status = "Decoding RAW… $d/$t" }
            deep.addAll(res)
            status = "✓ Deep-decode done (${r.rawPaths.size} RAW)."
            busy = false
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column { Text("SD Inventory", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Eyebrow("WHAT'S ON YOUR SD") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InvBtn(if (busy && report == null) "Scanning…" else "Scan /ext/subghz", enabled = connected && !busy) { scan() }
            report?.let { r -> if (r.rawPaths.isNotEmpty()) InvBtn("Deep-decode RAW (${r.rawPaths.size})", enabled = !busy, accent = false) { deepDecode() } }
        }

        report?.let { r ->
            FoxCard { Column {
                Row { Eyebrow("By protocol"); Spacer(Modifier.weight(1f)); Pill("${r.totalSubs}", Fox.amber, Fox.surface2) }
                Spacer(Modifier.height(6.dp))
                if (r.byProtocol.isEmpty()) Text("no .sub captures found", color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp)
                r.byProtocol.forEach { (label, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Fox.fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("×$n", color = Fox.amber, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } }
        }

        if (deep.isNotEmpty()) FoxCard { Column {
            Eyebrow("RAW deep-decode")
            Spacer(Modifier.height(6.dp))
            deep.forEach { (label, n) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = if (label == "(undecoded)") Fox.faint else Fox.fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("×$n", color = Fox.live, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        } }

        FoxCard { Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (connected) "LINKED" else "OFFLINE", if (connected) Fox.live else Fox.faint, Fox.surface2)
            Spacer(Modifier.width(8.dp))
            Text(status, color = if (status.startsWith("✗")) Fox.warn else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
        } }
    }
}

@Composable
private fun InvBtn(label: String, enabled: Boolean, accent: Boolean = true, onClick: () -> Unit) {
    val bg = if (!enabled) Fox.surface2 else if (accent) Fox.amber else Fox.surface
    val fg = if (!enabled) Fox.faint else if (accent) Fox.amberInk else Fox.fg
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
        .border(1.dp, Fox.line, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled) { onClick() }
        .padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
