package com.foxfw.companion

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxLogo
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.GradientBar
import com.foxfw.companion.ui.GradientText
import com.foxfw.companion.ui.Pill
import com.foxfw.companion.ui.Proto
import com.foxfw.companion.ui.ProtocolBadge
import com.foxfw.companion.ui.SignalBars
import com.foxfw.companion.ui.protoFiat
import com.foxfw.companion.ui.protoHitag
import com.foxfw.companion.ui.protoKeeloq
import com.foxfw.companion.ui.protoKia
import com.foxfw.companion.ui.protoPandora
import com.foxfw.companion.ui.protoPsa
import com.foxfw.companion.ui.protoVag
import kotlinx.coroutines.delay


@Composable
private fun ScreenScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

/* ------------------------------- DEVICE ------------------------------- */
/** Normalise a FoxFW version/tag ("foxfw-v2.1", "2.0.13", "v2.1") to a tidy "v…" label, "—" if unknown. */
private fun verLabel(s: String?): String {
    if (s.isNullOrBlank()) return "—"
    val t = s.trim().removePrefix("foxfw-").removePrefix("FoxFW-").trimStart('v', 'V')
    return if (t.isEmpty()) "—" else "v$t"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(fle: FlipperBle, onConnect: () -> Unit, onDisconnect: () -> Unit, onOpenDecode: () -> Unit,
                 onOpenSavedCaptures: () -> Unit = {}, onOpenTool: (String) -> Unit = {}) {
    val usb = UsbLink.usbConnected
    val connected = fle.conn == Conn.CONNECTED || usb
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cli = remember { FlipperCli() }
    var battery by remember { mutableStateOf<String?>(null) }
    var captures by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var espRowMsg by remember { mutableStateOf<String?>(null) }   // tapped ESP32 row → explanation dialog
    // Expandable DEVICE / POWER info (moved off the Settings page); pre-loaded on connect, revealed by the
    // down-arrow on the connection card.
    var showDeviceInfo by remember { mutableStateOf(false) }
    // "Detecting…" window for the receiver card: shown while the ESP32 probe runs, held a minimum of 5s
    // (in case detection is slow) but cleared the instant a result lands. rxDetectKick re-arms it on refresh.
    var rxDetecting by remember { mutableStateOf(false) }
    var rxDetectKick by remember { mutableIntStateOf(0) }
    val deviceDetail = remember { mutableStateListOf<Pair<String, String>>() }
    val powerDetail = remember { mutableStateListOf<Pair<String, String>>() }
    val rxStatus = CompanionCache.rx
    val refreshRot = remember { Animatable(0f) }
    val statProgress = remember { Animatable(if (CompanionCache.statsAnimated) 1f else 0f) }
    // Continuous spin for the receiver refresh glyph while the first-load ESP32 probe is still running.
    val rxSpin = androidx.compose.animation.core.rememberInfiniteTransition(label = "rx-spin")
    val rxSpinAngle by rxSpin.animateFloat(0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "rx-spin-a")

    suspend fun loadBattery() {
        battery = if (connected) {
            val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { (FlipperTransport.active ?: fle).powerInfo() }
            p?.firstOrNull { it.first.contains("charge", true) && !it.first.contains("voltage", true) }?.second?.let { "$it%" }
        } else null
    }
    suspend fun loadRx() {
        val s2 = runCatching { cli.receiverStatus() }.getOrNull()
        if (s2 != null) { CompanionCache.rx = s2; if (CompanionCache.protocols == null) CompanionCache.protocols = s2.protocols }
    }
    // Receiver-card refresh icon: spin 1s + re-read the receiver status.
    val refreshRx: () -> Unit = {
        CompanionCache.espDetect = null; rxDetectKick++   // synchronous null so the Detecting window re-arms
        scope.launch { refreshRot.snapTo(0f); refreshRot.animateTo(360f, tween(1000)) }
        // Re-read RXSTATUS then re-run the full ESP32 identity probe (sequential: one daemon at a time).
        scope.launch { loadRx(); CompanionCache.espDetect = classifyEspRelay() }
    }
    // Pull-to-refresh: re-fetch everything and replay the count-up, as if we'd just connected (~1.5s).
    // Hard-capped at 15s so a wedged Flipper daemon can't leave the spinner turning forever — on timeout
    // (or any error) we just stop refreshing and keep whatever we already had.
    val fullRefresh: () -> Unit = {
        CompanionCache.espDetect = null; rxDetectKick++
        scope.launch {
            refreshing = true
            try {
                kotlinx.coroutines.withTimeout(15_000) {
                    CompanionCache.rx = null
                    loadRx(); loadBattery()
                    CompanionCache.espDetect = classifyEspRelay()
                }
            } catch (_: Throwable) {
                // timed out or failed — fall through and clear the spinner
                if (CompanionCache.espDetect == null) CompanionCache.espDetect = EspDetect.ERROR
            }
            statProgress.snapTo(0f)
            statProgress.animateTo(1f, tween(1500, easing = LinearOutSlowInEasing))
            refreshing = false
        }
    }
    LaunchedEffect(connected) {
        captures = ctx.getExternalFilesDir(null)?.listFiles { f -> f.name.endsWith(".sub", true) }?.size ?: 0
        loadBattery()
    }
    // Drives the receiver "Detecting…" copy: starts whenever a probe is armed (connect / refresh) with the
    // detection still unknown, waits for the result, then holds a 5s floor so the word doesn't flicker past.
    LaunchedEffect(rxDetectKick, connected) {
        if (!connected || CompanionCache.espDetect != null) { rxDetecting = false; return@LaunchedEffect }
        rxDetecting = true
        val t0 = System.currentTimeMillis()
        while (CompanionCache.espDetect == null) kotlinx.coroutines.delay(100)
        val remain = 5000 - (System.currentTimeMillis() - t0)
        if (remain > 0) kotlinx.coroutines.delay(remain)
        rxDetecting = false
    }
    // Pre-load the full DEVICE + POWER/BATTERY info on connect so it's ready the instant the user expands it.
    LaunchedEffect(connected) {
        if (connected) {
            val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { (FlipperTransport.active ?: fle).deviceInfo() }
            val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { (FlipperTransport.active ?: fle).powerInfo() }
            deviceDetail.clear(); d?.let { deviceDetail.addAll(it) }
            powerDetail.clear(); p?.let { powerDetail.addAll(it) }
        } else { deviceDetail.clear(); powerDetail.clear(); showDeviceInfo = false }
    }
    // Firmware update availability, full ESP32 identity + wallpaper count for the DEVICE tiles.
    LaunchedEffect(connected) {
        if (connected) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FwRepo.load(ctx) }  // prefs only, no RPC
            CompanionCache.fwLatest = FwRepo.latestTag
            if (CompanionCache.connectedAtMs == null) CompanionCache.connectedAtMs = System.currentTimeMillis()
            if (CompanionCache.espDetect == null || CompanionCache.wallpapers == null) {
                // Wait for the receiver-status daemon probe (CC1101 + ESP32 over GPIO) to finish before
                // running our own daemon calls — a concurrent one on connect races it and drops detection.
                var waited = 0
                while (CompanionCache.rx == null && waited < 7000) { kotlinx.coroutines.delay(200); waited += 200 }
                kotlinx.coroutines.delay(300)
                // Full tri-state ESP32 identity — the same probe logic the ESP32 page uses (FOX / NON-FOX /
                // ERROR), not the firmware's short RXSTATUS check which was misreading Fox boards.
                if (CompanionCache.espDetect == null) CompanionCache.espDetect = classifyEspRelay()
                if (CompanionCache.wallpapers == null) {
                    val wp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperTransport.active?.storageList("/ext/wallpapers") }
                    if (wp != null) CompanionCache.wallpapers = wp.count { !it.isDir && it.name.endsWith(".xbm", true) }
                }
            }
            if (CompanionCache.fwInstallDate == null) {
                // Firmware build/install date from the core device_info RPC (e.g. firmware_build_date).
                val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { (FlipperTransport.active ?: fle).deviceInfo() }
                CompanionCache.fwInstallDate = info?.firstOrNull { it.first.contains("build_date", true) || it.first.endsWith("_date", true) }?.second
            }
        }
    }
    LaunchedEffect(connected) {
        if (connected && !CompanionCache.statsAnimated) {
            var waited = 0
            while (CompanionCache.protocols == null && waited < 4000) { kotlinx.coroutines.delay(100); waited += 100 }
            statProgress.snapTo(0f)
            statProgress.animateTo(1f, tween(1500, easing = LinearOutSlowInEasing))
            CompanionCache.statsAnimated = true
        }
    }
    // While refreshing we let the animation drive the tiles again (count back up from 0).
    val sp = if (CompanionCache.statsAnimated && !refreshing) 1f else statProgress.value

    val protoShown = if (connected) (CompanionCache.protocols ?: 0) else 0
    val appsShown = if (connected) (rxStatus?.apps ?: 0) else 0
    val sdUsedKb = rxStatus?.sdUsedKb
    val sdTotalKb = rxStatus?.sdTotalKb
    val sdPct = if (connected && sdTotalKb != null && sdTotalKb > 0 && sdUsedKb != null) ((sdUsedKb * 100) / sdTotalKb).toInt() else 0
    val sdCapGb = sdTotalKb?.takeIf { it > 0 }?.let { "%.0f".format(it * 1024.0 / 1_000_000_000.0) }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = fullRefresh, modifier = Modifier.fillMaxSize()) {
        ScreenScroll {
            FoxCard(
                border = if (connected) Fox.violet.copy(alpha = 0.42f) else Fox.line,
                modifier = if (usb) Modifier else Modifier.clickable { if (connected) onDisconnect() else onConnect() },
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FoxLogo(56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fle.deviceName ?: "FoxFW v2.1", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(7.dp))
                            Text(
                                if (connected) "FoxFW 2.1 · f7 · ${if (usb) "USB" else "BLE"} RPC\nregion unlocked" else "Tap to connect to BLE or insert USB",
                                color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp,
                            )
                        }
                        if (connected) Text(battery ?: "", color = Fox.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        else Icon(Icons.Filled.ChevronRight, null, tint = Fox.faint, modifier = Modifier.size(22.dp))
                    }
                    // Down-arrow (bottom-right) reveals the full DEVICE + POWER/BATTERY info below; up-arrow hides it.
                    // Sits in a short box pulled up tight against the row above so the card doesn't gain dead space.
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(16.dp)) {
                        Text(
                            "FoxFW Companion ${BuildConfig.VERSION_NAME}",
                            color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                        if (connected) Icon(
                            if (showDeviceInfo) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            if (showDeviceInfo) "Hide device info" else "Show device info",
                            tint = Fox.muted,
                            modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-2).dp).size(26.dp)
                                .clip(RoundedCornerShape(999.dp)).clickable { showDeviceInfo = !showDeviceInfo },
                        )
                    }
                }
            }

            // DEVICE + POWER/BATTERY detail (moved off Settings), revealed by the card's down-arrow.
            if (connected && showDeviceInfo) {
                FoxCard {
                    Column {
                        Eyebrow("DEVICE", color = Fox.amber); Spacer(Modifier.height(8.dp))
                        if (deviceDetail.isEmpty()) Text("reading…", color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                        deviceDetail.take(8).forEach { (k, v) -> InfoRow(prettify(k), v) }
                    }
                }
                FoxCard {
                    Column {
                        Eyebrow("POWER / BATTERY", color = Fox.amber); Spacer(Modifier.height(8.dp))
                        if (powerDetail.isEmpty()) Text("reading…", color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                        powerDetail.filter { it.first.contains("charge", true) || it.first.contains("volt", true) || it.first.contains("health", true) || it.first.contains("temp", true) }
                            .take(6).forEach { (k, v) -> InfoRow(prettify(k), v) }
                    }
                }
            }

            // Installed vs latest FoxFW → the firmware version tile.
            val fwInstalled = CompanionCache.fwVersion
            val fwLatest = CompanionCache.fwLatest
            val wallpapers = if (connected) (CompanionCache.wallpapers ?: 0) else 0

            val tileH = 84.dp
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile((captures * sp).toInt().toString(), "Saved .sub", Modifier.weight(1f).height(tileH), onClick = onOpenSavedCaptures)
                    StatTile((DecodeFeed.decodedToday * sp).toInt().toString(), "Decoded", Modifier.weight(1f).height(tileH))
                    StatTile((protoShown * sp).toInt().toString(), "Protocols", Modifier.weight(1f).height(tileH))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile((appsShown * sp).toInt().toString(), "APK count", Modifier.weight(1f).height(tileH))
                    StatTile((wallpapers * sp).toInt().toString(), "Wallpapers", Modifier.weight(1f).height(tileH))
                    StatTile("${(sdPct * sp).toInt()}%", if (sdCapGb != null) "SD ${sdCapGb}GB" else "SD card", Modifier.weight(1f).height(tileH)) { onOpenTool("files") }
                }
                // Hold the whole firmware/hardware row at "—" until the LAST value (Install Date) lands,
                // then reveal all three tiles together instead of one field popping in at a time.
                val row3Ready = connected && CompanionCache.fwInstallDate != null
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoTile2("Firmware", if (row3Ready) verLabel(fwInstalled) else "—", "Current", if (row3Ready) verLabel(fwLatest) else "—", Modifier.weight(1f).height(tileH))
                    FwInfoTile(connected && row3Ready, if (row3Ready) CompanionCache.fwInstallDate else null, if (row3Ready) CompanionCache.connectedAtMs else null, Modifier.weight(1f).height(tileH))
                    InfoTile2("Hardware", if (row3Ready) (CompanionCache.hw ?: "—") else "—", "Radio FW", if (row3Ready) (CompanionCache.radioFw ?: "—") else "—", Modifier.weight(1f).height(tileH))
                }
            }

            FoxCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("External Receiver")
                        Spacer(Modifier.weight(1f))
                        val rxProbing = connected && (CompanionCache.espDetect == null || rxDetecting)
                        Icon(
                            Icons.Outlined.Refresh, "Refresh receiver",
                            tint = if (connected) Fox.muted else Fox.faint,
                            modifier = Modifier.size(18.dp).rotate(if (rxProbing) rxSpinAngle else refreshRot.value)
                                .clip(RoundedCornerShape(999.dp)).clickable(enabled = connected) { refreshRx() },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    val ccExt = connected && rxStatus?.cc1101Ext == "ready"
                    val det = if (connected) (CompanionCache.espDetect ?: EspDetect.PENDING) else EspDetect.PENDING
                    // ESP32 counts as connected only when a board actually answers (FOX / NON-FOX). ERROR means
                    // nothing responded (e.g. only the CC1101 is attached); PENDING is still probing.
                    val espConnected = det == EspDetect.FOX || det == EspDetect.NONFOX
                    val espFw = CompanionCache.espFwName ?: ""   // FoxFW / Marauder / Bruce / Unsupported
                    val eMsg = when (det) {
                        EspDetect.FOX -> "This board has official Fox ESP32 Firmware installed."
                        EspDetect.NONFOX -> "This board does NOT contain Fox ESP32 Firmware — it's an unsupported board and may not function as expected."
                        else -> "No Fox ESP32 board detected — plug one in and tap the refresh icon above."
                    }
                    // Grey centred label between the board name and its pill (italic + bold).
                    @Composable
                    fun RowScope.MidLabel(text: String) {
                        Text(text, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    }
                    if (!ccExt && !espConnected) {
                        val emptyMsg = when {
                            !connected -> "Connect to Flipper"
                            rxDetecting -> "Detecting…"
                            else -> "None Detected"
                        }
                        Text(emptyMsg, color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    } else {
                        if (ccExt) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Flipper CC1101", color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                MidLabel("External")
                                Pill("CONNECTED", Fox.live, Fox.liveDim, dot = true)
                            }
                            if (espConnected) Spacer(Modifier.height(6.dp))
                        }
                        if (espConnected) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { espRowMsg = eMsg }, verticalAlignment = Alignment.CenterVertically) {
                                Text("Flipper ESP32", color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                MidLabel(espFw)
                                Pill("CONNECTED", Fox.live, Fox.liveDim, dot = true)
                            }
                        }
                    }
                }
            }

            // Live receiver waterfall — scrolls real saved-sub traces by when you have captures, or a
            // mostly-silent "pretend" feed (occasional 3–6 bar spike) when you don't. Same bar look as DECODE.
            com.foxfw.companion.ui.GraticuleScreen(Modifier.fillMaxWidth().height(58.dp)) {
                SignalWaterfall(
                    active = connected,         // only runs while linked; flat quiet line otherwise
                    hasSubs = captures >= 10,   // under 10 saved subs → just play the pretend waterfall
                    nextSubBars = { randomSubBars(ctx) },
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            }
        }
    }

    espRowMsg?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { espRowMsg = null },
            title = { Text("ESP32 board", color = Fox.fg) },
            text = { Text(msg, color = Fox.fg, fontSize = 13.sp, lineHeight = 17.sp) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { espRowMsg = null }) { Text("OK", color = Fox.amber) } },
            containerColor = Fox.surface,
        )
    }
}

