package com.foxfw.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.GraticuleScreen

/**
 * Phone-only LIVE 2.4 / 5 GHz Band Analyzer. A phone can't read raw RF spectrum (Android doesn't
 * expose spectral scan to apps), so this shows the band ACTIVITY its own radios see: WiFi APs folded
 * onto their channel (live per-channel RSSI bars with peak-hold + top talkers) and, on 2.4 GHz,
 * streaming BLE advertiser activity. No Flipper involved.
 *
 * "Live": BLE updates on every advert; WiFi is re-read fast from the OS cache (app-initiated scans
 * are throttled by Android, so we poll the system's latest results and nudge fresh scans when we can).
 */
@Composable
fun BandAnalyzerScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val wifiScanner = remember { WifiBandScanner(ctx) }
    val bleScanner = remember { BleBandScanner(ctx) }

    val perms = remember {
        val l = ArrayList<String>()
        l += Manifest.permission.ACCESS_FINE_LOCATION // the reliable gate for WiFi scan RESULTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) l += Manifest.permission.NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) l += Manifest.permission.BLUETOOTH_SCAN
        l.toTypedArray()
    }
    fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    // Require FINE_LOCATION: on this hardware NEARBY_WIFI_DEVICES alone returns an empty scan list,
    // so location is the dependable gate for WiFi results across OEMs.
    fun wifiPermOk() = has(Manifest.permission.ACCESS_FINE_LOCATION)
    fun locationOn(): Boolean = try {
        (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
    } catch (e: Exception) { true }

    var granted by remember { mutableStateOf(wifiPermOk()) }
    var bandIdx by remember { mutableStateOf(0) } // 0 = 2.4 GHz, 1 = 5 GHz
    var aps by remember { mutableStateOf<List<BandScan.Ap>>(emptyList()) }
    var ble by remember { mutableStateOf<BleBandScanner.BleActivity?>(null) }
    var lastUpdate by remember { mutableStateOf(0L) }
    var tickNow by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf("") }
    val peaks = remember { mutableStateMapOf<Int, Float>() } // channel -> held peak fraction

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = wifiPermOk()
        if (!granted) status = "Permission denied — the band map needs location (for WiFi) access."
    }

    // Start BLE scanning + WiFi result stream once permitted; tear down on leave.
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val stopWifi = wifiScanner.start { aps = it }
        val stopBle = bleScanner.start { ble = it }
        onDispose { stopWifi(); stopBle() }
    }
    // LIVE loop: re-read the OS scan cache fast; nudge a fresh scan every ~16 s (throttle-aware).
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        var t = 0
        while (granted) {
            val fresh = wifiScanner.currentAps()
            if (fresh.isNotEmpty()) { aps = fresh; lastUpdate = System.currentTimeMillis() }
            else aps = fresh
            if (t % 11 == 0) wifiScanner.requestScan()
            t++
            tickNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_500)
        }
    }

    val band = if (bandIdx == 0) BandScan.Band.GHZ24 else BandScan.Band.GHZ5
    val loads = remember(aps, bandIdx) { BandScan.aggregate(aps, band) }
    val busiest = loads.filter { it.apCount > 0 }.maxByOrNull { it.maxRssi }
    val topTalkers = remember(aps, bandIdx) {
        aps.filter { BandScan.bandOf(it.freqMhz) == band }.sortedByDescending { it.rssi }.take(6)
    }

    // peak-hold + decay: raise instantly, sag slowly so the trace stays "live".
    LaunchedEffect(bandIdx) { peaks.clear() }
    LaunchedEffect(loads) {
        val present = HashSet<Int>()
        for (l in loads) if (l.apCount > 0) {
            present += l.channel
            val f = BandScan.rssiToFraction(l.maxRssi)
            peaks[l.channel] = maxOf((peaks[l.channel] ?: 0f) - 0.02f, f)
        }
        // let channels that went quiet sag toward zero
        peaks.keys.toList().forEach { if (it !in present) peaks[it] = (peaks[it] ?: 0f) - 0.04f }
    }

    val liveAlpha by rememberInfiniteTransition(label = "live").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "liveA",
    )
    val agoSec = if (lastUpdate == 0L) -1 else ((tickNow - lastUpdate) / 1000L).toInt()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // header with a live pulse
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Fox.line, RoundedCornerShape(10.dp)).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.ArrowBack, null, tint = Fox.fg, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Band Analyzer", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow("phone WiFi + BLE · live")
            }
            Spacer(Modifier.weight(1f))
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).alpha(liveAlpha).clip(RoundedCornerShape(99.dp)).background(Fox.live))
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE", color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                }
            }
        }

        if (!granted) {
            FoxCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Scan the airwaves", color = Fox.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "This maps live 2.4 and 5 GHz activity from the phone's own radios — every WiFi network " +
                            "on its channel, plus nearby BLE devices. Android requires location + Bluetooth-scan " +
                            "access to read WiFi/BLE scans (it's never used for your location).",
                        color = Fox.muted, fontSize = 12.5.sp, lineHeight = 17.sp,
                    )
                    ActionBtn("Grant access", accent = true) { launcher.launch(perms) }
                }
            }
            return@Column
        }

        if (!locationOn()) {
            FoxCard(border = Fox.warn.copy(alpha = 0.5f)) {
                Text(
                    "Turn Location on in quick settings — Android won't return WiFi scan results while it's off " +
                        "(this is a system rule, not us tracking you).",
                    color = Fox.warn, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }

        // band toggle
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BandTab("2.4 GHz", bandIdx == 0, Modifier.weight(1f)) { bandIdx = 0 }
            BandTab("5 GHz", bandIdx == 1, Modifier.weight(1f)) { bandIdx = 1 }
        }

        // live per-channel bars
        FoxCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(if (bandIdx == 0) "WIFI CHANNELS 1–14" else "WIFI CHANNELS 36–165")
                        Text(
                            busiest?.let { "ch ${it.channel} · ${it.maxRssi} dBm" } ?: "no networks on this band",
                            color = Fox.phos, fontFamily = FoxMono, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(busiest?.topSsid ?: "", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
                }
                GraticuleScreen(Modifier.fillMaxWidth().height(150.dp)) {
                    ChannelBars(loads, peaks, Modifier.fillMaxSize().padding(6.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val marks = if (bandIdx == 0) listOf(1, 6, 11, 14) else listOf(36, 64, 100, 140, 165)
                    marks.forEach { Text("$it", color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp) }
                }
            }
        }

        // BLE activity (2.4 GHz only — adverts live in 2.4 GHz)
        if (bandIdx == 0) {
            FoxCard(border = Fox.violetDim) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("BLE ACTIVITY · 2.4 GHz")
                        Text(
                            ble?.let { "${it.devices} device${if (it.devices == 1) "" else "s"} · peak ${it.maxRssi} dBm" }
                                ?: "listening…",
                            color = Fox.violet, fontFamily = FoxMono, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                        val names = ble?.names?.joinToString(" · ")?.takeIf { it.isNotBlank() }
                        if (names != null) Text(names, color = Fox.muted, fontFamily = FoxMono, fontSize = 10.5.sp)
                    }
                    com.foxfw.companion.ui.Pill("adv 37·38·39", Fox.violet, Fox.violetDim)
                }
            }
        }

        // top talkers
        FoxCard {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Eyebrow("TOP TALKERS")
                if (topTalkers.isEmpty()) {
                    Text(
                        if (!wifiScanner.wifiEnabled()) "WiFi is off — turn it on to see networks."
                        else "No networks on this band yet · ${wifiScanner.diag}",
                        color = Fox.muted, fontSize = 12.sp,
                    )
                } else {
                    topTalkers.forEach { ap ->
                        val ch = BandScan.freqToChannel(ap.freqMhz)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(ap.ssid.ifBlank { "(hidden)" }, color = Fox.fg, fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1)
                                Text("ch ${ch ?: "?"} · ${ap.freqMhz} MHz", color = Fox.faint,
                                    fontFamily = FoxMono, fontSize = 10.sp)
                            }
                            Text("${ap.rssi} dBm", color = rssiColor(ap.rssi), fontFamily = FoxMono,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionBtn("Rescan now", accent = false, modifier = Modifier.weight(1f)) {
                status = if (wifiScanner.requestScan()) "Rescanning…" else "Scan throttled by Android — showing latest cache."
                aps = wifiScanner.currentAps()
                if (aps.isNotEmpty()) lastUpdate = System.currentTimeMillis()
            }
        }
        Text(
            status.ifBlank {
                val ago = if (agoSec < 0) "waiting for first scan" else "updated ${agoSec}s ago"
                "Live · $ago · WiFi + BLE activity (not a raw RF sweep)."
            },
            color = Fox.faint, fontSize = 11.sp, lineHeight = 15.sp,
        )
    }
}

