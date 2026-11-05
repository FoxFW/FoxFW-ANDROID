package com.foxfw.companion

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxFWTheme
import com.foxfw.companion.ui.FoxMono

enum class Tab(val label: String, val title: String, val sub: String, val icon: ImageVector) {
    DEVICE("Device", "Device", "Fox Firmware · BLE linked", Icons.Outlined.Smartphone),
    DECODE("Decode", "Decode", "433.92 MHz · live RX", Icons.Outlined.GraphicEq),
    ARCHIVE("Archive", "Archive", "on-device SD captures", Icons.Outlined.Inventory2),
    TOOLS("Tools", "Tools", "RF analysis & cracking", Icons.Outlined.GridView),
    SETTINGS("Settings", "Settings", "Fox Firmware", Icons.Outlined.Settings),
}

class MainActivity : ComponentActivity() {
    private var usbReceiver: android.content.BroadcastReceiver? = null

    override fun onDestroy() {
        usbReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug-only preview: `adb shell am start -n com.foxfw.companion/.MainActivity --ez demo_capture true`
        // fires the real HandshakeNotifier so the capture notification UX can be seen without a live capture.
        if (BuildConfig.PRIVATE_BUILD && intent?.getBooleanExtra("demo_capture", false) == true) {
            HandshakeNotifier.notifyCapture(
                this, "⚡ Handshake captured",
                "Jenny's Home (-30 dBm) · Full WPA handshake captured · 1 total — saved to SD (.pcap)",
            )
        }
        usbReceiver = UsbLink.register(this) // auto-detect a plugged-in Flipper (USB-preferred transport)
        UsbLink.scan(this)
        setContent {
            FoxFWTheme {
                val fle = remember { FoxApplication.instance.ble }
                var showPicker by remember { mutableStateOf(false) }
                val perms = remember {
                    val l = ArrayList<String>()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        l += android.Manifest.permission.BLUETOOTH_SCAN
                        l += android.Manifest.permission.BLUETOOTH_CONNECT
                    } else {
                        l += android.Manifest.permission.ACCESS_FINE_LOCATION
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        l += android.Manifest.permission.POST_NOTIFICATIONS
                    }
                    l.toTypedArray()
                }
                val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
                ) { res ->
                    val bleOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        res[android.Manifest.permission.BLUETOOTH_CONNECT] == true &&
                            res[android.Manifest.permission.BLUETOOTH_SCAN] == true
                    else res[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
                    // Instead of grabbing the first Flipper, open the picker and scan for all of them.
                    if (bleOk) { showPicker = true; fle.startDiscovery() }
                }
                val onConnect: () -> Unit = {
                    if (fle.conn == Conn.CONNECTED || fle.conn == Conn.SCANNING || fle.conn == Conn.CONNECTING) fle.disconnect()
                    else launcher.launch(perms)
                }
                var splash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(2900); splash = false }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var showWhatsNew by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { if (WhatsNew.shouldShow(ctx)) showWhatsNew = true }
                LaunchedEffect(Unit) { FwRepo.load(ctx); runCatching { FwRepo.sync(ctx) } } // silent throttled check + background prefetch
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    FoxApp(fle, onConnect, onExit = { finish() })
                    FoxGateDialogs(fle) // FoxFW v2.1+ / companion-IO gate on connect
                    if (showPicker) FlipperPickerDialog(
                        fle = fle,
                        onConnect = { f -> showPicker = false; fle.connectToDevice(f) },
                        onRefresh = { fle.startDiscovery() },
                        onCancel = { showPicker = false; fle.stopDiscovery() },
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showWhatsNew && !splash,
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)),
                    ) { WhatsNewSheet(onDismiss = { WhatsNew.markSeen(ctx); showWhatsNew = false }) }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = splash,
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(550)),
                    ) { SplashScreen() }
                }
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(androidx.compose.ui.graphics.Color(0xFF1B1522), Fox.page), radius = 1500f
            )
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            com.foxfw.companion.ui.FoxLogo(150.dp)
            com.foxfw.companion.ui.GradientText("FOXFW 2.1", fontSize = 30)
            Text("CUSTOM FIRMWARE", color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp, letterSpacing = 5.sp)
            Text(
                "“The one without limits knows not how to push them.”",
                color = Fox.amber, fontFamily = FoxMono, fontSize = 10.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 36.dp),
            )
            LoadingBar()
        }
    }
}

