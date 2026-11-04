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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.launch

/** One launchable item: a FAP by path, or a built-in app by name. */
private data class Launchable(val label: String, val target: String, val group: String)

// True built-ins (not FAPs) that launch by name over RPC.
private val BUILTINS = listOf(
    Launchable("Sub-GHz", "Sub-GHz", "Built-in"),
    Launchable("Archive", "Archive", "Built-in"),
    Launchable("Settings", "Settings", "Built-in"),
)

@Composable
fun FlipperLauncherScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ble = FoxApplication.instance.ble
    val connected = ble.conn == Conn.CONNECTED

    val items = remember { mutableStateListOf<Launchable>() }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (connected) "Loading apps from the Flipper…" else "Connect the Flipper (Device tab) first.") }
    var filter by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<String?>(null) }

    fun reload() {
        if (loading) return // re-entrancy guard: a second reload would clear items under the first
        if (!connected) { status = "Connect the Flipper first."; return }
        loading = true; items.clear(); items.addAll(BUILTINS)
        scope.launch {
            // Enumerate /ext/apps/<category>/*.fap
            val cats = ble.storageList("/ext/apps")?.filter { it.isDir } ?: emptyList()
            for (cat in cats) {
                val faps = ble.storageList("/ext/apps/${cat.name}")?.filter { !it.isDir && it.name.endsWith(".fap") } ?: emptyList()
                for (f in faps) {
                    items.add(Launchable(f.name.removeSuffix(".fap"), "/ext/apps/${cat.name}/${f.name}", cat.name))
                }
            }
            loading = false
            status = "${items.size} features. Tap one to open it on the Flipper."
        }
    }

    // initial load
    remember(connected) { if (connected && items.isEmpty()) reload(); connected }

    fun launch(item: Launchable) {
        if (!connected) { status = "Connect the Flipper first."; return }
        status = "Opening ${item.label}…"
        scope.launch {
            val ok = ble.appStartSafe(item.target, "")
            status = if (ok) "✓ Opened ${item.label} on the Flipper."
            else "✗ Couldn't open ${item.label} — if the Flipper is on its first-boot setup, finish that first (the loader is locked), or close the current app."
            if (ok) running = item.label
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column { Text("App Launcher", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Eyebrow("OPEN ANY FLIPPER FEATURE") }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .border(1.dp, Fox.line, RoundedCornerShape(10.dp)).clickable { reload() },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Refresh, null, tint = Fox.fg, modifier = Modifier.size(17.dp))
            }
        }

        if (running != null) FoxCard { Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Running"); Spacer(Modifier.width(8.dp)); Text(running!!, color = Fox.live, fontFamily = FoxMono, fontSize = 12.sp)
        } }

        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            label = { Text("Filter", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(),
        )

        val shown = items.filter { filter.isBlank() || it.label.contains(filter, true) || it.group.contains(filter, true) }
        shown.groupBy { it.group }.forEach { (group, list) ->
            FoxCard { Column {
                Row { Eyebrow(group); Spacer(Modifier.weight(1f)); Pill("${list.size}", Fox.faint, Fox.surface2) }
                Spacer(Modifier.height(6.dp))
                list.forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable { launch(item) }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, color = Fox.fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("open →", color = Fox.amber, fontFamily = FoxMono, fontSize = 10.sp)
                    }
                }
            } }
        }

        if (loading) FoxCard { Text("Loading…", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp) }
        FoxCard { Text(status, color = if (status.startsWith("✗")) Fox.amber else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp) }
    }
}