/** DEVICE-page ESP32 identity probe — the ESP32 page's exact tri-state logic (never guess Marauder from
 *  a bad/no reply), run as a one-shot over the Flipper relay. FOX / NONFOX / ERROR, or PENDING on silence. */
private suspend fun classifyEspRelay(): EspDetect {
    val cli = FlipperCli()
    suspend fun probe(cmd: String, ms: Int): String =
        (runCatching { cli.exchangeQuiet("ESP32 $cmd", ms) }.getOrNull() ?: "").lowercase()
    // Name the non-Fox board where we can (Marauder / Bruce), else "Unsupported". Fox → "FoxFW".
    fun nonFox(vararg blobs: String): EspDetect {
        val all = blobs.joinToString(" ")
        CompanionCache.espFwName = when {
            "bruce" in all -> "Bruce"
            "marauder" in all -> "Marauder"
            else -> "Unsupported"
        }
        return EspDetect.NONFOX
    }
    val r1 = probe("info", 1500)
    if ("fox esp32 firmware" in r1) { CompanionCache.espFwName = "FoxFW"; return EspDetect.FOX }
    if ("marauder" in r1 || "bruce" in r1) return nonFox(r1)
    val r2 = probe("stopscan", 1200)
    if ("fox esp32 firmware" in r2) { CompanionCache.espFwName = "FoxFW"; return EspDetect.FOX }   // a late Fox banner landed
    if ("marauder" in r2 || "stopscan" in r2 || "sniff" in r2 || "evilportal" in r2 || ("scan" in r2 && "stop" in r2))
        return nonFox(r1, r2)
    val r3 = probe("AT", 800)
    if ("at version" in r3 || Regex("(^|\\n)ok\\b").containsMatchIn(r3)) return nonFox(r1, r2, r3)
    // No identifiable firmware at all (silence / CC1101-mode garbage) → WAITING, never a false NOT FOXFW.
    CompanionCache.espFwName = null
    return if (r1.isBlank() && r2.isBlank() && r3.isBlank()) EspDetect.PENDING else EspDetect.ERROR
}

