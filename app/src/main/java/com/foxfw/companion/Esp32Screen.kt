package com.foxfw.companion

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign

internal enum class Esp32Mode { RELAY, USB }

/** Board identity on the ESP32 page. NON-FOXFW is only set on a POSITIVE non-Fox match (a named
 *  firmware answered a probe); a board that doesn't reply cleanly (busy, CC1101 mode, garbage,
 *  silence) stays ERROR, never NON-FOXFW — so "not Fox" is never guessed from a bad/no reply. */
internal enum class EspDetect { PENDING, FOX, NONFOX, ERROR }

/** Label + colours + tap-through explanation for the identity pill. */
internal data class PillSpec(
    val label: String,
    val fg: androidx.compose.ui.graphics.Color,
    val bg: androidx.compose.ui.graphics.Color,
    val msg: String,
)

/**
 * Per-page retained state for the ESP32 family of terminals, keyed by page id ("esp32", "wifi", "ir",
 * …). It lives outside the composables so leaving a page and reopening it restores the console log,
 * scroll position and the text box exactly where you left them — the page's refresh button is the only
 * thing that wipes it (via [reset]).
 */
internal object EspSession {
    class St {
        val lines = mutableStateListOf("Connecting…")
        val savedUpTo = mutableStateOf(0)
        val text = mutableStateOf("")
        var scrollValue = 0
        val detect = mutableStateOf(EspDetect.PENDING)
        val foxBoard = mutableStateOf(false)
        val autoShip = mutableStateOf(true)
        val cmdsOpen = mutableStateOf(false)
        val seenAps = mutableStateMapOf<String, Esp32.Ap>()
        val selected = mutableStateMapOf<Int, Boolean>()
        val lastSelectedApIndex = mutableStateOf(-1)
    }
    private val map = mutableMapOf<String, St>()
    fun get(id: String): St = map.getOrPut(id) { St() }
    fun reset(id: String) { map[id] = St() }
}

/** True while the ESP32 console input is focused — lets FoxApp hide its header + bottom nav so the
 *  console goes full-screen with the input pinned to the keyboard, exactly like the Flipper CLI. */
object EspImmersive { var on by androidx.compose.runtime.mutableStateOf(false) }

/**
 * ESP32 control console for the BFFB v2. Firmware-aware: on a **Fox ESP32 Firmware** board the action
 * buttons, Portal card (Fox Portal) and Commands List all use Fox's native commands; on Marauder/other
 * it shows the Marauder set and an Evil Portal card. Transport is auto-determined (direct USB-OTG board
 * else Flipper relay). The Console + input behave like the Flipper CLI: a scrolling page normally, and
 * a full-screen immersive terminal (input pinned to the keyboard) while the input is focused.
 */