@Composable
private fun LoadingBar() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "load")
    val x by t.animateFloat(
        -1f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "x",
    )
    androidx.compose.foundation.layout.Box(
        Modifier.width(124.dp).height(3.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(Fox.surface2)
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth(0.4f).height(3.dp).offset(x = (x.coerceIn(-1f, 1f) * 90).dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(Fox.gradient())
        )
    }
}

@Composable
fun FoxApp(fle: FlipperBle, onConnect: () -> Unit, onExit: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.DEVICE) }
    var toolsTarget by remember { mutableStateOf<String?>(null) }
    var showExit by remember { mutableStateOf(false) }
    var showDisconnect by remember { mutableStateOf(false) }
    var toolsCategory by remember { mutableStateOf("general") }
    var trimFromDecode by remember { mutableStateOf(false) }   // Trimmer opened from Decode → BACK returns there

    // Leaving the open tool: normally back to the tools list, but a Trimmer opened from Decode returns there.
    val leaveTool: () -> Unit = {
        if (trimFromDecode && toolsTarget == "subghz_trim") tab = Tab.DECODE
        trimFromDecode = false; toolsTarget = null; fle.incoming = null
    }

    // Phone BACK: step back through the nav instead of killing the app; confirm before closing.
    BackHandler(enabled = true) {
        when {
            toolsTarget != null -> leaveTool()           // leave the open tool
            tab != Tab.DEVICE -> tab = Tab.DEVICE        // any tab -> home
            else -> showExit = true                      // at home -> confirm close
        }
    }

    // When the Flipper streams an offload job, jump straight to the Hitag2 screen.
    LaunchedEffect(fle.incoming) {
        if (fle.incoming != null) { tab = Tab.TOOLS; toolsTarget = "hitag2" }
    }
    // When the Flipper offloads a PSA TEA brute, jump to the PSA screen.
    LaunchedEffect(PsaOffload.requestNonce) {
        if (PsaOffload.requestNonce > 0) { tab = Tab.TOOLS; toolsTarget = "psa" }
    }

    // Companion retry is MANUAL, never on a timer: an automatic probe would keep re-launching the
    // daemon on the Flipper and fight the user when they've deliberately closed it / moved to another
    // app. The banner's RETRY button is the only thing that re-sends (which makes the Flipper reopen
    // the daemon). A normal successful op still clears the "denied" state on its own.
    // Transport-aware link state: USB (preferred) or BLE.
    val linked = fle.conn == Conn.CONNECTED || UsbLink.usbConnected
    val companionScope = androidx.compose.runtime.rememberCoroutineScope()
    val sessionCli = remember { FlipperCli() }
    var companionRetrying by remember { mutableStateOf(false) }
    val onCompanionRetry: () -> Unit = {
        if (!companionRetrying && linked) companionScope.launch {
            companionRetrying = true
            if (CompanionLink.hasPending) CompanionLink.flush()
            else {
                val s = runCatching { sessionCli.receiverStatus() }.getOrNull()
                if (s != null) {
                    CompanionCache.rx = s
                    if (CompanionCache.protocols == null) CompanionCache.protocols = s.protocols
                }
            }
            companionRetrying = false
        }
    }

    // Auto-flush banked changes when the Flipper frees up. Safe now that the daemon is one-shot: each
    // attempt just tries to launch it for one command — if another app is open the launch fails and
    // NOTHING pops up on the Flipper; the moment the user is back on the desktop the launch succeeds,
    // the banked changes apply, and the daemon exits. Only runs while something is actually queued.
    LaunchedEffect(CompanionLink.pendingCount, linked) {
        while (CompanionLink.pendingCount > 0 && linked) {
            kotlinx.coroutines.delay(3000)
            if (!linked) break
            if (!companionRetrying) CompanionLink.flush()
        }
    }

    // Fetch receiver status + protocol count ONCE per connection, cached session-wide. Screens read
    // the cache instead of fetching on every visit — that re-launched the daemon and flashed the
    // Flipper's loader wheel each time you returned to DEVICE. Cleared on disconnect (protocols stays).
    LaunchedEffect(linked) {
        if (linked) {
            if (CompanionCache.rx == null) {
                val s = runCatching { sessionCli.receiverStatus() }.getOrNull()
                if (s != null) {
                    CompanionCache.rx = s
                    if (CompanionCache.protocols == null) CompanionCache.protocols = s.protocols
                }
            }
            // Hardware id + radio/BLE stack FW are static per device: pull them once over fast RPC
            // (device_info) and keep them for the whole session.
            if (CompanionCache.hw == null || CompanionCache.radioFw == null) {
                val info = runCatching { (FlipperTransport.active ?: fle).deviceInfo() }.getOrNull()
                if (info != null) {
                    fun k(name: String) = info.firstOrNull { it.first.lowercase().filter(Char::isLetterOrDigit) == name }?.second
                    val hwVer = k("hardwarever")
                    if (hwVer != null && CompanionCache.hw == null) {
                        // qFlipper-style hardware code: "<ver>.F<target><body><connect><display><color>",
                        // each a single uppercase hex digit (e.g. 15 + F + 7,B,9,C,6 -> "15.F7B9C6").
                        fun hx(name: String) = k(name)?.toIntOrNull()?.toString(16)?.uppercase() ?: ""
                        CompanionCache.hw = "$hwVer.F${hx("hardwaretarget")}${hx("hardwarebody")}" +
                            "${hx("hardwareconnect")}${hx("hardwaredisplay")}${hx("hardwarecolor")}"
                    }
                    val maj = k("radiostackmajor")
                    if (maj != null && CompanionCache.radioFw == null) {
                        val label = when (k("radiostacktype")) {
                            "1" -> "FULL"; "2" -> "HCI"; "3" -> "LITE"; "4" -> "BEACON"; "5" -> "BASIC"; else -> null
                        }
                        CompanionCache.radioFw = "$maj.${k("radiostackminor") ?: "0"}.${k("radiostacksub") ?: "0"}" +
                            (label?.let { " $it" } ?: "")
                    }
                    // Installed FoxFW version (device_info "fox_version"), for the DEVICE firmware tiles.
                    if (CompanionCache.fwVersion == null) k("foxversion")?.let { CompanionCache.fwVersion = it }
                }
            }
        } else {
            CompanionCache.onDisconnected()
        }
    }

    // The CLIs draw as a full-screen layer over this Scaffold. The ESP32 console goes immersive in place
    // (EspImmersive.on) — when its input is focused we hide the header + bottom nav here too.
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Fox.bg,
        bottomBar = {
            if (!EspImmersive.on) Column {
                if (CompanionLink.accessDenied || CompanionLink.pendingCount > 0)
                    CompanionBanner(CompanionLink.pendingCount, companionRetrying, onCompanionRetry)
                // Switching tabs remembers the open tool: returning to TOOLS reopens where you were.
                FoxTabBar(tab) { nt -> trimFromDecode = false; tab = nt }
            }
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
        ) {
            if (!EspImmersive.on) AppHeader(tab, tab == Tab.TOOLS && toolsTarget != null, fle, onConnect, { showDisconnect = true }, { tab = Tab.DEVICE }, toolsCategory, { toolsCategory = it }) { leaveTool() }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.DEVICE -> DeviceScreen(fle, onConnect, onDisconnect = { showDisconnect = true }, onOpenDecode = { tab = Tab.DECODE },
                    onOpenSavedCaptures = { ArchiveNav.browsing = false; tab = Tab.ARCHIVE },
                    onOpenTool = { tid -> toolsCategory = "general"; toolsTarget = tid; tab = Tab.TOOLS })
                Tab.DECODE -> DecodeScreen(onOpenTrimmer = { trimFromDecode = true; toolsCategory = "rf"; toolsTarget = "subghz_trim"; tab = Tab.TOOLS })
                Tab.ARCHIVE -> ArchiveScreen()
                Tab.TOOLS -> ToolsScreen(
                    fle = fle,
                    category = toolsCategory,
                    openTarget = toolsTarget,
                    onOpenTarget = { toolsTarget = it },
                    onClose = { leaveTool() },
                )
                Tab.SETTINGS -> SettingsScreen(fle)
            }
            }
        }
        if (showExit) AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text("Close FoxFW?", color = Fox.fg) },
            text = { Text("Are you sure you want to close FoxFW for Android?", color = Fox.muted, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { showExit = false; onExit() }) { Text("Close", color = Fox.amber) } },
            dismissButton = { TextButton(onClick = { showExit = false }) { Text("Stay", color = Fox.muted) } },
            containerColor = Fox.surface,
        )
        if (showDisconnect) AlertDialog(
            onDismissRequest = { showDisconnect = false },
            title = { Text("Disconnect Bluetooth?", color = Fox.fg) },
            text = { Text("Are you sure you want to disconnect Bluetooth?", color = Fox.muted, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { showDisconnect = false; tab = Tab.DEVICE; if (fle.conn == Conn.CONNECTED) fle.disconnect() }) { Text("Yes", color = Fox.amber) } },
            dismissButton = { TextButton(onClick = { showDisconnect = false }) { Text("No", color = Fox.muted) } },
            containerColor = Fox.surface,
        )

        // Connect-time "update ready?" prompt (Yes / No / Remind me) → Yes opens Firmware Update.
        FwUpdateDialog(fle) { tab = Tab.TOOLS; toolsTarget = "fwupdate" }
    }

    // Flipper CLI as a full-screen layer: decoupled from the Scaffold so opening the keyboard only
    // slides this up (imePadding) and the log resizes to fill — no header/nav add-remove to race it.
    if (tab == Tab.TOOLS && toolsTarget == "flippercli")
        FlipperTerminalScreen(onClose = { toolsTarget = null; fle.incoming = null })
    if (tab == Tab.TOOLS && toolsTarget == "esp32cli")
        Esp32CliScreen(onClose = { toolsTarget = null; fle.incoming = null })
    }
}