/* ------------------------------- DECODE ------------------------------- */
data class DecodeEntry(val proto: Proto, val title: String, val meta: String, val rssi: String, val bars: Int, val ago: String)

private val decodeFreqs = listOf(
    "300.00" to 300000000, "303.875" to 303875000, "304.25" to 304250000, "310.00" to 310000000,
    "315.00" to 315000000, "318.00" to 318000000, "330.00" to 330000000, "345.00" to 345000000,
    "348.00" to 348000000, "387.00" to 387000000, "390.00" to 390000000, "418.00" to 418000000,
    "433.075" to 433075000, "433.42" to 433420000, "433.777" to 433777000, "433.905" to 433905000,
    "433.92" to 433920000, "434.42" to 434420000,
    "434.775" to 434775000, "438.90" to 438900000, "464.00" to 464000000, "779.00" to 779000000,
    "868.35" to 868350000, "868.95" to 868950000, "915.00" to 915000000, "925.00" to 925000000,
)
// 433.777 = Chacon 84181, 433.905 = Meiantech/Atlantics/Aidebao (from the PandwaRF remote library).
private val DEFAULT_FREQ_IDX = 16   // 433.92
// (label, fox_subghz_io preset index): 0=AM270 1=AM650 2=FM238 3=FM476
private val decodeMods = listOf("AM650" to 1, "AM270" to 0, "FM238" to 2, "FM476" to 3)
private val decodeDevNames = listOf("Int" to 0, "Ext" to 1, "Auto" to 2)

/** Hand a RAW .sub (captured or opened on the Decode page) straight into the SubGHz Trimmer. */
object SubTrimHandoff { var text: String? = null; var name: String? = null }

/** Process-lifetime Decode-page state so leaving (e.g. into the Trimmer) and returning restores the
 *  loaded capture, its name and the tuner selections. */
object DecodeState {
    var sub by mutableStateOf<String?>(null)
    var name by mutableStateOf("capture")
    var freqIdx = DEFAULT_FREQ_IDX
    var modIdx = 0
    var devIdx = 2
    // Live-capture window length in seconds (1–30), persisted across app launches.
    var captureSecs = 4
    private const val PREFS = "fox_decode"
    private const val KEY_SECS = "capture_secs"
    fun loadDuration(ctx: android.content.Context) {
        captureSecs = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getInt(KEY_SECS, captureSecs).coerceIn(1, 30)
    }
    fun saveDuration(ctx: android.content.Context, secs: Int) {
        captureSecs = secs.coerceIn(1, 30)
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putInt(KEY_SECS, captureSecs).apply()
    }
}

