package com.foxfw.companion

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.launch

/** Named preset -> a swatch colour for the chips (approximate, for the UI only). */
private val SWATCH = mapOf(
    "warm" to Color(0xFFFFB457), "cool" to Color(0xFFBFD4FF), "white" to Color(0xFFF3EFE6),
    "red" to Color(0xFFFF5C5C), "orange" to Color(0xFFFF9A3D), "yellow" to Color(0xFFFFE04F),
    "green" to Color(0xFF4FE08A), "cyan" to Color(0xFF49E6E6), "blue" to Color(0xFF5AA9FF),
    "purple" to Color(0xFFB06BFF), "pink" to Color(0xFFFF6FB0),
)

private class BulbUi(val name: String, val mac: String) {
    var on by mutableStateOf(false)
    var bri by mutableStateOf(50)
    var color by mutableStateOf<String?>(null)
    var rssi by mutableStateOf<Int?>(null)
    var busy by mutableStateOf(false)
    var known by mutableStateOf(false) // real /state loaded
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HueScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { HueClient(ctx) }

    val bulbs = remember { mutableStateListOf<BulbUi>() }
    var baseField by remember { mutableStateOf(client.base) }
    var tokenField by remember { mutableStateOf(client.token) }
    var settingsOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Proxy: ${client.base}") }
    var loading by remember { mutableStateOf(false) }
    var scanOut by remember { mutableStateOf<String?>(null) }

    fun refreshState(b: BulbUi) {
        if (b.busy) return
        b.busy = true
        scope.launch {
            client.state(b.name).onSuccess { st ->
                if (st != null) {
                    st.on?.let { b.on = it }; st.bri?.let { b.bri = it }
                    b.color = st.color; b.rssi = st.rssi; b.known = true
                }
            }
            b.busy = false
        }
    }

    fun loadHealth() {
        loading = true; status = "Reading ${HueApi.normalizeBase(client.base)}…"
        scope.launch {
            client.health()
                .onSuccess { list ->
                    bulbs.clear()
                    list.forEach { bulbs.add(BulbUi(it.name, it.mac).apply { on = it.on }) }
                    status = if (list.isEmpty()) "Proxy reachable, but it lists no bulbs." else "${list.size} bulb(s) · reading live state…"
                    loading = false
                    bulbs.forEach { refreshState(it) } // fill real bri/color in the background
                }
                .onFailure { status = "Can't reach the proxy: ${it.message}"; loading = false }
        }
    }

    fun apply(b: BulbUi, on: Boolean? = null, bri: Int? = null, color: String? = null) {
        b.busy = true
        scope.launch {
            client.set(b.name, on, bri, color)
                .onSuccess {
                    on?.let { b.on = it }; bri?.let { b.bri = it }; color?.let { b.color = it }
                    status = "${b.name}: set OK"
                }
                .onFailure { status = "${b.name}: ${it.message}" }
            b.busy = false
        }
    }