@Composable
fun Esp32Screen(onClose: () -> Unit) {
    // Header refresh = a full reload: Esp32Hub.reset() wipes the shared console + drops the link, then
    // bumping the key recreates the body so every remember resets and its LaunchedEffect reconnects —
    // identical to leaving the page and reopening it. The nonce + spin flag live out here so they survive
    // that recreation.
    var reloadNonce by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val reloadScope = rememberCoroutineScope()
    androidx.compose.runtime.key(reloadNonce) {
        Esp32ScreenBody(onClose, refreshing) {
            if (!refreshing) {
                refreshing = true
                reloadScope.launch { delay(1000); Esp32Hub.reset(); EspSession.reset("esp32"); reloadNonce++; refreshing = false }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun Esp32ScreenBody(onClose: () -> Unit, refreshing: Boolean, onReload: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    val session = remember { EspSession.get("esp32") }
    Esp32Hub.init(ctx)
    val bleLink = Esp32Hub.bleLink
    var usbConnected by Esp32Hub.usbConnectedState
    var mode by Esp32Hub.modeState
    val link: Esp32Link = Esp32Hub.link

    // Shared across the whole ESP32 area (see Esp32Hub): one connection + one console + targets +
    // capture pipeline. Per-page here: input text, scroll, command-list open, portal fields, jobs.
    val lines = Esp32Hub.lines
    var savedUpTo by Esp32Hub.savedUpToState
    var status by Esp32Hub.statusState

    var text by session.text
    var confirmCapture by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }

    var liveScan by remember { mutableStateOf(false) }
    val seenAps = Esp32Hub.seenAps
    val selected = Esp32Hub.selected
    var autoShip by Esp32Hub.autoShipState
    var captureBanner by Esp32Hub.captureBannerState
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val networksRequester = remember { BringIntoViewRequester() }

    var foxBoard by Esp32Hub.foxBoardState
    var detect by Esp32Hub.detectState
    var pillInfo by remember { mutableStateOf<String?>(null) }
    var lastSelectedApIndex by Esp32Hub.lastSelectedApIndexState

    val harvest = remember { HarvestSession() }
    var harvesting by remember { mutableStateOf(false) }
    var harvestJob by remember { mutableStateOf<Job?>(null) }
    var harvestTick by remember { mutableStateOf(0) }

    var portalOpen by remember { mutableStateOf(false) }
    var portalRunning by remember { mutableStateOf(false) }
    var portalFile by remember { mutableStateOf("index.html") }
    var portalApIdx by remember { mutableStateOf("") }
    var foxTitle by remember { mutableStateOf("") }
    var foxIntro by remember { mutableStateOf("") }
    var cmdsOpen by session.cmdsOpen

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }

    val connected = if (mode == Esp32Mode.USB) usbConnected else bleLink.connected

    val consoleScroll = rememberScrollState()
    val pageScroll = rememberScrollState(session.scrollValue) // hoisted + restored from last visit
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) consoleScroll.animateScrollTo(consoleScroll.maxValue) }

    // Console + transport are the shared hub's — these delegate so all the feature logic below is
    // unchanged. The single onLine (AP parse, ⚡ capture banner, auto-ship) is wired once in Esp32Hub.
    fun log(line: String) = Esp32Hub.log(line)

    suspend fun sendOne(line: String, window: Int? = null) = Esp32Hub.sendOne(line, window)

    fun doSend(line: String) {
        val c = line.trim(); if (c.isEmpty()) return
        log("› $c"); scope.launch { sendOne(c) }; scope.launch { pageScroll.animateScrollTo(0) }
    }
    fun submit() {
        if (text.isNotBlank()) {
            log("› ${text.trim()}"); val c = text.trim()
            scope.launch { sendOne(c) }
            text = ""; focus.clearFocus()
            scope.launch { pageScroll.animateScrollTo(0) } // drop to the top so the console (with the reply) shows
        }
    }
    fun saveNow() {
        val path = CliLog.append(ctx, lines.subList(savedUpTo, lines.size).toList())
        savedUpTo = lines.size
        log(if (path != null) "[log saved → $path]" else "[nothing new to save]")
    }

    fun doScan() {
        if (foxBoard) {
            if (scanning) return
            seenAps.clear()
            scope.launch {
                scanning = true; log("› WIFISCANALL"); status = "Scanning 2.4 + 5 GHz…"
                sendOne("WIFISCANALL")
                scanning = false; status = "Scan done — ${seenAps.size} networks."
                kotlinx.coroutines.delay(150); runCatching { networksRequester.bringIntoView() }
            }
            return
        }
        if (liveScan) {
            liveScan = false; scanJob?.cancel(); scanJob = null
            doSend(Esp32.STOP); status = "Live scan stopped — ${seenAps.size} networks seen."; return
        }
        scanJob?.cancel(); liveScan = true; seenAps.clear()
        scanJob = scope.launch {
            log("› scanall"); sendOne("scanall"); delay(2000)
            while (liveScan && isActive) {
                sendOne("list -a"); status = "Live scanning 2.4 + 5 GHz… ${seenAps.size} networks"; delay(2500)
            }
        }
    }

    fun capTargetAp(): Esp32.Ap? = seenAps.values.firstOrNull { selected.containsKey(it.index) } ?: seenAps.values.maxByOrNull { it.rssi }
    fun tapAp(ap: Esp32.Ap) {
        if (selected.containsKey(ap.index)) selected.remove(ap.index) else selected[ap.index] = true
        doSend(Esp32.selectAp(ap.index))
    }

    fun captureHandshake() {
        if (capturing) { // toggle -> stop
            capturing = false; captureJob?.cancel(); captureJob = null
            scope.launch { sendOne(Esp32.STOP, 1200) }
            status = "Capture stopped."
            return
        }
        val targeted = selected.isNotEmpty()
        val tgt = capTargetAp() ?: run { status = "Scan first, then capture — it needs a target channel."; return }
        val ch = tgt.channel
        Esp32Hub.capTargetName = tgt.essid; Esp32Hub.capTargetRssi = tgt.rssi
        capturing = true
        captureJob = scope.launch {
            try {
                val l = link
                val startCmd = "sniffpmkid -c $ch -d"
                log("› $startCmd")
                if (l is Esp32BleLink) {
                    // Relay: run in short daemon calls so the Flipper's loader frees between each (never
                    // stuck), Stop is responsive, and the whole capture is bounded to ~30s. Start the
                    // sniff, check the board accepted it, then just LISTEN in chunks (empty = capture only).
                    val before = lines.size
                    sendOne(startCmd, 3000)
                    val startOut = if (lines.size > before) lines.subList(before.coerceAtMost(lines.size), lines.size).joinToString(" ").lowercase() else ""
                    if ("[cancelled]" in startOut) { status = "Capture cancelled from the Flipper — tap Capture to retry."; return@launch }
                    if ("unknown" in startOut || "invalid" in startOut || "not a command" in startOut || "error" in startOut) {
                        status = "The board didn't accept 'sniffpmkid' — pick a different capture command from the Commands List."
                        return@launch
                    }
                    val chunks = 6
                    for (i in 0 until chunks) {
                        if (!capturing) break
                        status = "Capturing on ch $ch — ${if (targeted) "${selected.size} target(s)" else "strongest AP"}, ~${(chunks - i) * 4}s left (tap to Stop)"
                        val mark = lines.size
                        sendOne("", 4000) // listen only
                        // Flipper-side cancel: the user hit Back on the "Operation in progress" screen.
                        val chunkOut = if (lines.size > mark) lines.subList(mark.coerceAtMost(lines.size), lines.size).joinToString(" ") else ""
                        if ("[cancelled]" in chunkOut) { status = "Capture cancelled from the Flipper — tap Capture to retry."; return@launch }
                    }
                    sendOne(Esp32.STOP, 1200)
                    if (capturing) status = "Capture done. A caught handshake shows ⚡ above — pull the .pcap → Load .pcap to crack."
                } else {
                    sendOne(startCmd); status = "Capturing over USB — handshakes stream live; tap Stop when done."
                }
            } finally { capturing = false }
        }
    }

    fun toggleHarvest() {
        if (harvesting) {
            harvesting = false; harvestJob?.cancel(); harvestJob = null
            scope.launch { sendOne(Esp32.STOP) }
            status = "Auto-harvest stopped — ${harvest.totalPwnd} pwnd / ${harvest.totalComplete} full EAPOL over ${harvest.windows} windows."
            return
        }
        if (capturing) return
        harvestJob?.cancel(); harvesting = true
        harvestJob = scope.launch {
            val tgt = capTargetAp() ?: run { harvesting = false; status = "Scan first — auto-harvest needs a target channel."; return@launch }
            val ch = tgt.channel
            Esp32Hub.capTargetName = tgt.essid; Esp32Hub.capTargetRssi = tgt.rssi
            val cmd = "sniffpmkid -c $ch -d"; log("› auto-harvest: $cmd (looping)")
            while (harvesting && isActive) {
                val before = lines.size
                val l = link
                if (l is Esp32BleLink) { sendOne(cmd, 22_000); sendOne(Esp32.STOP, 800) } else { sendOne(cmd); delay(22_000); sendOne(Esp32.STOP); delay(400) }
                val slice = if (lines.size > before) lines.subList(before, lines.size).joinToString("\n") else ""
                // Flipper-side cancel: user hit Back on the "Operation in progress" screen — stop here.
                if ("[cancelled]" in slice) { harvesting = false; status = "Auto-harvest cancelled from the Flipper — tap to retry."; break }
                val neu = harvest.add(HarvestParse.parseWindow(slice)); harvestTick++
                status = "Auto-harvest · window ${harvest.windows} · ${harvest.totalPwnd} pwnd · ${harvest.totalComplete} full EAPOL" + if (neu > 0) " · +$neu this pass ⚡" else " · listening…"
                delay(400)
            }
        }
    }

    fun portalStart() {
        if (foxBoard) doSend("WIFIFOXPORTAL:START")
        else { val f = portalFile.trim(); doSend(if (f.isEmpty() || f == "index.html") "evilportal -c start" else "evilportal -c start -w $f") }
        portalRunning = true
    }
    fun portalStop() { if (foxBoard) doSend("WIFIFOXPORTAL:STOP") else doSend(Esp32.STOP); portalRunning = false }

    // Connect once for the whole ESP32 area (detection + transport live in the hub). Entering this page
    // after the link is already up is a no-op — no reconnect churn when switching tabs.
    LaunchedEffect(Unit) { Esp32Hub.enter(ctx) }

    // ---- focus-driven immersive (exactly like the Flipper CLI) ----
    var focused by remember { mutableStateOf(false) }
    var hadKeyboard by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible) hadKeyboard = true
        else if (hadKeyboard) { hadKeyboard = false; if (focused) focus.clearFocus() }
    }
    BackHandler(enabled = focused) { focus.clearFocus() }
    DisposableEffect(Unit) {
        onDispose {
            EspImmersive.on = false
            session.scrollValue = pageScroll.value // remember the scroll position for next open
            scanJob?.cancel(); harvestJob?.cancel()
            if (savedUpTo < lines.size) {
                runCatching { CliLog.append(ctx, lines.subList(savedUpTo, lines.size).toList()) }
                savedUpTo = lines.size // persisted — don't re-save these lines on the next close
            }
            // The shared link is NOT torn down here — Esp32Hub owns it (stays up across navigation,
            // drops only on explicit refresh/reset or when the app is backgrounded).
        }
    }

    val cmds = if (foxBoard) Esp32.foxCatalog else Esp32.catalog

    // Spins the header refresh glyph while a full reload is in flight.
    val refreshSpin = rememberInfiniteTransition(label = "esp-refresh")
    val refreshAngle by refreshSpin.animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "esp-refresh-a")

    @Composable
    fun ConsoleLines() {
        // "Connecting…" is a real printed line (seeded into the log), not an empty-state hint — so
        // 'clear' truly empties the console and the board's banner just prints beneath it on connect.
        lines.takeLast(300).forEach { l ->
            val col = when { l.startsWith("›") -> Fox.amber; l.startsWith("[relay]") -> Fox.warn; Esp32.isHandshakeHit(l) -> Fox.live; else -> Fox.fg }
            Text(l, color = col, fontFamily = FoxMono, fontSize = 10.5.sp, lineHeight = 13.sp)
        }
    }

    Column(
        Modifier.fillMaxSize().background(Fox.bg)
            .then(if (focused) Modifier.navigationBarsPadding() else Modifier)
            .imePadding(),
    ) {
      // Header + scrolling content are inset; the input bar below goes full-bleed to the nav bar.
      Column(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp)) {
        if (!focused) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Column {
                    Text("ESP32", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Eyebrow(when {
                        detect == EspDetect.FOX -> "Fox ESP32 FW"
                        detect == EspDetect.NONFOX -> "Marauder / stock"
                        detect == EspDetect.ERROR -> "detection error"
                        connected -> "detecting…"
                        else -> "BFFB v2"
                    })
                }
                Spacer(Modifier.weight(1f))
                // Full reload — spins while it re-inits everything (same as closing + reopening the page).
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                        .border(1.dp, Fox.line, RoundedCornerShape(9.dp))
                        .clickable { onReload() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⟳", color = Fox.amber, fontSize = 16.sp,
                        modifier = Modifier.graphicsLayer { rotationZ = if (refreshing) refreshAngle else 0f })
                }
                Spacer(Modifier.weight(1f))
                if (connected) {
                    // Identity pill — tap for an explanation. NON-FOXFW is green (a positively
                    // identified non-Fox board); ERROR is amber (couldn't identify — reconnect).
                    val spec = when (detect) {
                        EspDetect.FOX -> PillSpec("FOXFW", Fox.live, Fox.liveDim,
                            "This board has official Fox ESP32 Firmware installed.")
                        EspDetect.NONFOX -> PillSpec("NON-FOXFW", Fox.live, Fox.liveDim,
                            "This board does NOT contain Fox ESP32 Firmware and may not function as expected.")
                        EspDetect.ERROR -> PillSpec("ERROR", Fox.amber, Fox.amberDim,
                            "There was an error detecting the board, please reconnect.")
                        EspDetect.PENDING -> PillSpec("DETECTING…", Fox.muted, Fox.line,
                            "Still identifying the board — give it a moment.")
                    }
                    Box(Modifier.clickable { pillInfo = spec.msg }) { Pill(spec.label, spec.fg, spec.bg) }
                    // Connection topology: BLE / USB to the Flipper (FLPR) or straight to the ESP32.
                    val conns = if (mode == Esp32Mode.USB) listOf("USB", "ESP32")
                        else if (FlipperTransport.kind == LinkKind.BLE) listOf("BLE")
                        else listOf("USB", "FLPR")
                    conns.forEach { c -> Spacer(Modifier.width(6.dp)); Pill(c, Fox.live, Fox.liveDim) }
                } else Pill("offline", Fox.muted, Fox.line)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (focused) {
                // Immersive: the Console fills everything above the keyboard (Flipper-CLI layout).
                Column(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Fox.bg).border(1.dp, Fox.line, RoundedCornerShape(12.dp))
                    .verticalScroll(consoleScroll).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)) { ConsoleLines() }
            } else {
                // Normal: one scrolling page — Console card (scrolls out of view) then the controls.
                // Uses the hoisted pageScroll so Send can snap back to the top (console) after a command.
                Column(Modifier.fillMaxSize().verticalScroll(pageScroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    captureBanner?.let { cb ->
                        FoxCard(border = Fox.live.copy(alpha = 0.6f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚡", fontSize = 18.sp); Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Handshake captured", color = Fox.live, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(cb, color = Fox.fg, fontSize = 11.sp)
                                }
                                Text("✕", color = Fox.muted, fontSize = 15.sp, modifier = Modifier.clickable { captureBanner = null })
                            }
                        }
                    }

                    // CONSOLE card — header row (ship/clear here, never over the log), then the capped log.
                    FoxCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Eyebrow("Console")
                                Spacer(Modifier.weight(1f))
                                Text("clear", color = Fox.muted, fontSize = 10.5.sp, modifier = Modifier.clickable { lines.clear() })
                            }
                            Box(Modifier.fillMaxWidth().height(174.dp).clip(RoundedCornerShape(10.dp)) // ~12 lines, then scrolls
                                .background(Fox.bg).border(1.dp, Fox.line, RoundedCornerShape(10.dp))) {
                                Column(Modifier.fillMaxWidth().verticalScroll(consoleScroll).padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)) { ConsoleLines() }
                            }
                        }
                    }

                    Text(status, color = Fox.faint, fontSize = 10.5.sp, lineHeight = 14.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

                    FoxCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MBtn(if (foxBoard) (if (scanning) "⟳ Scanning…" else "⟳ Scan networks") else if (liveScan) "■ Stop live scan" else "⟳ Live scan (continuous)", Modifier.fillMaxWidth(), accent = if (foxBoard) scanning else true) { doScan() }
                            MBtn(if (capturing) "■ Stop capture" else "⚡ Capture handshake", Modifier.fillMaxWidth(), accent = capturing) { if (capturing) captureHandshake() else confirmCapture = true }
                            MBtn("Auto-Send to the Cracker (${if (autoShip) "ON" else "OFF"})", Modifier.fillMaxWidth(), accent = autoShip) { autoShip = !autoShip }
                            MBtn(if (harvesting) "■ Stop auto-harvest" else "♻ Auto-harvest (continuous)", Modifier.fillMaxWidth(), accent = harvesting) { if (harvesting || !capturing) toggleHarvest() }
                            if (harvest.windows > 0 || harvesting) {
                                val t = harvestTick
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (t >= 0 && harvesting) Pill("HARVEST", Fox.live, Fox.liveDim, dot = true)
                                    Spacer(Modifier.width(8.dp))
                                    Text("${harvest.totalPwnd} pwnd", color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Text("${harvest.totalComplete} full EAPOL", color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Text("${harvest.windows} win", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // networks — accumulated from scans; tap one to select it as a target for attacks/capture
                    if (seenAps.isNotEmpty() || liveScan) {
                        FoxCard(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(networksRequester)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val g24 = seenAps.values.filter { it.band == BandScan.Band.GHZ24 }.sortedByDescending { it.rssi }
                                val g5 = seenAps.values.filter { it.band == BandScan.Band.GHZ5 }.sortedByDescending { it.rssi }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Eyebrow("NETWORKS"); Spacer(Modifier.width(10.dp))
                                    Text("2.4G ${g24.size}", color = Fox.live, fontFamily = FoxMono, fontSize = 10.5.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("5G ${g5.size}", color = Fox.violet, fontFamily = FoxMono, fontSize = 10.5.sp)
                                    Spacer(Modifier.weight(1f))
                                    if (liveScan) Pill("LIVE", Fox.live, Fox.liveDim)
                                    else Text("clear", color = Fox.muted, fontSize = 11.sp, modifier = Modifier.clickable { seenAps.clear(); selected.clear() })
                                }
                                Text("Tap a network to select it — attacks + capture target the selection.", color = Fox.faint, fontSize = 10.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MBtn("Select all", Modifier.weight(1f)) { seenAps.values.forEach { selected[it.index] = true }; doSend(Esp32.selectAllAp()) }
                                    if (selected.isNotEmpty()) MBtn("Clear selection", Modifier.weight(1f)) { selected.clear() }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    if (g24.isNotEmpty()) { BandHeader("2.4 GHz", g24.size, Fox.live); g24.forEach { ap -> ApRow(ap, Fox.live, selected.containsKey(ap.index)) { tapAp(ap) } } }
                                    if (g5.isNotEmpty()) { BandHeader("5 GHz", g5.size, Fox.violet); g5.forEach { ap -> ApRow(ap, Fox.violet, selected.containsKey(ap.index)) { tapAp(ap) } } }
                                }
                            }
                        }
                    }

                    // Portal card — Fox Portal on a Fox board, Evil Portal otherwise
                    FoxCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { portalOpen = !portalOpen }) {
                                Eyebrow(if (foxBoard) "FOX PORTAL" else "EVIL PORTAL")
                                Spacer(Modifier.width(10.dp))
                                if (portalRunning) Pill("LIVE", Fox.crit, Fox.amberDim, dot = true)
                                Spacer(Modifier.weight(1f))
                                Text(if (portalOpen) "▾" else "▸", color = Fox.muted, fontSize = 13.sp)
                            }
                            if (portalOpen && foxBoard) {
                                Text("Fox captive portal. Set a title/intro, start it, and submitted entries stream to the console + the board SD. Authorised testing only.", color = Fox.faint, fontSize = 10.sp, lineHeight = 14.sp)
                                PortalField("Portal title", foxTitle, { foxTitle = it }, "Set title") { if (foxTitle.isNotBlank()) doSend("WIFIFOXPORTAL:SETTITLE:${foxTitle.trim()}") }
                                PortalField("Intro text", foxIntro, { foxIntro = it }, "Set intro") { if (foxIntro.isNotBlank()) doSend("WIFIFOXPORTAL:SETINTRO:${foxIntro.trim()}") }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MBtn(if (portalRunning) "● Running" else "▶ Start", Modifier.weight(1f), accent = !portalRunning) { portalStart() }
                                    MBtn("■ Stop", Modifier.weight(1f)) { portalStop() }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MBtn("Status", Modifier.weight(1f)) { doSend("WIFIFOXPORTAL:STATUS") }
                                    MBtn("QR", Modifier.weight(1f)) { doSend("WIFIFOXPORTAL:QR") }
                                    MBtn("Export log", Modifier.weight(1f)) { doSend("WIFIFOXPORTAL:EXPORTLOG") }
                                }
                                PortalCreds(lines)
                            } else if (portalOpen) {
                                Text("Rogue captive portal. Clone a target AP, pick a page on the board SD, start. Submitted creds stream to the console + SD. Authorised testing only.", color = Fox.faint, fontSize = 10.sp, lineHeight = 14.sp)
                                PortalField("AP index (or blank for selected)", portalApIdx, { portalApIdx = it.filter(Char::isDigit) }, "Set AP") {
                                    val idx = portalApIdx.trim().ifEmpty { selected.keys.firstOrNull()?.toString().orEmpty() }
                                    if (idx.isEmpty()) status = "Pick a target: scan then type an AP index." else doSend("evilportal -c setap $idx")
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("index.html", "apple.html", "google.html", "microsoft.html").forEach { f ->
                                        val sel = portalFile == f
                                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) Fox.amberDim else Fox.surface2)
                                            .border(1.dp, if (sel) Fox.amber else Fox.line, RoundedCornerShape(8.dp)).clickable { portalFile = f }.padding(horizontal = 9.dp, vertical = 6.dp)) {
                                            Text(f.removeSuffix(".html"), color = if (sel) Fox.amber else Fox.muted, fontFamily = FoxMono, fontSize = 10.sp)
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MBtn(if (portalRunning) "● Running" else "▶ Start portal", Modifier.weight(1f), accent = !portalRunning) { portalStart() }
                                    MBtn("■ Stop", Modifier.weight(1f)) { portalStop() }
                                }
                                PortalCreds(lines)
                            }
                        }
                    }

                    // Commands List — collapsible, FW-appropriate. Tapping drops the command into the box.
                    FoxCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { cmdsOpen = !cmdsOpen }) {
                                Eyebrow("COMMANDS LIST")
                                Spacer(Modifier.width(8.dp))
                                Text(if (foxBoard) "Fox ESP32 Firmware" else "Other (Marauder)", color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp)
                                Spacer(Modifier.weight(1f))
                                Text(if (cmdsOpen) "▾" else "▸", color = Fox.muted, fontSize = 14.sp)
                            }
                            if (cmdsOpen) {
                                Text("Tap a command to drop it into the box above, then Send.", color = Fox.faint, fontSize = 10.sp)
                                cmds.forEach { cat ->
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Eyebrow(cat.name.uppercase())
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            cat.cmds.forEach { cmd -> CmdChip(cmd) { text = cmd.template } }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

      }

        // input + Send/Save — a full-width bar fixed flush to the bottom nav, like the Flipper CLI.
        // The bar spans edge-to-edge (no gap to the tab bar); the textbox keeps a darker fill so it
        // still reads as a field against the bar.
        Box(
            Modifier.fillMaxWidth().background(Fox.surface)
                .border(1.dp, Fox.line, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
        ) {
        Row(Modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { if (!focused) Text("ENTER ESP32 COMMAND", fontSize = 11.sp, fontFamily = FoxMono) },
                modifier = Modifier.weight(1f).fillMaxHeight().onFocusChanged { focused = it.isFocused; EspImmersive.on = it.isFocused },
                textStyle = TextStyle(fontFamily = FoxMono, fontSize = 12.sp),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Fox.bg, unfocusedContainerColor = Fox.bg,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MBtn("Send", Modifier.weight(1f).width(80.dp), accent = true) { submit() }
                MBtn("Save", Modifier.weight(1f).width(80.dp)) { saveNow() }
            }
        }
        }
    }

    pillInfo?.let { msg ->
        AlertDialog(
            onDismissRequest = { pillInfo = null },
            title = { Text("ESP32 board", color = Fox.fg) },
            text = { Text(msg, color = Fox.fg, fontSize = 13.sp, lineHeight = 17.sp) },
            confirmButton = { TextButton(onClick = { pillInfo = null }) { Text("OK", color = Fox.amber) } },
            containerColor = Fox.surface,
        )
    }

    if (confirmCapture) {
        AlertDialog(
            onDismissRequest = { confirmCapture = false },
            title = { Text("Authorized use only", color = Fox.crit) },
            text = {
                Text("This deauthenticates ${if (selected.isNotEmpty()) "the selected AP(s)' clients" else "nearby clients"} to force a reconnect and capture the PMKID + 4-way EAPOL handshake (saved as .pcap on the board SD). Only on networks you own or are authorized to test.",
                    color = Fox.fg, fontSize = 12.sp, lineHeight = 16.sp)
            },
            confirmButton = { TextButton(onClick = { confirmCapture = false; captureHandshake() }) { Text("Capture", color = Fox.crit) } },
            dismissButton = { TextButton(onClick = { confirmCapture = false }) { Text("Cancel", color = Fox.muted) } },
            containerColor = Fox.surface,
        )
    }
}