@Composable
fun DecodeScreen(onOpenTrimmer: () -> Unit = {}) {
    var openFob by remember { mutableStateOf<DecodeFeed.Item?>(null) }
    val fob = openFob
    if (fob != null) {
        // System/gesture back closes the fob detail back to the decode list (not out to DEVICE home).
        androidx.activity.compose.BackHandler { openFob = null }
        FobScreen(fob, onBack = { openFob = null })
        return
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var freqIdx by remember { mutableStateOf(DecodeState.freqIdx) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        SpecHandoff.tuneHz?.let { hz ->
            decodeFreqs.indices.minByOrNull { kotlin.math.abs(decodeFreqs[it].second - hz) }?.let { freqIdx = it }
            SpecHandoff.tuneHz = null
        }
    }
    var modIdx by remember { mutableStateOf(DecodeState.modIdx) }
    var devIdx by remember { mutableStateOf(DecodeState.devIdx) }
    // Live-capture window (seconds), restored from prefs on first entry and saved on change.
    var capSecs by remember { mutableStateOf(DecodeState.captureSecs) }
    androidx.compose.runtime.LaunchedEffect(Unit) { DecodeState.loadDuration(ctx); capSecs = DecodeState.captureSecs }
    // Stop/Cancel flow: once Stop is pressed we're "stopping" (FAP finishing + saving); Cancel abandons.
    var stopping by remember { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (ArkeEngine.available) "Engine ready · capture a fob via the Flipper" else "decode engine loading…") }
    var capturedSub by remember { mutableStateOf(DecodeState.sub ?: DecodeFeed.items.firstOrNull()?.subText?.ifEmpty { null }) }
    var capName by remember { mutableStateOf(DecodeState.name) }
    // Open-from-Flipper picker: browse /ext/subghz for .sub files.
    var showFlipperPicker by remember { mutableStateOf(false) }
    val flipperSubs = remember { androidx.compose.runtime.mutableStateListOf<Pair<String, String>>() }  // label -> full path
    var pickerBusy by remember { mutableStateOf(false) }
    // Capture Live button fill (0→1 over the capture window) and undecoded-RAW tracking for History.
    val captureProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    var rxStarting by remember { mutableStateOf(false) }   // FAP booting, before the record window
    var undecodedRaw by remember { mutableStateOf(false) }
    var rawLogged by remember { mutableStateOf(false) }
    val items = DecodeFeed.items

    // Persist Decode state so leaving (into the Trimmer) and returning restores the loaded capture.
    androidx.compose.runtime.LaunchedEffect(capturedSub, capName, freqIdx, modIdx, devIdx) {
        DecodeState.sub = capturedSub; DecodeState.name = capName
        DecodeState.freqIdx = freqIdx; DecodeState.modIdx = modIdx; DecodeState.devIdx = devIdx
    }

    fun tryDecode(sub: String, source: String) {
        scope.launch {
            val (frames, err) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ArkeEngine.decode(sub) }
            when {
                err != null -> { status = "decode error: $err"; undecodedRaw = true; rawLogged = false }
                frames.isEmpty() -> { status = "No known protocol in $source — save the RAW below, or open it in the Trimmer."; undecodedRaw = true; rawLogged = false }
                else -> { DecodeFeed.add(frames, sub); status = "Decoded ${frames.size} frame(s) · ${frames.first().make}"; undecodedRaw = false }
            }
        }
    }

    // If a capture didn't decode but the user saves it anyway, record it in History as "Unknown RAW".
    fun logUnknownRaw(sub: String) {
        if (rawLogged) return
        rawLogged = true
        HistoryStore.log("decode", "Unknown RAW", "${decodeFreqs[freqIdx].first} MHz · undecoded capture", sub, "sub")
    }

    fun listen() {
        if (!FlipperRf.connected) { status = "Connect the Flipper (Remote Screen) to capture."; return }
        busy = true; stopping = false
        val (fLabel, fHz) = decodeFreqs[freqIdx]
        val dev = decodeDevNames[devIdx].second
        val (modLabel, preset) = decodeMods[modIdx]
        val capMs = capSecs * 1000
        rxStarting = true
        status = "Starting RX on $fLabel MHz $modLabel…"
        captureJob = scope.launch {
            captureProgress.snapTo(0f)
            // The fill starts only once the FAP says recording is live (onStarted), so it lines up
            // with the real record window instead of the FAP-boot lag.
            val sub = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FlipperRf.rx(fHz, dev, capMs, preset, onStarted = {
                    scope.launch {
                        rxStarting = false
                        status = "Recording ${capSecs}s on $fLabel MHz $modLabel… press the fob now."
                        captureProgress.snapTo(0f)
                        captureProgress.animateTo(1f, androidx.compose.animation.core.tween(capMs, easing = androidx.compose.animation.core.LinearEasing))
                    }
                })
            }
            captureProgress.snapTo(0f); rxStarting = false; stopping = false
            if (sub == null) { busy = false; status = "Capture failed · ${FlipperRf.lastRxDiag}"; return@launch }
            capturedSub = sub; capName = "capture_${fLabel.replace('.', '_')}"
            busy = false
            tryDecode(sub, "the capture — try again, fob closer")
        }
    }

    // Open a .sub from the phone (any folder), show its waveform, and try to decode it.
    val openSubLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            if (text.isNullOrBlank()) { status = "Couldn't read that .sub file."; return@launch }
            val name = runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                }
            }.getOrNull() ?: "opened.sub"
            capturedSub = text; capName = name.removeSuffix(".sub")
            status = "Opened $name."
            tryDecode(text, name)
        }
    }

    fun saveRawPhone() {
        val sub = capturedSub ?: return
        scope.launch {
            val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                pushToDownloads(ctx, "${capName}.sub", sub.toByteArray())
            }
            status = msg
            if (undecodedRaw) logUnknownRaw(sub)
        }
    }
    fun saveRawFlipper() {
        val sub = capturedSub ?: return
        if (!FlipperRf.connected) { status = "Connect the Flipper first."; return }
        scope.launch {
            status = "Saving to the Flipper…"
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val t = FlipperTransport.active ?: return@withContext false
                t.storageMkdir("/ext/subghz")
                var file = "${capName}.sub"; var n = 2
                while (t.storageStat("/ext/subghz/$file")) { file = "${capName}($n).sub"; n++ }
                t.storageWrite("/ext/subghz/$file", sub.toByteArray())
            }
            status = if (ok) "Saved to /ext/subghz on the Flipper." else "Save to Flipper failed — is it on its desktop?"
            if (ok && undecodedRaw) logUnknownRaw(sub)
        }
    }

    // List .sub files in /ext/subghz (plus one sub-folder level) for the Open-from-Flipper picker.
    fun loadFlipperSubs() {
        if (!FlipperRf.connected) { status = "Connect the Flipper first."; return }
        pickerBusy = true; flipperSubs.clear()
        scope.launch {
            val t = FlipperTransport.active
            val root = "/ext/subghz"
            val top = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { t?.storageList(root) } ?: emptyList()
            top.filter { !it.isDir && it.name.endsWith(".sub", true) }.forEach { flipperSubs.add(it.name to "$root/${it.name}") }
            for (d in top.filter { it.isDir }) {
                val sub = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { t?.storageList("$root/${d.name}") } ?: continue
                sub.filter { !it.isDir && it.name.endsWith(".sub", true) }.forEach { flipperSubs.add("${d.name}/${it.name}" to "$root/${d.name}/${it.name}") }
            }
            pickerBusy = false
        }
    }
    fun openFlipperSub(label: String, path: String) {
        showFlipperPicker = false
        scope.launch {
            status = "Reading $label…"
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperTransport.active?.storageRead(path) }
            val text = bytes?.toString(Charsets.UTF_8)
            if (text.isNullOrBlank()) { status = "Couldn't read $label."; return@launch }
            capturedSub = text; capName = label.substringAfterLast('/').removeSuffix(".sub")
            status = "Opened $label."
            tryDecode(text, label)
        }
    }
    fun trim() {
        val sub = capturedSub
        if (sub == null) status = "Capture or open a .sub first."
        else { SubTrimHandoff.text = sub; SubTrimHandoff.name = capName; onOpenTrimmer() }
    }

    ScreenScroll {
        FoxCard {
            Column {
                com.foxfw.companion.ui.GraticuleScreen(Modifier.fillMaxWidth().height(96.dp)) {
                    Row(Modifier.align(Alignment.Center).fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                // TUNED + status pill sit over the number, sized to it, so the pill's right
                                // edge lines up with the end of the frequency digits; MHz hangs off the side.
                                Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("TUNED", color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, letterSpacing = 1.5.sp)
                                        Spacer(Modifier.weight(1f))
                                        Pill(if (busy) "RX" else "Idle", if (busy) Fox.live else Fox.amber, if (busy) Fox.liveDim else Fox.amberDim, dot = busy)
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(decodeFreqs[freqIdx].first, color = Fox.phos, fontFamily = FoxMono, fontSize = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("MHz", color = Fox.muted, fontFamily = FoxMono, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
                            }
                            Spacer(Modifier.height(3.dp))
                            Text("${decodeMods[modIdx].first} · CC1101", color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp, letterSpacing = 1.sp)
                        }
                        Box(Modifier.weight(0.85f).let { if (capturedSub != null) it.clickable { trim() } else it }) {
                            RawTrace(capturedSub, Modifier.fillMaxSize())
                            // Tiny Trim pill, top-centre of the waterfall (tapping it OR the waterfall → Trimmer).
                            if (capturedSub != null) {
                                Box(
                                    Modifier.align(Alignment.TopCenter)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(Fox.amber)
                                        .clickable { trim() }
                                        .padding(horizontal = 9.dp, vertical = 2.dp),
                                ) {
                                    Text("Trim", color = Fox.amberInk, fontFamily = FoxMono, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Frequency (MHz)"); Spacer(Modifier.height(6.dp))
                        PickDropdown(decodeFreqs.map { it.first }, freqIdx) { freqIdx = it }
                    }
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Modulation"); Spacer(Modifier.height(6.dp))
                        PickDropdown(decodeMods.map { it.first }, modIdx) { modIdx = it }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Eyebrow("CC1101")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    decodeDevNames.forEachIndexed { i, (lbl, _) ->
                        PickChip(lbl, i == devIdx, Modifier.weight(1f)) { devIdx = i }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Eyebrow("Method")
                Spacer(Modifier.height(6.dp))
                // Open sources (orange, Capture height), above the full-width Capture Live button.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrangeBtn("Open .sub (Phone)", Modifier.weight(1f)) { openSubLauncher.launch(arrayOf("*/*")) }
                    OrangeBtn("Open .sub (Flipper)", Modifier.weight(1f)) { showFlipperPicker = true; loadFlipperSubs() }
                }
                Spacer(Modifier.height(8.dp))
                val capHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                var durationMenuOpen by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(if (busy) Fox.surface2 else Fox.amber).clickable(enabled = !busy) {
                        capHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        listen()
                    }, contentAlignment = Alignment.Center) {
                    // Progress fill grows left→right over the record window (only once recording is live).
                    if (busy && !rxStarting) {
                        Box(Modifier.matchParentSize()) {
                            Box(Modifier.fillMaxWidth(captureProgress.value.coerceIn(0f, 1f)).fillMaxHeight().background(Fox.amber))
                        }
                    }
                    // "Capture Live" stays centred on the whole button regardless of the duration chip.
                    Text(when { rxStarting -> "Starting RX…"; busy -> "listening…"; else -> "Capture Live" },
                        color = if (busy) Fox.fg else Fox.amberInk,
                        fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 9.dp))
                    // Right-side duration picker — part of the same button; tapping it (not the body)
                    // opens the 1–30s list instead of starting a capture. Hidden while recording.
                    if (!busy) {
                        Box(Modifier.align(Alignment.CenterEnd)) {
                            Row(
                                Modifier.clip(RoundedCornerShape(7.dp)).clickable { durationMenuOpen = true }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${capSecs}s", color = Fox.amberInk, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Icon(Icons.Filled.ArrowDropDown, null, tint = Fox.amberInk, modifier = Modifier.size(16.dp))
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = durationMenuOpen, onDismissRequest = { durationMenuOpen = false },
                                modifier = Modifier.heightIn(max = 320.dp),
                            ) {
                                (1..30).forEach { s ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("${s}s", fontFamily = FoxMono, fontSize = 13.sp, color = if (s == capSecs) Fox.amber else Fox.fg) },
                                        onClick = { durationMenuOpen = false; capSecs = s; DecodeState.saveDuration(ctx, s) })
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Status line, with a Stop/Cancel pill while recording. Stop vanishes in the final second
                // so a late tap can't race the window's own end. Pressing Stop asks the Flipper to finish
                // + save now (status "Stopping RX…"); the pill becomes Cancel to abandon the partial
                // capture instead of waiting. When the capture ends — clicked or not — the whole row clears.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    val showStop = busy && !rxStarting && !stopping && captureProgress.value < (1f - 1f / capSecs.toFloat())
                    val showCancel = busy && stopping
                    if (showStop || showCancel) {
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFB4232F))
                                .clickable {
                                    if (showCancel) {
                                        captureJob?.cancel()
                                        busy = false; rxStarting = false; stopping = false
                                        status = "Capture cancelled"
                                        scope.launch { captureProgress.snapTo(0f) }
                                    } else {
                                        FlipperRf.requestRxStop(); stopping = true; status = "Stopping RX…"
                                    }
                                }
                                .padding(horizontal = 18.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (showCancel) "Cancel" else "Stop", color = Color.White,
                                fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                // Save the RAW (don't lose a non-decoding capture) — same size as the open buttons.
                if (capturedSub != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OrangeBtn("Save .sub (Phone)", Modifier.weight(1f)) { saveRawPhone() }
                        OrangeBtn("Save .sub (Flipper)", Modifier.weight(1f)) { saveRawFlipper() }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Decoded fobs — ${items.size}")
        }
        FoxCard {
            Column {
                if (items.isEmpty()) Text("No captures yet. Pick a frequency and capture a fob.", color = Fox.faint, fontFamily = FoxMono, fontSize = 12.sp)
                items.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    Box(Modifier.fillMaxWidth().clickable { openFob = e }) {
                        DecodeRow(DecodeEntry(e.proto, e.title, e.meta, "", e.bars, agoLabel(e.tMs)))
                    }
                }
            }
        }
    }

    // Open-from-Flipper picker — the /ext/subghz .sub list.
    if (showFlipperPicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showFlipperPicker = false },
            title = { Text("Open .sub from Flipper", color = Fox.fg) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    when {
                        pickerBusy -> Text("Scanning /ext/subghz…", color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                        flipperSubs.isEmpty() -> Text("No .sub files in /ext/subghz.", color = Fox.faint, fontFamily = FoxMono, fontSize = 12.sp)
                        else -> flipperSubs.forEach { (label, path) ->
                            Row(Modifier.fillMaxWidth().clickable { openFlipperSub(label, path) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, color = Fox.fg, fontFamily = FoxMono, fontSize = 11.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("open →", color = Fox.amber, fontFamily = FoxMono, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showFlipperPicker = false }) { Text("Close", color = Fox.amber) } },
            containerColor = Fox.surface,
        )
    }
}

/** Full amber action button (Capture height), used for the Decode open/save sources. */
@Composable
private fun OrangeBtn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(9.dp)).background(Fox.amber).clickable { onClick() }.padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Fox.amberInk, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PickDropdown(options: List<String>, index: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Fox.surface2)
                .border(1.dp, Fox.line2, RoundedCornerShape(9.dp)).clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.getOrElse(index) { "?" }, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, null, tint = Fox.muted, modifier = Modifier.size(16.dp))
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEachIndexed { i, opt ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(opt, fontFamily = FoxMono, fontSize = 13.sp, color = if (i == index) Fox.amber else Fox.fg) },
                    onClick = { open = false; onSelect(i) })
            }
        }
    }
}

@Composable
private fun PickChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.clickable { onClick() }, shape = RoundedCornerShape(9.dp),
        color = if (selected) Fox.amber else Fox.surface2, border = BorderStroke(1.dp, if (selected) Fox.amber else Fox.line2)) {
        Box(Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) Fox.amberInk else Fox.muted, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/* ------------------------------- ARCHIVE ------------------------------- */
data class SavedFile(val file: java.io.File, val meta: String, val freqHz: Int)

private fun scanSavedSubs(ctx: android.content.Context): List<SavedFile> {
    val dir = ctx.getExternalFilesDir(null) ?: return emptyList()
    return (dir.listFiles { f -> f.isFile && f.name.endsWith(".sub", true) } ?: emptyArray())
        .sortedByDescending { it.lastModified() }
        .map { f ->
            var freq = 433920000
            runCatching {
                f.bufferedReader().useLines { seq ->
                    seq.firstOrNull { it.startsWith("Frequency:") }?.substringAfter(":")?.trim()?.toIntOrNull()?.let { freq = it }
                }
            }
            val kb = (f.length() + 512) / 1024
            SavedFile(f, "%.2f MHz · %d KB".format(freq / 1e6, kb), freq)
        }
}

/**
 * Remembers where the Archive tab was last — which sub-view (SD browser vs saved captures) and, inside
 * the browser, which directory — so leaving and returning to the tab lands you back where you were
 * rather than resetting to the SD root. Lives for the process so it survives tab switches.
 */
internal object ArchiveNav {
    var browsing = true
    var path = "/ext"
}

@Composable
fun ArchiveScreen() {
    var browsing by remember { mutableStateOf(ArchiveNav.browsing) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val files = remember { mutableStateListOf<SavedFile>() }
    var status by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    fun refresh() { files.clear(); files.addAll(scanSavedSubs(ctx)) }
    LaunchedEffect(Unit) { refresh() }

    if (browsing) { SdBrowserScreen(onClose = { browsing = false; ArchiveNav.browsing = false; refresh() }); return }

    // Opening a saved capture → the fob D-pad page. Back (system or in-page) returns here, to the
    // Archive list exactly where the user was.
    var openFob by remember { mutableStateOf<DecodeFeed.Item?>(null) }
    val fob = openFob
    if (fob != null) {
        androidx.activity.compose.BackHandler { openFob = null }
        FobScreen(fob, onBack = { openFob = null })
        return
    }
    var renaming by remember { mutableStateOf<SavedFile?>(null) }
    var deleting by remember { mutableStateOf<SavedFile?>(null) }

    fun sendFromArchive(f: SavedFile) {
        scope.launch {
            status = "Opening ${f.file.name}…"
            val txt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { f.file.readText() }.getOrNull() }
            if (txt == null) { status = "Couldn't read ${f.file.name}."; return@launch }
            // Decode so a known fob opens its D-pad; otherwise a fixed-code/RAW page (RAW send only).
            val (frames, _) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ArkeEngine.decode(txt) }
            val frame = frames.firstOrNull() ?: ArkeEngine.Frame(
                "Fixed-code / RAW", null, null, null, null, null, null, null, null)
            status = ""
            openFob = DecodeFeed.makeItem(frame, txt)
        }
    }

    ScreenScroll {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Saved .sub — ${files.size}"); Spacer(Modifier.weight(1f))
            Text("BROWSE SD", color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { browsing = true; ArchiveNav.browsing = true })
            Spacer(Modifier.width(16.dp))
            Text("REFRESH", color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { refresh() })
        }
        if (files.isEmpty()) {
            FoxCard { Text("No saved captures yet. Forge in Pandora / Car & Gate Clone (OUTPUT: Save .sub), or capture in Decode.",
                color = Fox.muted, fontSize = 13.sp) }
        }
        if (files.isNotEmpty()) FoxCard {
            Column {
                files.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    val open = expanded == f.file.name
                    Row(Modifier.fillMaxWidth().clickable { expanded = if (open) null else f.file.name }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                            .background(Fox.surface2).border(1.dp, Fox.line2, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.GraphicEq, null, tint = Fox.amber, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.file.name, color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(f.meta, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = Fox.faint, modifier = Modifier.size(18.dp))
                    }
                    if (open) {
                        Column(Modifier.padding(bottom = 10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PickChip("Send", false, Modifier.weight(1f)) { expanded = null; sendFromArchive(f) }
                                PickChip("Rename", false, Modifier.weight(1f)) { renaming = f }
                                PickChip("Delete", false, Modifier.weight(1f)) { deleting = f }
                            }
                        }
                    }
                }
            }
        }
        if (status.isNotEmpty()) Text(status, color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp)
    }

    // Rename a saved capture (local phone file).
    renaming?.let { rf ->
        var newName by remember(rf.file.name) { mutableStateOf(rf.file.name.removeSuffix(".sub")) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val nn = newName.trim(); renaming = null
                    if (nn.isNotEmpty()) {
                        val dest = java.io.File(rf.file.parentFile, "$nn.sub")
                        val ok = runCatching { rf.file.renameTo(dest) }.getOrDefault(false)
                        status = if (ok) "Renamed to $nn.sub" else "Rename failed."
                        refresh()
                    }
                }) { Text("Rename", color = Fox.amber, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = null }) { Text("Cancel", color = Fox.muted) } },
            title = { Text("Rename", color = Fox.fg) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FoxMono, fontSize = 13.sp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Fox.fg, unfocusedTextColor = Fox.fg,
                        focusedBorderColor = Fox.amber, unfocusedBorderColor = Fox.line, cursorColor = Fox.amber),
                )
            },
            containerColor = Fox.surface,
        )
    }

    // Delete a saved capture (local phone file), with confirmation.
    deleting?.let { df ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deleting = null
                    runCatching { df.file.delete() }
                    expanded = null; refresh()
                    status = "Deleted ${df.file.name}"
                }) { Text("Delete", color = Fox.warn, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("Cancel", color = Fox.muted) } },
            title = { Text("Delete ${df.file.name}?", color = Fox.fg) },
            text = { Text("This removes the saved capture from the phone.", color = Fox.muted, fontSize = 13.sp) },
            containerColor = Fox.surface,
        )
    }
}