    LaunchedEffect(Unit) { loadHealth() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Fox.line, RoundedCornerShape(10.dp)).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.ArrowBack, null, tint = Fox.fg, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Hue Lights", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow("CYD BLE proxy · over WiFi")
            }
            if (loading) Pill("…", Fox.amber, Fox.amberDim) else Pill("${bulbs.size}", Fox.live, Fox.liveDim)
        }

        FoxCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(HueApi.normalizeBase(client.base), color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(if (settingsOpen) "done" else "edit", color = Fox.amber, fontSize = 12.sp,
                        modifier = Modifier.clickable { settingsOpen = !settingsOpen })
                }
                if (settingsOpen) {
                    OutlinedTextField(value = baseField, onValueChange = { baseField = it }, singleLine = true,
                        label = { Text("proxy host / IP", fontSize = 10.sp) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = tokenField, onValueChange = { tokenField = it }, singleLine = true,
                        label = { Text("token (optional)", fontSize = 10.sp) }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HBtn("Save & reload", Modifier.weight(1f), accent = true) { client.save(baseField, tokenField); settingsOpen = false; loadHealth() }
                        HBtn("Scan bulbs", Modifier.weight(1f)) {
                            status = "BLE scanning via proxy…"
                            scope.launch { client.scan().onSuccess { scanOut = it; status = "Scan done." }.onFailure { status = "Scan failed: ${it.message}" } }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HBtn(if (loading) "Reading…" else "↻ Refresh", Modifier.weight(1f)) { if (!loading) loadHealth() }
                }
                Text(status, color = Fox.faint, fontSize = 11.sp, lineHeight = 15.sp)
                scanOut?.let { Text(it, color = Fox.muted, fontFamily = FoxMono, fontSize = 9.5.sp, lineHeight = 13.sp) }
            }
        }

        bulbs.forEach { b -> BulbCard(b, onToggle = { apply(b, on = it) }, onBri = { apply(b, bri = it) },
            onColor = { apply(b, color = it) }, onRead = { refreshState(b) }) }

        if (bulbs.isEmpty() && !loading) {
            Text("No bulbs. Check the proxy host above and tap Refresh, or Scan bulbs to find their MACs.",
                color = Fox.faint, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulbCard(b: BulbUi, onToggle: (Boolean) -> Unit, onBri: (Int) -> Unit, onColor: (String) -> Unit, onRead: () -> Unit) {
    FoxCard(border = if (b.on) Fox.amber.copy(alpha = 0.5f) else Fox.line) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.name.replaceFirstChar { it.uppercase() }, color = Fox.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.mac, color = Fox.faint, fontFamily = FoxMono, fontSize = 9.5.sp)
                        b.rssi?.let { Spacer(Modifier.width(8.dp)); Text("$it dBm", color = Fox.muted, fontFamily = FoxMono, fontSize = 9.5.sp) }
                    }
                }
                if (b.busy) Pill("…", Fox.amber, Fox.amberDim)
                Spacer(Modifier.width(8.dp))
                Switch(checked = b.on, onCheckedChange = { onToggle(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Fox.amberInk, checkedTrackColor = Fox.amber, uncheckedTrackColor = Fox.surface2, uncheckedBorderColor = Fox.line))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bright", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.width(52.dp))
                Slider(
                    value = b.bri.toFloat(), onValueChange = { b.bri = it.toInt() }, valueRange = 0f..100f,
                    onValueChangeFinished = { onBri(b.bri) }, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Fox.amber, activeTrackColor = Fox.amber, inactiveTrackColor = Fox.surface2),
                )
                Text("${b.bri}%", color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp, modifier = Modifier.width(40.dp))
            }
            // White-only bulbs ("Hue white lamp") have no colour characteristic — /state reports an
            // empty colour. Once we've read real state and it's empty, hide the colour chips.
            val whiteOnly = b.known && b.color.isNullOrEmpty()
            if (!whiteOnly) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HueApi.COLORS.forEach { c ->
                    val sel = b.color == c
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(Fox.surface2)
                            .border(if (sel) 2.dp else 1.dp, if (sel) Fox.amber else Fox.line, RoundedCornerShape(8.dp))
                            .clickable { onColor(c) }.padding(horizontal = 9.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(SWATCH[c] ?: Fox.muted))
                            Spacer(Modifier.width(5.dp))
                            Text(c, color = if (sel) Fox.amber else Fox.muted, fontFamily = FoxMono, fontSize = 10.sp)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (b.known) "live state" else "tap read for live state", color = Fox.faint, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text("↻ read", color = Fox.info, fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.clickable { onRead() })
            }
        }
    }
}

@Composable
private fun HBtn(text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(11.dp))
            .background(if (accent) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (accent) Fox.amber else Fox.line, RoundedCornerShape(11.dp))
            .clickable { onClick() }.padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (accent) Fox.amber else Fox.fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}
