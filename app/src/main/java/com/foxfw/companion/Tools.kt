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
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.GradientBar
import com.foxfw.companion.ui.GradientText
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Tool(val id: String, val name: String, val desc: String, val icon: ImageVector, val cat: String = "general")

private val tools = listOf(
    // --- General ---
    Tool("files", "File Manager", "browse · pull · push SD", Icons.Outlined.Folder),
    Tool("remote", "Screen Mirror", "mirror + control", Icons.Outlined.Screenshot),
    Tool("fwupdate", "Firmware Update", "latest FoxFW, auto", Icons.Outlined.SystemUpdate),
    Tool("backup", "Backup & Restore", "SD + device /int", Icons.Outlined.Backup),
    Tool("flippercli", "Flipper CLI", "terminal over BLE/USB", Icons.Outlined.Terminal),
    Tool("launcher", "App Launcher", "open any Flipper feature", Icons.Outlined.GridView),
    Tool("painter", "Painter", "draw on the Flipper", Icons.Outlined.Brush),
    Tool("hue", "Hue Lights", "CYD proxy · dim & colour", Icons.Outlined.Lightbulb),
    Tool("inventory", "SD Inventory", "what’s on your SD", Icons.Outlined.Inventory2),
    Tool("history", "History", "decodes · cracks · share", Icons.Outlined.History),
    Tool("diag", "Diagnostics", "on-device self-test", Icons.Outlined.Memory),
    // --- RF Analysis ---
    Tool("keeloq", "KeeLoq Crack", "204-key store", Icons.Outlined.Lock, "rf"),
    Tool("hitag2", "Hitag2 Recover", "SAT offload", Icons.Outlined.Key, "rf"),
    Tool("renault", "Renault Seed", "seed → clone", Icons.Outlined.Key, "rf"),
    Tool("subghz_trim", "SubGHz Trimmer", "view · trim .sub", Icons.Outlined.GraphicEq, "rf"),
    Tool("siggen", "Signal Gen", "replay / TX", Icons.Outlined.GraphicEq, "rf"),
    Tool("freq", "Freq Analyzer", "sweep 300–928M", Icons.Outlined.Radar, "rf"),
    Tool("band", "Band Analyzer", "2.4/5 GHz · phone WiFi+BLE", Icons.Outlined.Wifi, "rf"),
    Tool("wificrack", "Handshake Crack", "WPA PMKID · on-phone", Icons.Outlined.Password, "rf"),
    Tool("lanscan", "LAN Scanner", "find hosts · on-phone", Icons.Outlined.Lan, "rf"),
    Tool("bleexplore", "BLE Explorer", "GATT · scan · read", Icons.Outlined.Bluetooth, "rf"),
    Tool("pandora", "Pandora Clone", "D-605 + MAX fob", Icons.Outlined.Sensors, "rf"),
    Tool("carclone", "Car & Gate Clone", "VAG · KIA · PSA · gates", Icons.Outlined.DirectionsCar, "rf"),
    Tool("chameleon", "ChameleonUltra", "NFC over BLE", Icons.Outlined.Contactless, "rf"),
    Tool("mfkey", "NFC MFKey", "recover Mifare keys", Icons.Outlined.Key, "rf"),
    Tool("psa", "PSA Offload", "TEA brute · BLE", Icons.Outlined.Memory, "rf"),
    Tool("tagmaker", "Tag Maker", "NFC/RFID · make & emulate", Icons.Outlined.Contactless, "rf"),
    // --- ESP32 (needs a board) ---
    Tool("esp32", "Esp32", "BFFB v2 · drive it all", Icons.Outlined.Router, "esp32"),
    Tool("esp32cli", "ESP32 CLI", "terminal over relay / USB", Icons.Outlined.Terminal, "esp32"),
    Tool("esp_wifi", "ESP32 WiFi", "scan · sniff · attack", Icons.Outlined.Wifi, "esp32"),
    Tool("esp_ble", "ESP32 BLE", "scan · spam · tags", Icons.Outlined.Bluetooth, "esp32"),
    Tool("esp_gps", "ESP32 GPS", "fix · track · wardrive", Icons.Outlined.GpsFixed, "esp32"),
    Tool("esp_ir", "ESP32 IR", "recv · send · TV-B-Gone", Icons.Outlined.SettingsRemote, "esp32"),
    Tool("esp_subghz", "ESP32 SubGHz", "rx · tx · frequency", Icons.Outlined.Radar, "esp32"),
    Tool("esp_rfid", "ESP32 RFID", "scan · read · emulate", Icons.Outlined.Contactless, "esp32"),
)