/** Live per-channel bars + peak-hold ticks, drawn inside the recessed graticule. */
@Composable
private fun ChannelBars(loads: List<BandScan.ChannelLoad>, peaks: Map<Int, Float>, modifier: Modifier) {
    Canvas(modifier) {
        if (loads.isEmpty()) return@Canvas
        val n = loads.size
        val slot = size.width / n
        val barW = slot * 0.6f
        loads.forEachIndexed { i, load ->
            val frac = if (load.apCount > 0) BandScan.rssiToFraction(load.maxRssi) else 0f
            val h = (size.height - 4f) * frac
            val x = i * slot + (slot - barW) / 2f
            val y = size.height - h
            val occupied = load.apCount > 0
            val top = if (occupied) Fox.amber else Fox.line2
            val bot = if (occupied) Fox.amber.copy(alpha = 0.25f) else Fox.line
            if (h > 1f) {
                drawRect(
                    brush = Brush.verticalGradient(listOf(top, bot), startY = y, endY = size.height),
                    topLeft = Offset(x, y), size = Size(barW, h),
                )
            } else {
                drawRect(Fox.line, topLeft = Offset(x, size.height - 2f), size = Size(barW, 2f))
            }
            // peak-hold tick
            val pk = peaks[load.channel] ?: 0f
            if (pk > 0.01f) {
                val py = size.height - (size.height - 4f) * pk
                drawRect(Fox.phos, topLeft = Offset(x, py), size = Size(barW, 2f))
            }
        }
    }
}

@Composable
private fun BandTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (selected) Fox.amber else Fox.line, RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Fox.amber else Fox.muted, fontFamily = FoxMono,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActionBtn(text: String, accent: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(11.dp))
            .background(if (accent) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (accent) Fox.amber else Fox.line, RoundedCornerShape(11.dp))
            .clickable { onClick() }.padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (accent) Fox.amber else Fox.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun rssiColor(dbm: Int) = when {
    dbm >= -55 -> Fox.live
    dbm >= -70 -> Fox.amber
    else -> Fox.muted
}