/* ------------------------------- SD BROWSER ------------------------------- */
private fun sizeLabel(n: Long): String =
    if (n < 1024) "$n B" else if (n < 1024 * 1024) "%.1f KB".format(n / 1024.0) else "%.1f MB".format(n / 1048576.0)

/** Browse the Flipper's microSD over BLE RPC and pull files down to the phone. */
@Composable
fun SdBrowserScreen(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var path by remember { mutableStateOf(ArchiveNav.path) }
    var entries by remember { mutableStateOf<List<com.foxfw.companion.Proto.FileEntry>?>(null) }  // null = loading
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<com.foxfw.companion.Proto.FileEntry?>(null) }
    var deleting by remember { mutableStateOf<com.foxfw.companion.Proto.FileEntry?>(null) }

    fun load(p: String) {
        entries = null; error = null; expanded = null
        scope.launch {
            if (!FlipperRf.connected) { error = "Connect the Flipper to browse its SD."; entries = emptyList(); return@launch }
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperTransport.active?.storageList(p) }
            if (r == null) { error = "Couldn't read $p"; entries = emptyList() } else entries = r
        }
    }
    // Remember the directory across tab switches, and (re)load whenever it changes.
    LaunchedEffect(path) { ArchiveNav.path = path; load(path) }

    // "/ext" is the top of the browse tree: the Up button is disabled there and can't climb past it.
    val atRoot = path == "/ext"
    fun up() { if (!atRoot) path = path.substringBeforeLast('/').ifEmpty { "/ext" } }
    // Hardware back climbs a directory; at the root it leaves the browser for the saved-captures view.
    androidx.activity.compose.BackHandler { if (atRoot) onClose() else up() }

    ScreenScroll {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                .background(if (atRoot) Fox.surface2 else Fox.surface)
                .border(1.dp, Fox.line, RoundedCornerShape(9.dp))
                .clickable(enabled = !atRoot) { up() },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowUpward, "Up one directory",
                    tint = if (atRoot) Fox.faint else Fox.fg, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("Flipper SD")
                Spacer(Modifier.height(3.dp))
                Text(path, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("SAVED CAPTURES", color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onClose() })
        }

        FoxCard {
            Column {
                val list = entries
                when {
                    list == null -> Text("reading…", color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                    error != null -> Text(error!!, color = Fox.warn, fontFamily = FoxMono, fontSize = 12.sp)
                    list.isEmpty() -> Text("Empty folder.", color = Fox.faint, fontFamily = FoxMono, fontSize = 12.sp)
                    else -> list.forEachIndexed { i, e ->
                        if (i > 0) Divider()
                        val isOpen = !e.isDir && expanded == e.name
                        Row(Modifier.fillMaxWidth().clickable {
                            if (e.isDir) path = "$path/${e.name}" else expanded = if (isOpen) null else e.name
                        }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))
                                .background(Fox.surface2).border(1.dp, Fox.line2, RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center) {
                                Icon(if (e.isDir) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile, null,
                                    tint = if (e.isDir) Fox.amber else Fox.muted, modifier = Modifier.size(17.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name, color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                Text(if (e.isDir) "folder" else sizeLabel(e.size), color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp)
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = Fox.faint, modifier = Modifier.size(18.dp))
                        }
                        if (isOpen) {
                            val full = "$path/${e.name}"
                            val launch = launchTargetFor(e.name, full)   // (target, args) or null if not an openable type
                            Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PickChip("Push to Phone", false, Modifier.fillMaxWidth()) {
                                    scope.launch {
                                        status = "Pushing ${e.name}…"
                                        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FlipperTransport.active?.storageRead(full) }
                                        if (bytes == null) { status = "Push failed — ${e.name}"; return@launch }
                                        status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pushToDownloads(ctx, e.name, bytes) }
                                    }
                                }
                                if (launch != null) PickChip("Launch App on Flipper", false, Modifier.fillMaxWidth()) {
                                    if (!FlipperRf.connected) { status = "Connect the Flipper first."; return@PickChip }
                                    scope.launch {
                                        status = "Opening ${e.name} on the Flipper…"
                                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            FlipperTransport.active?.appStartSafe(launch.first, launch.second) ?: false
                                        }
                                        status = if (ok) "Opened ${e.name} on the Flipper."
                                        else "Couldn't open ${e.name} — close the app running on the Flipper and try again."
                                    }
                                }
                                PickChip("Rename", false, Modifier.fillMaxWidth()) { renaming = e }
                                PickChip("Delete", false, Modifier.fillMaxWidth()) { deleting = e }
                            }
                        }
                    }
                }
            }
        }
        if (status.isNotEmpty()) Text(status, color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp)
    }

    // Rename dialog.
    renaming?.let { ent ->
        val full = "$path/${ent.name}"
        var newName by remember(ent.name) { mutableStateOf(ent.name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val nn = newName.trim(); renaming = null
                    if (nn.isNotEmpty() && nn != ent.name) scope.launch {
                        status = "Renaming…"
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            FlipperTransport.active?.storageRename(full, "$path/$nn") ?: false
                        }
                        status = if (ok) "Renamed to $nn" else "Rename failed."
                        load(path)
                    }
                }) { Text("Rename", color = Fox.amber, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = null }) { Text("Cancel", color = Fox.muted) } },
            title = { Text("Rename", color = Fox.fg) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FoxMono, fontSize = 13.sp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Fox.fg, unfocusedTextColor = Fox.fg,
                        focusedBorderColor = Fox.amber, unfocusedBorderColor = Fox.line, cursorColor = Fox.amber),
                )
            },
            containerColor = Fox.surface,
        )
    }

    // Delete confirmation.
    deleting?.let { ent ->
        val full = "$path/${ent.name}"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        status = "Deleting ${ent.name}…"
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            FlipperTransport.active?.storageDelete(full, ent.isDir) ?: false
                        }
                        status = if (ok) "Deleted ${ent.name}" else "Delete failed."
                        load(path)
                    }
                }) { Text("Delete", color = Fox.warn, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("Cancel", color = Fox.muted) } },
            title = { Text("Delete ${ent.name}?", color = Fox.fg) },
            text = { Text("This permanently removes it from the Flipper SD.", color = Fox.muted, fontSize = 13.sp) },
            containerColor = Fox.surface,
        )
    }
}