@Composable
fun ToolsScreen(fle: FlipperBle, category: String, openTarget: String?, onOpenTarget: (String) -> Unit, onClose: () -> Unit) {
    val listScroll = rememberScrollState() // hoisted: survives opening/closing a tile, resets on tab change
    val toolsCtx = androidx.compose.ui.platform.LocalContext.current
    if (openTarget == "hitag2") {
        Hitag2OffloadScreen(fle, onClose); return
    }
    if (openTarget == "renault") {
        RenaultSeedScreen(onClose); return
    }
    if (openTarget == "keeloq") {
        KeeLoqCrackScreen(onClose); return
    }
    if (openTarget == "siggen") {
        SignalGenScreen(onClose); return
    }
    if (openTarget == "freq") {
        FreqAnalyzerScreen(onClose); return
    }
    if (openTarget == "band") {
        BandAnalyzerScreen(onClose); return
    }
    if (openTarget == "wificrack") {
        WifiCrackScreen(onClose); return
    }
    if (openTarget == "lanscan") {
        LanScanScreen(onClose); return
    }
    if (openTarget == "bleexplore") {
        BleGattScreen(onClose); return
    }
    if (openTarget == "esp32") {
        Esp32Screen(onClose); return
    }
    if (openTarget != null && openTarget.startsWith("esp_")) {
        EspTerminalScreen(openTarget.removePrefix("esp_"), onClose); return
    }
    if (openTarget == "esp32cli") {
        // Rendered as a full-screen layer in FoxApp (over the Scaffold); nothing to draw here.
        return
    }
    if (openTarget == "remote") {
        RemoteScreen(fle, onClose); return
    }
    if (openTarget == "subghz_trim") {
        SubGhzViewerScreen(fle, onClose); return
    }
    if (openTarget == "painter") {
        PainterScreen(fle, onClose); return
    }
    if (openTarget == "hue") {
        HueScreen(onClose); return
    }
    if (openTarget == "chameleon") {
        ChameleonScreen(onClose); return
    }
    if (openTarget == "mfkey") {
        NfcMfkeyScreen(fle, onClose); return
    }
    if (openTarget == "pandora") {
        PandoraScreen(onClose); return
    }
    if (openTarget == "carclone") {
        CarCloneScreen(onClose); return
    }
    if (openTarget == "psa") {
        PsaScreen(onClose); return
    }
    if (openTarget == "tagmaker") {
        TagMakerScreen(onClose); return
    }
    if (openTarget == "launcher") {
        FlipperLauncherScreen(onClose); return
    }
    if (openTarget == "flippercli") {
        // Rendered as a full-screen layer in FoxApp (over the Scaffold); nothing to draw here.
        return
    }
    if (openTarget == "fwupdate") {
        FwUpdateScreen(onClose); return
    }
    if (openTarget == "history") {
        HistoryScreen(onClose); return
    }
    if (openTarget == "inventory") {
        InventoryScreen(onClose); return
    }
    if (openTarget == "backup") {
        BackupScreen(onClose); return
    }
    if (openTarget == "files") {
        FileManagerScreen(onClose); return
    }
    if (openTarget == "diag") {
        DiagnosticsScreen(onClose); return
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(listScroll)
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 2-col grid, one category at a time (general / rf / esp32).
        val shown = tools.filter { it.cat == category }
        // Every ESP32 tile needs a board attached: available when the Device-page detector reports an
        // external ESP32, or a board is plugged into the phone's USB-OTG. Without one we keep the tiles
        // VISIBLE but disabled (greyed, non-tappable) rather than hiding them, so the layout never shifts.
        val espE = CompanionCache.rx?.esp32
        val espAvail = espE == "fox" || espE == "notfox" || Esp32UsbLink.find(toolsCtx) != null
        shown.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                row.forEach { t ->
                    val enabled = t.cat != "esp32" || espAvail
                    ToolCard(t, Modifier.weight(1f), enabled = enabled) {
                        if (enabled) onOpenTarget(t.id)
                        else android.widget.Toast.makeText(
                            toolsCtx,
                            "Connect your ESP32 first — plug it into the phone (USB-OTG), or link the Flipper on the Device tab.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolCard(t: Tool, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val base = modifier.clip(shape)
        .background(Brush.verticalGradient(listOf(Fox.surface2, Fox.surface)))
        .border(1.dp, Fox.line, shape)
    // Disabled tiles are still tappable (greyed) so the tap can explain why they're off.
    Box(base.alpha(if (enabled) 1f else 0.42f).clickable { onClick() }) {
        Column(Modifier.padding(14.dp).heightIn(min = 118.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // recessed, screen-dark icon chip with an amber rim — reads like a panel key
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(Fox.screen)
                .border(1.dp, Fox.amber.copy(alpha = 0.45f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center) {
                Icon(t.icon, null, tint = Fox.amber, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.weight(1f))
            // maxLines=1 on the title + a reserved 2-line desc keep every tile's title on the same
            // baseline across a row, regardless of how long each description is.
            Text(t.name, color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(if (enabled) t.desc else "connect an ESP32", color = Fox.muted, fontFamily = FoxMono,
                fontSize = 11.sp, minLines = 2, maxLines = 2)
        }
    }
}

/* ---------------- Hitag2 guess-and-determine offload (the feature) ---------------- */
private enum class Phase { IDLE, SOLVE, DONE, FAILED }

data class SatTrace(val name: String, val btn: Int, val ctrl: Int, val hop: Long)

@Composable
private fun Hitag2OffloadScreen(fle: FlipperBle, onClose: () -> Unit) {
    // Frame source, in priority: (1) saved Fiat V1 .subs the user loaded + picked a fob from,
    // (2) a live BLE offload job, (3) a baked-in SAMPLE set so the engine can be exercised standalone.
    val scope = rememberCoroutineScope()
    val job = fle.incoming
    var loaded by remember { mutableStateOf<List<FiatCaptures.Frame>?>(null) } // all parsed saved frames
    var chosenUid by remember { mutableStateOf<Long?>(null) }                  // the fob (UID) the user picked
    var loadStatus by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val groups = loaded?.groupBy { it.uid } ?: emptyMap()

    val demoTraces = remember {
        listOf(
            SatTrace("Great_kot", 2, 345, 0xE1371615L),
            SatTrace("Big_sidorovich", 4, 347, 0xE0F4918EL),
            SatTrace("Liquid_juzyk", 1, 346, 0xE3F649B1L),
            SatTrace("Ooo", 2, 343, 0xE1E3A735L),
        )
    }
    val traces = remember(chosenUid, job, loaded) {
        when {
            chosenUid != null -> (loaded?.filter { it.uid == chosenUid } ?: emptyList())
                .map { SatTrace(it.name, it.btn, it.ctrl, it.hop) }
            loaded != null -> emptyList() // loaded several fobs — waiting for the user to pick one above
            job != null -> List(job.btn.size) { i ->
                SatTrace("frame ${i + 1}", job.btn[i], job.ctrl[i], job.hop[i] and 0xFFFFFFFFL)
            }
            else -> demoTraces
        }
    }
    val uid = chosenUid ?: job?.uid ?: 0x75E1D628L
    val isDemo = chosenUid == null && job == null && loaded == null
    // The user must PICK which captured frames go into the crack — no blind auto-crack.
    val picked = remember(traces) { androidx.compose.runtime.mutableStateListOf<Int>() }
    fun toggle(i: Int) { if (i in picked) picked.remove(i) else picked.add(i) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    fun loadSaved() {
        if (fle.conn != Conn.CONNECTED) { loadStatus = "Connect the Flipper first."; return }
        loading = true; chosenUid = null; loaded = null; loadStatus = "Scanning the SD (/ext/subghz + subfolders)…"
        // clear a stale finished-crack result so a reload can't render a DONE card against new/empty traces
        if (!CrackState.running) { CrackState.done = false; CrackState.failed = false; CrackState.result = null }
        scope.launch {
            val frames = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FlipperRf.loadFiatCaptures { scanned, found -> loadStatus = "Scanning SD… $scanned files, $found Fiat so far" }
            }
            loading = false
            when {
                frames == null -> loadStatus = "Couldn't read the Flipper SD (is it connected?)."
                frames.isEmpty() -> loadStatus = "No decoded Fiat V1 captures found on the SD (/ext/subghz + subfolders). On the Flipper, read + Save a Fiat fob first."
                else -> {
                    loaded = frames
                    val g = frames.groupBy { it.uid }
                    loadStatus = "Found ${frames.size} frame(s) across ${g.size} fob(s)."
                    if (g.size == 1) chosenUid = g.keys.first() // one fob → select it
                }
            }
        }
    }
    // Phase is derived from the shared CrackState, which lives in the foreground service — so the
    // crack keeps running (and this screen re-binds to it) across navigation, lock, and app switch.
    val phase = when {
        CrackState.running -> Phase.SOLVE
        CrackState.done -> Phase.DONE
        CrackState.failed -> Phase.FAILED
        else -> Phase.IDLE
    }
    val statesScanned = CrackState.states
    val elapsed = CrackState.elapsed
    val result = CrackState.result

    fun startCrack() {
        val sel = traces.filterIndexed { i, _ -> i in picked }
        if (sel.size < 2) return
        CrackService.start(
            ctx, uid,
            sel.map { it.btn }.toIntArray(),
            sel.map { it.ctrl }.toIntArray(),
            sel.map { it.hop }.toLongArray(),
        )
    }
    fun clearResult() { CrackState.done = false; CrackState.failed = false; CrackState.result = null }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hitag2 Recover", color = Fox.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("GUESS-AND-DETERMINE · PHONE OFFLOAD", color = Fox.muted, fontFamily = FoxMono,
                    fontSize = 10.sp, letterSpacing = 1.sp)
            }
        }

        // Load decoded Fiat V1 captures from the Flipper SD and pick which fob to crack.
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .border(1.dp, Fox.line2, RoundedCornerShape(14.dp))
                .clickable(enabled = !loading) { loadSaved() }.padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (loading) "SCANNING…" else "LOAD SAVED CAPTURES (/ext/subghz)", color = Fox.fg,
                fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
        }
        loadStatus?.let {
            Text(it, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
        }
        if (groups.size > 1) {
            FoxCard {
                Column {
                    Eyebrow("Pick a fob")
                    Spacer(Modifier.height(8.dp))
                    groups.forEach { (u, fr) ->
                        val sel = u == chosenUid
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { chosenUid = u }
                                .padding(vertical = 9.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("0x%08X".format(u), color = if (sel) Fox.amber else Fox.fg, fontFamily = FoxMono,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${fr.size} frames", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
                            if (sel) { Spacer(Modifier.width(8.dp)); Text("✓", color = Fox.amber, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }

        FoxCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Capture set")
                    Spacer(Modifier.weight(1f))
                    if (isDemo) Text("SAMPLE", color = Fox.violet, fontFamily = FoxMono, fontSize = 9.sp, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("Tap to pick which frames to crack — need at least 2.", color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))
                Row {
                    Text("UID", color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("0x%08X".format(uid), color = Fox.amber, fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${picked.size}/${traces.size} picked", color = if (picked.size >= 2) Fox.live else Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                traces.forEachIndexed { i, tr ->
                    val sel = i in picked
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { toggle(i) }
                            .padding(vertical = 8.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                                .background(if (sel) Fox.amber else Fox.surface2)
                                .border(1.dp, if (sel) Fox.amber else Fox.line2, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center,
                        ) { if (sel) Text("✓", color = Fox.amberInk, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(10.dp))
                        Text(tr.name, color = Fox.fg, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("btn %d · ctrl %d · %08X".format(tr.btn, tr.ctrl, tr.hop),
                            color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
                    }
                }
            }
        }

        when (phase) {
            Phase.DONE -> traces.getOrNull(0)?.let { t ->
                ResultCard(uid, result, t, elapsed) {
                    if (fle.conn == Conn.CONNECTED) fle.sendResult(true, result)
                }
            }
            Phase.FAILED -> FoxCard(border = Fox.crit.copy(alpha = 0.5f)) {
                Column {
                    Text("No key found", color = Fox.crit, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (NativeSolver.available) "The captures may have bit errors, or they aren’t all from one fob. Need ≥2 clean same-UID frames."
                        else "Native solver unavailable on this device.",
                        color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    CrackButton("TRY AGAIN") { clearResult(); startCrack() }
                }
            }
            Phase.SOLVE -> {
                SolveCard(statesScanned, elapsed)
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Fox.line2, RoundedCornerShape(14.dp))
                        .clickable { CrackService.cancel(ctx) }.padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("CANCEL", color = Fox.muted, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp) }
                Text(
                    "Runs in the background — you can leave this screen, lock the phone, or use other apps.",
                    color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp,
                )
            }
            else -> {
                val ready = picked.size >= 2
                CrackButton(if (ready) "CRACK ${picked.size} FRAMES ON PHONE" else "PICK AT LEAST 2 FRAMES", enabled = ready) {
                    clearResult(); startCrack()
                }
            }
        }
    }
}

@Composable
private fun SolveCard(states: Long, elapsed: Int) {
    val t = rememberInfiniteTransition(label = "solve")
    val f by t.animateFloat(0.14f, 0.92f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "bar")
    val dictPhase = CrackState.phaseDict
    FoxCard(border = Fox.violet.copy(alpha = 0.42f)) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Cracking on phone…", color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Pill(if (dictPhase) "Dict" else "Solve", Fox.violet, Fox.violetDim, dot = true)
            }
            Spacer(Modifier.height(12.dp))
            GradientBar(f)
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth()) {
                Text((if (dictPhase) "dictionary · " else "state recovery · ") + "${elapsed}s",
                    color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text((if (dictPhase) "%,d keys×epochs" else "%,d states").format(states),
                    color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CrackButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
    val shaped = if (enabled) base.background(Fox.gradient()).clickable { onClick() } else base.background(Fox.surface2)
    Box(shaped.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Fox.amberInk else Fox.muted, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun ResultCard(uid: Long, key: ByteArray?, f0: SatTrace, elapsed: Int, onSend: () -> Unit) {
    val absolute = CrackState.absoluteKey
    val method = CrackState.method
    val verify = CrackState.verify
    val codes = CrackState.predictedCodes
    val k4 = key?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
    val k5 = key?.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
    val cloneHi = ((key?.getOrNull(2)?.toInt()?.and(0xFF) ?: 0) shl 8) or (key?.getOrNull(3)?.toInt()?.and(0xFF) ?: 0)
    val cloneLo = ((key?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0) shl 8) or (key?.getOrNull(5)?.toInt()?.and(0xFF) ?: 0)
    val txScope = rememberCoroutineScope()
    val txConnected = FlipperRf.connected
    var fiatVariant by remember { mutableStateOf("B") }
    var fiatPreset by remember { mutableStateOf(FlipperRf.AM650) }
    var txBusy by remember { mutableStateOf(false) }
    var txStatus by remember { mutableStateOf("") }
    fun txFiat(c: Int, h: Long) {
        if (!txConnected) { txStatus = "Connect the Flipper (Device tab) to transmit."; return }
        txBusy = true; txStatus = "Rendering & transmitting ctrl $c…"
        txScope.launch {
            val pstr = when (fiatPreset) {
                FlipperRf.AM270 -> "FuriHalSubGhzPresetOok270Async"
                FlipperRf.FM238 -> "FuriHalSubGhzPreset2FSKDev238Async"
                FlipperRf.FM476 -> "FuriHalSubGhzPreset2FSKDev476Async"
                else -> "FuriHalSubGhzPresetOok650Async"
            }
            val sub = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                ArkeEngine.Fiat.renderSub(uid.toInt(), f0.btn, c, h, fiatVariant, 433920000, pstr)
            }
            if (sub == null) { txStatus = "Render failed."; txBusy = false; return@launch }
            val r = FlipperRf.tx(sub, 433920000, FlipperRf.txDevice, fiatPreset)
            txStatus = if (r.ok) "⚡ Sent ctrl $c (TE-$fiatVariant). No response? try TE-A / AM270." else "TX failed: ${r.label}"
            txBusy = false
        }
    }
    FoxCard(border = Fox.live.copy(alpha = 0.5f)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, tint = Fox.live, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (absolute) "Absolute key recovered" else "Clone secret recovered",
                    color = Fox.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${elapsed}s", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
            }
            Spacer(Modifier.height(12.dp))
            KeyRow("UID", "0x%08X".format(uid))
            if (absolute) {
                KeyRow("key (48-bit)", "%02X %02X %02X %02X %02X %02X".format(
                    k4, k5,
                    key?.getOrNull(2)?.toInt()?.and(0xFF) ?: 0, key?.getOrNull(3)?.toInt()?.and(0xFF) ?: 0,
                    key?.getOrNull(4)?.toInt()?.and(0xFF) ?: 0, key?.getOrNull(5)?.toInt()?.and(0xFF) ?: 0))
                KeyRow("epoch", "${CrackState.epoch}")
            } else {
                KeyRow("key high (k4 k5)", "%02X %02X".format(k4, k5))
                KeyRow("clone-low", "%04X %04X".format(cloneHi, cloneLo))
            }
            if (method.isNotEmpty()) KeyRow("method", if (absolute) "dictionary" else "hitag2 bitslice")
            if (verify.isNotEmpty()) KeyRow("verify", verify)
            if (!codes.isNullOrEmpty()) {
                Spacer(Modifier.height(10.dp))
                Eyebrow("Next codes · btn ${f0.btn} · tap TX", Fox.live)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FiatChip("TE-B", fiatVariant == "B") { fiatVariant = "B" }
                    FiatChip("TE-A", fiatVariant == "A") { fiatVariant = "A" }
                    FiatChip("AM650", fiatPreset == FlipperRf.AM650) { fiatPreset = FlipperRf.AM650 }
                    FiatChip("AM270", fiatPreset == FlipperRf.AM270) { fiatPreset = FlipperRf.AM270 }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FiatChip("Int", FlipperRf.txDevice == FlipperRf.DEV_INT) { FlipperRf.txDevice = FlipperRf.DEV_INT }
                    FiatChip("Ext", FlipperRf.txDevice == FlipperRf.DEV_EXT) { FlipperRf.txDevice = FlipperRf.DEV_EXT }
                    FiatChip("Auto", FlipperRf.txDevice == FlipperRf.DEV_AUTO) { FlipperRf.txDevice = FlipperRf.DEV_AUTO }
                }
                Spacer(Modifier.height(6.dp))
                codes.forEach { (c, h) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("ctrl $c", color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("%08X".format(h), color = Fox.fg, fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        Text(if (txConnected && !txBusy) "TX →" else "—", color = if (txConnected && !txBusy) Fox.amber else Fox.faint,
                            fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.clickable(enabled = txConnected && !txBusy) { txFiat(c, h) })
                    }
                }
                if (txStatus.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(txStatus, color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp, lineHeight = 13.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fox.gradient())
                .clickable { onSend() }.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                Text("SEND KEY TO FOX ONE", color = Fox.amberInk, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun KeyRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(k, color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(v, color = Fox.fg, fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FiatChip(label: String, sel: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) Fox.amber else Fox.surface2)
            .border(1.dp, if (sel) Fox.amber else Fox.line, RoundedCornerShape(8.dp))
            .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp),
    ) { Text(label, color = if (sel) Fox.amberInk else Fox.muted, fontFamily = FoxMono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
}
