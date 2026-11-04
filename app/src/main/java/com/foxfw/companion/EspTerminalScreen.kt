package com.foxfw.companion

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A single-subsystem ESP32 terminal — a pared-down copy of the ESP32 page (same console, input bar and
 * immersive keyboard behaviour, same transport + detection) scoped to one command set. No portal, no
 * networks/scan/capture controls: just the console, the domain's commands as always-visible buttons,
 * and the raw input. One instance per domain (WiFi / BLE / GPS / IR / Sub-GHz / RFID), each with its
 * own retained [EspSession] so the console, scroll and text box survive leaving and reopening.
 */
private data class EspDomain(val title: String, val cats: Set<String>)

private val ESP_DOMAINS = mapOf(
    "wifi" to EspDomain("ESP32 WiFi", setOf("Scan", "Sniff", "WiFi attacks", "Channel", "Lists & targets", "Network recon")),
    "ble" to EspDomain("ESP32 BLE", setOf("Bluetooth")),
    "gps" to EspDomain("ESP32 GPS", setOf("GPS / wardrive")),
    "ir" to EspDomain("ESP32 IR", setOf("Infrared")),
    "subghz" to EspDomain("ESP32 SubGHz", setOf("Sub-GHz")),
    "rfid" to EspDomain("ESP32 RFID", setOf("RFID")),
)

@Composable
fun EspTerminalScreen(domainKey: String, onClose: () -> Unit) {
    val domain = ESP_DOMAINS[domainKey] ?: run { onClose(); return }
    // Header refresh = full reload: wipe the shared console + board state and reconnect (Esp32Hub.reset),
    // recreate the body, reset this page's input/scroll — same as the main ESP32 page.
    var reloadNonce by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val reloadScope = rememberCoroutineScope()
    androidx.compose.runtime.key(reloadNonce) {
        EspTerminalBody(domainKey, domain, refreshing) {
            if (!refreshing) {
                refreshing = true
                reloadScope.launch { delay(1000); Esp32Hub.reset(); EspSession.reset("term:$domainKey"); reloadNonce++; refreshing = false }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun EspTerminalBody(domainKey: String, domain: EspDomain, refreshing: Boolean, onReload: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    val session = remember { EspSession.get("term:$domainKey") }
    LaunchedEffect(Unit) { Esp32Hub.enter(ctx) }   // one shared connection for the whole ESP32 area

    // Shared board state + console live in Esp32Hub; only the input box + scroll are per-page.
    val mode by Esp32Hub.modeState
    val lines = Esp32Hub.lines
    var savedUpTo by Esp32Hub.savedUpToState
    var text by session.text
    val foxBoard by Esp32Hub.foxBoardState
    val detect by Esp32Hub.detectState
    val status by Esp32Hub.statusState
    var pillInfo by remember { mutableStateOf<String?>(null) }

    val connected = Esp32Hub.connected

    val consoleScroll = rememberScrollState()
    val pageScroll = rememberScrollState(session.scrollValue)
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) consoleScroll.animateScrollTo(consoleScroll.maxValue) }

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
            scope.launch { pageScroll.animateScrollTo(0) }
        }
    }
    fun saveNow() {
        val path = CliLog.append(ctx, lines.subList(savedUpTo, lines.size).toList())
        savedUpTo = lines.size
        log(if (path != null) "[log saved → $path]" else "[nothing new to save]")
    }

    // ---- focus-driven immersive (exactly like the Flipper CLI / ESP32 page) ----
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
            session.scrollValue = pageScroll.value
            if (savedUpTo < lines.size) {
                runCatching { CliLog.append(ctx, lines.subList(savedUpTo, lines.size).toList()) }
                savedUpTo = lines.size
            }
            // The shared link is NOT torn down here — the hub owns it (stays up across navigation,
            // drops only on explicit reset or when the app is backgrounded).
        }
    }

    // FW-appropriate command set for this subsystem only.
    val catalog = if (foxBoard) Esp32.foxCatalog else Esp32.catalog
    val cmds = catalog.filter { it.name in domain.cats }

    val refreshSpin = rememberInfiniteTransition(label = "term-refresh")
    val refreshAngle by refreshSpin.animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "term-refresh-a")

    @Composable
    fun ConsoleLines() {
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
      Column(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp)) {
        if (!focused) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Column {
                    Text(domain.title, color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Eyebrow(when {
                        detect == EspDetect.FOX -> "Fox ESP32 FW"
                        detect == EspDetect.NONFOX -> "Marauder / stock"
                        detect == EspDetect.ERROR -> "detection error"
                        connected -> "detecting…"
                        else -> "BFFB v2"
                    })
                }
                Spacer(Modifier.weight(1f))
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
                    val conns = if (mode == Esp32Mode.USB) listOf("USB", "ESP32")
                        else if (FlipperTransport.kind == LinkKind.BLE) listOf("BLE")
                        else listOf("USB", "FLPR")
                    conns.forEach { c -> Spacer(Modifier.width(6.dp)); Pill(c, Fox.live, Fox.liveDim) }
                } else Pill("offline", Fox.muted, Fox.line)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (focused) {
                Column(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Fox.bg).border(1.dp, Fox.line, RoundedCornerShape(12.dp))
                    .verticalScroll(consoleScroll).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)) { ConsoleLines() }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(pageScroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // CONSOLE card
                    com.foxfw.companion.ui.FoxCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Eyebrow("Console")
                                Spacer(Modifier.weight(1f))
                                Text("clear", color = Fox.muted, fontSize = 10.5.sp, modifier = Modifier.clickable { lines.clear() })
                            }
                            Box(Modifier.fillMaxWidth().height(174.dp).clip(RoundedCornerShape(10.dp))
                                .background(Fox.bg).border(1.dp, Fox.line, RoundedCornerShape(10.dp))) {
                                Column(Modifier.fillMaxWidth().verticalScroll(consoleScroll).padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)) { ConsoleLines() }
                            }
                        }
                    }

                    Text(status, color = Fox.faint, fontSize = 10.5.sp, lineHeight = 14.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

                    // Commands for this subsystem — always laid out, grouped by section. Tap drops it
                    // into the box above (edit any argument, then Send).
                    if (cmds.isEmpty()) {
                        Text("No ${domain.title.removePrefix("ESP32 ")} commands for this board — connect a Fox ESP32 board.",
                            color = Fox.faint, fontSize = 11.sp, lineHeight = 15.sp)
                    } else cmds.forEach { cat ->
                        com.foxfw.companion.ui.FoxCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

        // input + Send/Save — full-width bar flush to the bottom nav, like the ESP32 page.
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
}