@Composable
private fun PortalField(hint: String, value: String, onChange: (String) -> Unit, btn: String, onBtn: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = value, onValueChange = onChange, placeholder = { Text(hint, fontSize = 10.sp) },
            modifier = Modifier.weight(1f), textStyle = TextStyle(fontFamily = FoxMono, fontSize = 12.sp), singleLine = true)
        MBtn(btn) { onBtn() }
    }
}

@Composable
private fun PortalCreds(lines: List<String>) {
    val creds = lines.filter { portalCredLine(it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Captured", color = Fox.fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text("${creds.size}", color = if (creds.isNotEmpty()) Fox.live else Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
    }
    if (creds.isEmpty()) Text("No submissions yet — entries land here as the page is filled in.", color = Fox.faint, fontSize = 10.sp)
    else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        creds.takeLast(40).forEach { c -> Text(c, color = Fox.live, fontFamily = FoxMono, fontSize = 10.5.sp, lineHeight = 13.sp) }
    }
}

@Composable
internal fun CmdChip(cmd: Esp32.Cmd, onClick: () -> Unit) {
    val accent = cmd.danger
    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(if (accent) Fox.crit.copy(alpha = 0.14f) else Fox.surface2)
        .border(1.dp, if (accent) Fox.crit else Fox.line, RoundedCornerShape(9.dp)).clickable { onClick() }.padding(vertical = 8.dp, horizontal = 11.dp)) {
        Text(cmd.label, color = if (accent) Fox.crit else Fox.fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun MBtn(text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(11.dp)).background(if (accent) Fox.amberDim else Fox.surface2)
        .border(1.dp, if (accent) Fox.amber else Fox.line, RoundedCornerShape(11.dp)).clickable { onClick() }.padding(vertical = 11.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center) { Text(text, color = if (accent) Fox.amber else Fox.fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun BandHeader(label: String, count: Int, col: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(col))
        Spacer(Modifier.width(6.dp))
        Text("$label · $count", color = col, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ApRow(ap: Esp32.Ap, bandCol: androidx.compose.ui.graphics.Color, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (selected) Fox.amberDim else androidx.compose.ui.graphics.Color.Transparent)
        .clickable { onClick() }.padding(vertical = 3.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (selected) "✓" else "·", color = if (selected) Fox.amber else Fox.faint, fontSize = 11.sp, modifier = Modifier.width(14.dp))
        Text("CH${ap.channel}", color = bandCol, fontFamily = FoxMono, fontSize = 10.5.sp, modifier = Modifier.width(48.dp))
        Text(ap.essid, color = if (selected) Fox.amber else Fox.fg, fontFamily = FoxMono, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text("${ap.rssi}", color = Fox.muted, fontFamily = FoxMono, fontSize = 10.5.sp)
    }
}

private fun portalCredLine(line: String): Boolean {
    val l = line.trim()
    if (l.isEmpty() || l.startsWith("›") || l.startsWith("[")) return false
    val lo = l.lowercase()
    val formEncoded = l.count { it == '=' } >= 2 && '&' in l
    val hit = ("password" in lo || "passwd" in lo || "email" in lo || "username" in lo || "user=" in lo || "pass=" in lo || "credential" in lo || (("@" in l) && ("=" in l)) || formEncoded)
    return hit && !lo.startsWith("received ") && !lo.startsWith("complete ") && !lo.startsWith("pwnd")
}