@Composable
private fun AppHeader(tab: Tab, inTool: Boolean, fle: FlipperBle, onConnect: () -> Unit, onDisconnect: () -> Unit, onGoDevice: () -> Unit, toolsCategory: String, onToolsCategory: (String) -> Unit, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Live capture count for the Archive subtitle, refreshed when that tab opens.
    var captures by remember { mutableStateOf(0) }
    LaunchedEffect(tab, fle.conn) {
        if (tab == Tab.ARCHIVE) {
            captures = ctx.getExternalFilesDir(null)
                ?.listFiles { f -> f.isFile && f.name.endsWith(".sub", true) }?.size ?: 0
        }
    }
    // Honest, live subtitle instead of a hardcoded one.
    val sub = when (tab) {
        Tab.DEVICE -> if (UsbLink.usbConnected) "${fle.deviceName ?: "Fox Firmware"} · USB linked" else when (fle.conn) {
            Conn.CONNECTED -> "${fle.deviceName ?: "Fox Firmware"} · BLE linked"
            Conn.SCANNING -> "Scanning for Flipper…"
            Conn.CONNECTING -> "Linking over BLE…"
            Conn.DISCONNECTED -> "Not connected"
        }
        Tab.ARCHIVE -> if (captures == 1) "1 capture on device" else "$captures captures on device"
        // Inside a tool, the subtitle names the category it came from (matches the pill).
        Tab.TOOLS -> when (toolsCategory) { "general" -> "General Tools"; "esp32" -> "ESP32 Tools"; else -> "RF Analysis" }
        else -> tab.sub
    }
    val bleConnected = fle.conn == Conn.CONNECTED
    val usbConnected = UsbLink.usbConnected
    val inProgress = fle.conn == Conn.SCANNING || fle.conn == Conn.CONNECTING
    val toolsList = tab == Tab.TOOLS && !inTool
    // The BLE pill briefly expands to show CONNECTING / CONNECTED on a state change, then shrinks back to
    // just the icon. Keyed on a coarse phase so SCANNING→CONNECTING doesn't restart the timer.
    var connLabel by remember { mutableStateOf("") }
    val connPhase = when (fle.conn) {
        Conn.CONNECTED -> 2
        Conn.SCANNING, Conn.CONNECTING -> 1
        else -> 0
    }
    LaunchedEffect(connPhase) {
        when (connPhase) {
            1 -> { connLabel = "CONNECTING"; kotlinx.coroutines.delay(3000); connLabel = "" }
            2 -> { connLabel = "CONNECTED"; kotlinx.coroutines.delay(3000); connLabel = "" }
            else -> connLabel = ""
        }
    }
    Column(Modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 8.dp, bottom = if (toolsList) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inTool) {
            Box(
                Modifier.size(34.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .border(1.dp, Fox.line, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Fox.fg, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tab.title, color = Fox.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                // Settings refresh — force-reloads FoxFW settings from the device. Should never be needed.
                if (tab == Tab.SETTINGS && !inTool) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(30.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                            .border(1.dp, Fox.line, androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                            .clickable {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                FoxSettingsReload.trigger++
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Refresh, "Reload settings", tint = Fox.amber, modifier = Modifier.size(16.dp)) }
                }
            }
            Spacer(Modifier.height(5.dp))
            // Subtitle on every tab (Tools list included — "GENERAL TOOLS" etc.) so the title/BLE block
            // is the same height everywhere and lines up identically.
            Text(sub.uppercase(), color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp,
                fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
        }
        // BLE pill (always visible) + USB indicator. BLE: green when linked, else orange; it expands to
        // show CONNECTING/CONNECTED for ~3s then shrinks back to the icon. Tapping the BLE pill while
        // disconnected jumps to the Device tab and starts connecting; while linked it disconnects.
        val bleTint = if (bleConnected) Fox.live else Fox.amber
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = if (bleConnected) Fox.liveDim else Fox.surface,   // black when disconnected, to match the USB icon
                border = androidx.compose.foundation.BorderStroke(1.dp, bleTint.copy(alpha = 0.5f)),
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    if (bleConnected) onDisconnect() else { onGoDevice(); if (!usbConnected) onConnect() }
                },
            ) {
                Row(
                    Modifier.height(38.dp).padding(horizontal = 10.dp).animateContentSize(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.BluetoothConnected, "Bluetooth", tint = bleTint, modifier = Modifier.size(19.dp))
                    if (connLabel.isNotEmpty()) Text(connLabel, color = bleTint, fontFamily = FoxMono,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = if (usbConnected) Fox.liveDim else Fox.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, (if (usbConnected) Fox.live else Fox.amber).copy(alpha = 0.5f)),
            ) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Usb, if (usbConnected) "USB connected" else "USB disconnected",
                        tint = if (usbConnected) Fox.live else Fox.amber, modifier = Modifier.size(19.dp))
                }
            }
        }
    }
    // Category selector, centered under the title on the Tools list.
    if (toolsList) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatChip("GENERAL", toolsCategory == "general") { onToolsCategory("general") }
            Spacer(Modifier.width(6.dp))
            CatChip("RF ANALYSIS", toolsCategory == "rf") { onToolsCategory("rf") }
            Spacer(Modifier.width(6.dp))
            CatChip("ESP32", toolsCategory == "esp32") { onToolsCategory("esp32") }
        }
    }
    }
}

