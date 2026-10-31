package com.foxfw.companion

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
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
    DEVICE("Device", "Device", "Fox One · BLE linked", Icons.Outlined.Smartphone),
    DECODE("Decode", "Decode", "433.92 MHz · live RX", Icons.Outlined.GraphicEq),
    ARCHIVE("Archive", "Archive", "on-device SD captures", Icons.Outlined.Inventory2),
    TOOLS("Tools", "Tools", "RF analysis & cracking", Icons.Outlined.GridView),
    SETTINGS("Settings", "Settings", "Fox One", Icons.Outlined.Settings),
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
                    if (bleOk) fle.connect()
                }
                val onConnect: () -> Unit = {
                    if (fle.conn == Conn.CONNECTED || fle.conn == Conn.SCANNING || fle.conn == Conn.CONNECTING) fle.disconnect()
                    else launcher.launch(perms)
                }
                var splash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(1900); splash = false }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var showWhatsNew by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { if (WhatsNew.shouldShow(ctx)) showWhatsNew = true }
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    FoxApp(fle, onConnect)
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
fun FoxApp(fle: FlipperBle, onConnect: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.DEVICE) }
    var toolsTarget by remember { mutableStateOf<String?>(null) }

    // When the Flipper streams an offload job, jump straight to the Hitag2 screen.
    LaunchedEffect(fle.incoming) {
        if (fle.incoming != null) { tab = Tab.TOOLS; toolsTarget = "hitag2" }
    }
    // When the Flipper offloads a PSA TEA brute, jump to the PSA screen.
    LaunchedEffect(PsaOffload.requestNonce) {
        if (PsaOffload.requestNonce > 0) { tab = Tab.TOOLS; toolsTarget = "psa" }
    }

    Scaffold(
        containerColor = Fox.bg,
        bottomBar = { FoxTabBar(tab) { tab = it } },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
        ) {
            AppHeader(tab, fle, onConnect)
            when (tab) {
                Tab.DEVICE -> DeviceScreen(fle, onConnect, onOpenDecode = { tab = Tab.DECODE })
                Tab.DECODE -> DecodeScreen()
                Tab.ARCHIVE -> ArchiveScreen()
                Tab.TOOLS -> ToolsScreen(
                    fle = fle,
                    openTarget = toolsTarget,
                    onOpenTarget = { toolsTarget = it },
                    onClose = { toolsTarget = null },
                )
                Tab.SETTINGS -> SettingsScreen(fle)
            }
        }
    }
}

@Composable
private fun AppHeader(tab: Tab, fle: FlipperBle, onConnect: () -> Unit) {
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
        Tab.DEVICE -> if (UsbLink.usbConnected) "${fle.deviceName ?: "Fox One"} · USB linked" else when (fle.conn) {
            Conn.CONNECTED -> "${fle.deviceName ?: "Fox One"} · BLE linked"
            Conn.SCANNING -> "Scanning for Fox One…"
            Conn.CONNECTING -> "Linking over BLE…"
            Conn.DISCONNECTED -> "Not connected · tap scan"
        }
        Tab.ARCHIVE -> if (captures == 1) "1 capture on device" else "$captures captures on device"
        else -> tab.sub
    }
    val scanActive = fle.conn == Conn.CONNECTED || fle.conn == Conn.SCANNING || fle.conn == Conn.CONNECTING
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tab.title, color = Fox.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(sub.uppercase(), color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp,
                fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
        }
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = if (scanActive) Fox.amberDim else Fox.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (scanActive) Fox.amber.copy(alpha = 0.5f) else Fox.line),
            modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onConnect()
            },
        ) {
            androidx.compose.foundation.layout.Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (scanActive) Icons.Filled.BluetoothConnected else Icons.Filled.Search,
                    if (scanActive) "Disconnect" else "Scan for device",
                    tint = if (scanActive) Fox.amber else Fox.fg, modifier = Modifier.size(19.dp),
                )
            }
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