/**
 * Flipper file associations: which built-in app opens a given file, mirroring the firmware's archive.
 * A .fap is itself an app (launched directly, no args); every other known type opens its data file in
 * the named built-in. Returns (appTarget, args) or null when the extension isn't an openable type.
 */
private fun launchTargetFor(name: String, fullPath: String): Pair<String, String>? {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext == "fap") return fullPath to ""
    val app = when (ext) {
        "sub" -> "Sub-GHz"
        "nfc" -> "NFC"
        "ir" -> "Infrared"
        "rfid" -> "125 kHz RFID"
        "ibtn" -> "iButton"
        else -> return null
    }
    return app to fullPath
}

/** Save bytes pulled off the Flipper into the phone's Downloads/FoxFW folder. Returns a status line. */
private fun pushToDownloads(ctx: android.content.Context, filename: String, bytes: ByteArray): String = try {
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/FoxFW")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "Couldn't create the file in Downloads."
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "Saved $filename to Downloads/FoxFW."
    } else {
        val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "FoxFW").apply { mkdirs() }
        val f = java.io.File(dir, filename); f.writeBytes(bytes)
        "Saved to ${f.absolutePath}"
    }
} catch (e: Exception) { "Push to phone failed: ${e.message}" }

/* ------------------------------- SETTINGS ------------------------------- */
@Composable
fun SettingsScreen(fle: FlipperBle) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val connected = fle.conn == Conn.CONNECTED || UsbLink.usbConnected
    var clock by remember { mutableStateOf("—") }
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    var toast by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }   // auto-save in progress → floating pill

    fun refreshClock() {
        if (!connected) return
        scope.launch {
            val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { (FlipperTransport.active ?: fle).getDateTime() }
            clock = c?.let { "%04d-%02d-%02d  %02d:%02d:%02d".format(it.year, it.month, it.day, it.hour, it.minute, it.second) } ?: "—"
        }
    }
    LaunchedEffect(connected) { if (connected) refreshClock() }

    Box(Modifier.fillMaxSize()) {
        ScreenScroll {
            if (!connected) {
                FoxCard { Text("Connect the Flipper to edit FoxFW settings, sync the clock and use device controls.",
                    color = Fox.muted, fontSize = 13.sp) }
            } else {
                // FoxFW settings — inlined, auto-saving (the header refresh button reloads them).
                FoxSettingsContent(onSaving = { saving = it })
                // CLOCK
                FoxCard {
                    Column {
                        Eyebrow("CLOCK", color = Fox.amber); Spacer(Modifier.height(8.dp))
                        Text(clock, color = Fox.fg, fontFamily = FoxMono, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        BigButton("Sync clock to phone time") {
                            scope.launch {
                                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fle.setDateTime() }
                                toast = if (ok) "Clock synced" else "Sync failed"; if (ok) refreshClock()
                            }
                        }
                    }
                }
                // CONTROLS
                FoxCard {
                    Column {
                        Eyebrow("CONTROLS", color = Fox.amber); Spacer(Modifier.height(8.dp))
                        BigButton("Find my Flipper") {
                            scope.launch { val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fle.playAlert() }; toast = if (ok) "Alert sent" else "Failed" }
                        }
                        Spacer(Modifier.height(8.dp))
                        BigButton("Reboot") { confirm = Triple("Reboot", "Reboot the Flipper now?") { fle.reboot(0); toast = "Rebooting…" } }
                        Spacer(Modifier.height(8.dp))
                        BigButton("Reboot to DFU") { confirm = Triple("Reboot to DFU", "Reboot into DFU (firmware flashing) mode?") { fle.reboot(1); toast = "Rebooting to DFU…" } }
                        Spacer(Modifier.height(6.dp))
                        Text("To update firmware, tap Tools and scroll down to \"Firmware Update\".",
                            color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().clickable {
                            confirm = Triple("Factory reset", "This ERASES all data and settings on the Flipper and cannot be undone. Continue?") { fle.factoryReset(); toast = "Factory reset sent" }
                        }, contentAlignment = Alignment.Center) {
                            Text("Factory reset…", color = Color(0xFFFF5C78), fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
            }

            Text("FoxFW Companion ${BuildConfig.VERSION_NAME}", color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))

            if (toast.isNotEmpty()) {
                LaunchedEffect(toast) { kotlinx.coroutines.delay(1800); toast = "" }
                Text(toast, color = Fox.live, fontFamily = FoxMono, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }

        // Floating "saving changes" pill, centred just above the bottom nav — appears for ~2s on every edit.
        if (saving) {
            Box(Modifier.fillMaxSize().padding(bottom = 16.dp), contentAlignment = Alignment.BottomCenter) {
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(999.dp), color = Fox.amberDim,
                    border = BorderStroke(1.dp, Fox.amber.copy(alpha = 0.5f)), shadowElevation = 8.dp,
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = Fox.amber, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Saving changes…", color = Fox.amber, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    confirm?.let { (title, msg, action) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title, color = Fox.fg) },
            text = { Text(msg, color = Fox.muted) },
            containerColor = Fox.surface,
            confirmButton = { Text(title, color = Color(0xFFFF5C78), fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { action(); confirm = null }.padding(8.dp)) },
            dismissButton = { Text("Cancel", color = Fox.muted, modifier = Modifier.clickable { confirm = null }.padding(8.dp)) },
        )
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Fox.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(v, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
    }
}

@Composable
private fun RxRow(device: String, port: String, pillText: String, pillFg: androidx.compose.ui.graphics.Color, pillBg: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)? = null) {
    val row = Modifier.fillMaxWidth().padding(vertical = 3.dp).let { if (onClick != null) it.clickable { onClick() } else it }
    Row(row, verticalAlignment = Alignment.CenterVertically) {
        Text(device, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text(port, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Pill(pillText, pillFg, pillBg, dot = true)
    }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Uniform width (fits the longest control label) + centred, rather than stretched full-width.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.width(220.dp).clip(RoundedCornerShape(10.dp)).background(Fox.amber).clickable {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            onClick()
        }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Fox.amberInk, fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun prettify(k: String): String = k.replace('_', ' ').split(' ')
    .joinToString(" ") { w -> w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() } }

/* ------------------------------- shared ------------------------------- */
@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Fox.line))

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, accent: Boolean = false,
             valueColor: Color? = null, onClick: (() -> Unit)? = null) {
    val m = if (onClick != null) modifier.clickable { onClick() } else modifier
    Surface(modifier = m, shape = RoundedCornerShape(11.dp), color = Fox.surface, border = BorderStroke(1.dp, Fox.line)) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = valueColor ?: if (accent) Fox.amber else Fox.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text(label.uppercase(), color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, letterSpacing = 1.sp,
                lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** A tile that shows two key/value lines (e.g. Hardware + Radio FW), sized to sit beside StatTile. */
@Composable
fun InfoTile2(k1: String, v1: String, k2: String, v2: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(11.dp), color = Fox.surface, border = BorderStroke(1.dp, Fox.line)) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            InfoKV(k1, v1); Spacer(Modifier.height(8.dp)); InfoKV(k2, v2)
        }
    }
}

/** Firmware-update status tile — small text like the Hardware tile: "Up to Date", or green
 *  "Update Pending!" (then it links to the Firmware Update page). */
@Composable
fun FwUpdateTile(connected: Boolean, updateAvail: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val m = if (onClick != null) modifier.clickable { onClick() } else modifier
    Surface(modifier = m, shape = RoundedCornerShape(11.dp), color = Fox.surface, border = BorderStroke(1.dp, Fox.line)) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Text("FIRMWARE UPDATE", color = Fox.faint, fontFamily = FoxMono, fontSize = 8.sp, letterSpacing = 1.sp, lineHeight = 11.sp, maxLines = 2)
            Spacer(Modifier.height(7.dp))
            val (txt, col) = when {
                !connected -> "—" to Fox.fg
                updateAvail -> "Update Pending!" to Fox.live
                else -> "Up to Date" to Fox.fg
            }
            Text(txt, color = col, fontFamily = FoxMono, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Firmware install date + a live session uptime, in the InfoTile2 two-field style. The uptime ticks
 *  every second from when this connection came up (CompanionCache.connectedAtMs). */
@Composable
fun FwInfoTile(connected: Boolean, installDate: String?, connectedAtMs: Long?, modifier: Modifier = Modifier) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connected, connectedAtMs) {
        while (connected && connectedAtMs != null) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val uptime = if (!connected || connectedAtMs == null) "—" else {
        val s = ((nowMs - connectedAtMs) / 1000).coerceAtLeast(0L)
        "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
    val date = if (!connected) "—" else (installDate ?: "—")
    Surface(modifier = modifier, shape = RoundedCornerShape(11.dp), color = Fox.surface, border = BorderStroke(1.dp, Fox.line)) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            InfoKV("Install Date", date); Spacer(Modifier.height(8.dp)); InfoKV("Current Uptime", uptime)
        }
    }
}

@Composable
private fun InfoKV(k: String, v: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(k.uppercase(), color = Fox.faint, fontFamily = FoxMono, fontSize = 8.sp, letterSpacing = 1.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(v, color = Fox.fg, fontFamily = FoxMono, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun DecodeRow(e: DecodeEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ProtocolBadge(e.proto)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(e.meta, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.rssi, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(7.dp))
                SignalBars(e.bars)
            }
            Spacer(Modifier.height(6.dp))
            Text(e.ago, color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
        }
    }
}

// Parse a captured .sub's RAW_Data timings into (durationUs, isHigh) level segments.
private fun parseRawLevels(sub: String): List<Pair<Int, Boolean>> {
    val out = ArrayList<Pair<Int, Boolean>>()
    sub.lineSequence().forEach { ln ->
        val t = ln.trim()
        if (t.startsWith("RAW_Data:")) {
            t.removePrefix("RAW_Data:").trim().split(Regex("\\s+")).forEach { tok ->
                val v = tok.toIntOrNull() ?: return@forEach
                if (v != 0) out.add(Pair(kotlin.math.abs(v), v > 0))
            }
        }
    }
    return out
}

// Oscilloscope-style trace of the ACTUAL captured RAW signal (OOK/FSK level over time). Long silence
// gaps are capped so the burst stays visible. Drawn from the 4s capture — nothing synthetic. With no
// capture yet it draws a flat baseline of tiny bars along the bottom (nothing captured).
@Composable
private fun RawTrace(sub: String?, modifier: Modifier) {
    val bars = remember(sub) {
        val b = (if (sub == null) List(72) { 0.05f } else rawToBars(sub, 72)).toMutableList()
        if (b.isNotEmpty()) { b[0] = 1f; b[b.lastIndex] = 1f }   // bookend bars to full height — nicer framing
        b.toList()
    }
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        bars.forEach { v ->
            Box(Modifier.weight(1f).fillMaxHeight(v).clip(RoundedCornerShape(1.dp)).background(Fox.gradient()))
        }
    }
}

/** DEVICE-page animated "live receiver" waterfall. Bars scroll right-to-left: with saved captures it
 *  feeds real .sub traces (a random file at a time); with none it plays a mostly-silent pretend feed
 *  that occasionally fires a 3–6 bar spike. Same bar gradient as [RawTrace]; Canvas-drawn so the ~14fps
 *  scroll is cheap. */
@Composable
private fun SignalWaterfall(active: Boolean, hasSubs: Boolean, nextSubBars: suspend () -> List<Float>?, modifier: Modifier = Modifier) {
    val n = 60
    val bars = remember { androidx.compose.runtime.mutableStateListOf<Float>().apply { repeat(n) { add(0.035f) } } }
    LaunchedEffect(active, hasSubs) {
        val rnd = java.util.Random()
        val stream = ArrayDeque<Float>()
        var burst = 0
        var gap = 0
        while (true) {
            kotlinx.coroutines.delay(70)
            val next: Float = if (!active) {
                // Disconnected: a flat, nearly-still quiet line — the feed only "comes alive" once linked.
                0.025f + rnd.nextFloat() * 0.012f
            } else if (hasSubs) when {
                stream.isNotEmpty() -> stream.removeFirst()
                // ~2.5s of dead air after each sub so consecutive captures don't crowd together.
                gap > 0 -> { gap--; 0.025f + rnd.nextFloat() * 0.012f }
                else -> {
                    val sb = nextSubBars()
                    if (!sb.isNullOrEmpty()) stream.addAll(sb) else stream.addAll(List(12) { 0.045f })
                    gap = 36   // 36 × 70ms ≈ 2.5s gap before the next one
                    stream.removeFirst()
                }
            } else when {
                burst > 0 -> { burst--; 0.45f + rnd.nextFloat() * 0.5f }
                rnd.nextInt(100) < 1 -> { burst = 2 + rnd.nextInt(4); 0.4f + rnd.nextFloat() * 0.55f }
                else -> 0.03f + rnd.nextFloat() * 0.04f
            }
            if (bars.isNotEmpty()) bars.removeAt(0)
            bars.add(next.coerceIn(0f, 1f))
        }
    }
    Canvas(modifier) {
        val count = bars.size
        if (count == 0) return@Canvas
        val gap = size.width * 0.004f
        val bw = ((size.width - gap * (count - 1)) / count).coerceAtLeast(0.5f)
        val brush = Fox.gradient()
        for (i in 0 until count) {
            val bh = (size.height * bars[i]).coerceAtLeast(size.height * 0.02f)
            drawRect(brush = brush,
                topLeft = androidx.compose.ui.geometry.Offset(i * (bw + gap), size.height - bh),
                size = androidx.compose.ui.geometry.Size(bw, bh))
        }
    }
}

/** Pick a random saved .sub and collapse it to bars, off the main thread; null if there are no subs. */
private suspend fun randomSubBars(ctx: android.content.Context): List<Float>? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val files = ctx.getExternalFilesDir(null)?.listFiles { f -> f.name.endsWith(".sub", true) }
        if (files.isNullOrEmpty()) return@withContext null
        val f = files[java.util.Random().nextInt(files.size)]
        val text = runCatching { f.readText() }.getOrNull() ?: return@withContext null
        rawToBars(text, 48)
    }

// Collapse a RAW capture into a per-time-bucket activity envelope (transition density). A real burst
// piles transitions into a few buckets -> a spike; quiet/noise spreads thin -> a low baseline of
// small bars across the whole 4s.
private fun rawToBars(sub: String, n: Int): List<Float> {
    val levels = parseRawLevels(sub)
    if (levels.isEmpty()) return List(n) { 0.06f }
    val total = levels.sumOf { it.first.toLong() }.coerceAtLeast(1L)
    val counts = IntArray(n)
    var t = 0L
    for ((dur, _) in levels) {
        val b = ((t.toDouble() / total) * n).toInt().coerceIn(0, n - 1)
        counts[b]++
        t += dur
    }
    return counts.map { d -> (0.06f + 0.9f * (d.toFloat() / (d + 8f))).coerceIn(0.04f, 1f) }
}


@Composable
private fun SettingToggle(key: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(key, color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(sub, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
            }
        }
        FoxSwitch(checked, onChange)
    }
}

@Composable
private fun FoxSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val thumbX by androidx.compose.animation.core.animateDpAsState(
        if (checked) 19.dp else 0.dp,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "thumb",
    )
    val track by androidx.compose.animation.animateColorAsState(
        if (checked) Fox.amber else Fox.surface2,
        androidx.compose.animation.core.tween(180), label = "track",
    )
    val edge by androidx.compose.animation.animateColorAsState(
        if (checked) Fox.amber else Fox.line2,
        androidx.compose.animation.core.tween(180), label = "edge",
    )
    Box(
        Modifier.width(42.dp).height(25.dp).clip(RoundedCornerShape(999.dp))
            .background(track).border(1.dp, edge, RoundedCornerShape(999.dp))
            .clickable {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onChange(!checked)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.padding(horizontal = 2.dp).offset(x = thumbX).size(19.dp)
            .clip(RoundedCornerShape(999.dp)).background(Color.White))
    }
}