@Composable
private fun CatChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
            .background(if (selected) Fox.amber else Fox.surface)
            .border(1.dp, if (selected) Fox.amber else Fox.line, androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, color = if (selected) Fox.amberInk else Fox.muted, fontFamily = FoxMono, fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
    }
}

@Composable
private fun CompanionBanner(pending: Int, retrying: Boolean, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Fox.amber.copy(alpha = 0.13f))
            .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Warning, null, tint = Fox.amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Unable to Access Companion App. Please return Flipper Zero to its DESKTOP to apply changes!",
                color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (pending > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "$pending change${if (pending == 1) "" else "s"} queued — applies automatically when you're back on the desktop",
                    color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // RETRY re-sends the command, which makes the Flipper reopen the daemon. Manual only.
        Box(
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(Fox.amber.copy(alpha = if (retrying) 0.10f else 0.20f))
                .clickableNoRipple { if (!retrying) onRetry() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (retrying) "…" else "RETRY",
                color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
private fun FoxTabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val idx = Tab.entries.indexOf(selected).coerceAtLeast(0)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(Fox.bg.copy(alpha = 0.96f))
            .drawBehind { drawLine(Fox.line, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.5f) }
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 8.dp, vertical = 9.dp),
    ) {
        val tabW = maxWidth / Tab.entries.size
        val xOff by animateDpAsState(tabW * idx, spring(dampingRatio = 0.72f, stiffness = 430f), label = "ind")
        // machined indicator bar riding over the active tab
        Box(Modifier.width(tabW).offset(x = xOff), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier.width(26.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(Fox.amber)
                    .drawBehind { drawCircle(Fox.amber.copy(alpha = 0.5f), radius = size.height) }
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in Tab.entries) {
                val sel = t == selected
                Column(
                    Modifier.weight(1f).clickableNoRipple {
                        if (t != selected) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onSelect(t)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(t.icon, t.label, tint = if (sel) Fox.amber else Fox.faint, modifier = Modifier.size(22.dp))
                    Text(t.label.uppercase(), color = if (sel) Fox.amber else Fox.faint,
                        fontFamily = FoxMono, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                }
            }
        }
    }
}

/** click without the material ripple, for the tab items. */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

