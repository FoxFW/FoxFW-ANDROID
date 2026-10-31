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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FoxPhase { LOADING, UNSUPPORTED, DISCONNECTED, LOADED, LOAD_FAILED }

@Composable
fun FoxSettingsScreen(onClose: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cfg = remember { FoxSettings.Cfg() }
    var rev by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(FoxPhase.LOADING) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var originalName by remember { mutableStateOf("") }
    fun edit(b: () -> Unit) { b(); rev++ }

    fun load() {
        if (!FlipperRf.connected) { phase = FoxPhase.DISCONNECTED; return }
        phase = FoxPhase.LOADING; status = "checking firmware…"; busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (!FoxSettings.supported()) return@withContext null
                FoxSettings.read()
            }
            busy = false
            when {
                ok == null && !FlipperRf.connected -> { phase = FoxPhase.DISCONNECTED }
                ok == null -> {
                    // supported but read failed, OR unsupported — distinguish via one more stat
                    val sup = withContext(Dispatchers.IO) { FoxSettings.supported() }
                    if (!sup) { phase = FoxPhase.UNSUPPORTED; status = "unsupported firmware" }
                    // read failed (transient): do NOT reset cfg to defaults or enable Apply — that could
                    // push blank defaults over the Flipper's real settings. Stay read-only; user Reloads.
                    else { phase = FoxPhase.LOAD_FAILED; status = "read failed — put the Flipper on its home screen, then Reload." }
                }
                else -> { copyInto(cfg, ok); originalName = cfg.deviceName; rev++; phase = FoxPhase.LOADED; status = "loaded" }
            }
        }
    }

    fun apply() {
        if (phase != FoxPhase.LOADED) return
        busy = true; status = "applying…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) { FoxSettings.write(cfg) }
            busy = false
            status = if (ok) {
                if (cfg.deviceName != originalName) { originalName = cfg.deviceName; "applied · name change needs a reboot" }
                else "applied"
            } else "apply timed out — is the Flipper on its home screen?"
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { load() }
    val r = rev   // read once so field edits recompose the whole form

    Column(Modifier.fillMaxWidth().background(Fox.page).verticalScroll(rememberScrollState()).padding(16.dp)) {
        // header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Fox.surface)
                .border(1.dp, Fox.line, RoundedCornerShape(10.dp)).clickable { onClose() },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, "back", tint = Fox.fg, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("FULL SETTINGS · OVER BLE/USB")
                Text("FoxFW Settings", color = Fox.fg, fontFamily = FoxMono, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))

        // status + actions
        FoxCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (busy) "• working…" else "• ${status.ifEmpty { "ready" }}",
                    color = if (phase == FoxPhase.UNSUPPORTED) Fox.warn else Fox.live, fontFamily = FoxMono, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                ActionChip("Reload", enabled = !busy) { load() }
                Spacer(Modifier.width(8.dp))
                ActionChip("Apply", accent = true, enabled = !busy && phase == FoxPhase.LOADED) { apply() }
            }
        }
        Spacer(Modifier.height(12.dp))

        when (phase) {
            FoxPhase.DISCONNECTED -> Note("Connect the Flipper (Remote Screen) to edit device settings.")
            FoxPhase.LOAD_FAILED -> Note("Couldn't read settings — put the Flipper on its home screen and tap Reload. Your device settings were left untouched.")
            FoxPhase.UNSUPPORTED -> FoxCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("unsupported firmware", color = Fox.warn, fontFamily = FoxMono, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("FoxFW Settings need FoxFW 2.1+ (the \"Import Companion Settings\" app isn't installed on this Flipper). Every other feature still works on any firmware.",
                        color = Fox.muted, fontSize = 13.sp)
                }
            }
            else -> {
                val loaded = phase == FoxPhase.LOADED
                Section("Display & Sound") {
                    RowSlider("Contrast", cfg.contrast, -8, 8, loaded) { edit { cfg.contrast = it } }
                    RowSlider("Backlight %", cfg.backlightPct, 0, 100, loaded) { edit { cfg.backlightPct = it } }
                    RowSlider("LED %", cfg.ledPct, 0, 100, loaded) { edit { cfg.ledPct = it } }
                    RowSlider("Volume %", cfg.volumePct, 0, 100, loaded) { edit { cfg.volumePct = it } }
                    RowDropdown("Backlight off", FoxSettings.BACKLIGHT_OFF, FoxSettings.delayToIdx(cfg.delayMs), loaded) { edit { cfg.delayMs = FoxSettings.idxToDelay(it) } }
                    RowSwitch("Vibro", cfg.vibro == 1, loaded) { edit { cfg.vibro = if (it) 1 else 0 } }
                    RowSwitch("Invert LCD", cfg.inversion == 1, loaded) { edit { cfg.inversion = if (it) 1 else 0 } }
                }
                Section("Menu Theme") {
                    RowDropdown("Style", FoxSettings.THEME, cfg.theme, loaded) { edit { cfg.theme = it } }
                }
                Section("Locale") {
                    RowDropdown("Time", FoxSettings.TIME_FMT, cfg.timeFmt, loaded) { edit { cfg.timeFmt = it } }
                    RowDropdown("Date", FoxSettings.DATE_FMT, cfg.dateFmt, loaded) { edit { cfg.dateFmt = it } }
                    RowDropdown("Units", FoxSettings.UNITS, cfg.units, loaded) { edit { cfg.units = it } }
                    RowSwitch("Left-handed", cfg.hand == 1, loaded) { edit { cfg.hand = if (it) 1 else 0 } }
                }
                Section("Night Shift") {
                    RowDropdown("Level", FoxSettings.NIGHT_LEVEL, cfg.nsIdx, loaded) { edit { cfg.nsIdx = it } }
                    RowSlider("Start (min)", cfg.nsStart, 0, 1439, loaded) { edit { cfg.nsStart = it } }
                    RowSlider("End (min)", cfg.nsEnd, 0, 1439, loaded) { edit { cfg.nsEnd = it } }
                }
                Section("RGB Backlight") {
                    RowSwitch("Installed", cfg.rgbInstalled == 1, loaded) { edit { cfg.rgbInstalled = if (it) 1 else 0 } }
                    if (cfg.rgbInstalled == 1) {
                        RowSwitch("White mode", cfg.rgbWhite == 1, loaded) { edit { cfg.rgbWhite = if (it) 1 else 0 } }
                        val canColor = loaded && cfg.rgbWhite == 0
                        val allSame = cfg.rgbLed1 == cfg.rgbLed2 && cfg.rgbLed2 == cfg.rgbLed3
                        RowColor("All LEDs", if (allSame) cfg.rgbLed1 else -1, canColor) { idx ->
                            edit { cfg.rgbLed1 = idx; cfg.rgbLed2 = idx; cfg.rgbLed3 = idx }
                        }
                        RowColor("LED 1", cfg.rgbLed1, canColor) { idx -> edit { cfg.rgbLed1 = idx } }
                        RowColor("LED 2", cfg.rgbLed2, canColor) { idx -> edit { cfg.rgbLed2 = idx } }
                        RowColor("LED 3", cfg.rgbLed3, canColor) { idx -> edit { cfg.rgbLed3 = idx } }
                        RowDropdown("Effect", FoxSettings.RGB_EFFECT, cfg.rgbEffect.coerceIn(0, 2), canColor) { edit { cfg.rgbEffect = it } }
                        SubNote("Per-LED colors for a fitted RGB backlight board. White mode overrides them with a plain white backlight.")
                    } else {
                        SubNote("Turn on Installed if you've fitted an RGB backlight board, then pick per-LED colors here.")
                    }
                }
                Section("Lock Screen / Desktop") {
                    RowDropdown("Battery view", FoxSettings.BATTERY_VIEW, cfg.batteryView, loaded) { edit { cfg.batteryView = it } }
                    RowSwitch("Show clock", cfg.showClock == 1, loaded) { edit { cfg.showClock = if (it) 1 else 0 } }
                    RowSwitch("Midnight = 0", cfg.midnightFmt == 1, loaded) { edit { cfg.midnightFmt = if (it) 1 else 0 } }
                    RowSwitch("Hide WiFi icon", cfg.wifiHidden == 1, loaded) { edit { cfg.wifiHidden = if (it) 1 else 0 } }
                    RowSwitch("Statusbar icons", cfg.statusbarIcons == 1, loaded) { edit { cfg.statusbarIcons = if (it) 1 else 0 } }
                    RowDropdown("Shell color", FoxSettings.SHELL_COLOR, cfg.shellColorIdx, loaded) { edit { cfg.shellColorIdx = it } }
                }
                Section("Fox Alarm") {
                    RowSwitch("Keep backlight", cfg.alarmKeepBacklight == 1, loaded) { edit { cfg.alarmKeepBacklight = if (it) 1 else 0 } }
                    RowSwitch("Beep", cfg.alarmBeep == 1, loaded) { edit { cfg.alarmBeep = if (it) 1 else 0 } }
                    RowSwitch("Vibrate", cfg.alarmVibrate == 1, loaded) { edit { cfg.alarmVibrate = if (it) 1 else 0 } }
                }
                Section("Input") {
                    RowDropdown("Vibro level", FoxSettings.VIBRO_LEVEL, cfg.vibroLevelIdx, loaded) { edit { cfg.vibroLevelIdx = it } }
                    RowDropdown("Vibro trigger", FoxSettings.VIBRO_TRIGGER, cfg.vibroTriggerIdx, loaded) { edit { cfg.vibroTriggerIdx = it } }
                }
                Section("Power") {
                    RowDropdown("Auto power-off", FoxSettings.AUTO_POWEROFF, cfg.autoPoweroffIdx, loaded) { edit { cfg.autoPoweroffIdx = it } }
                    RowDropdown("Charge limit", FoxSettings.CHARGE_LIMIT, cfg.limitChargeIdx, loaded) { edit { cfg.limitChargeIdx = it } }
                }
                Section("System") {
                    RowSwitch("Bluetooth", cfg.btEnabled == 1, loaded) { edit { cfg.btEnabled = if (it) 1 else 0 } }
                    RowDropdown("Sleep", FoxSettings.SLEEP, cfg.sleepMethod, loaded) { edit { cfg.sleepMethod = it } }
                    RowSwitch("Detailed filenames", cfg.fileNaming == 1, loaded) { edit { cfg.fileNaming = if (it) 1 else 0 } }
                    RowText("Device name", cfg.deviceName, loaded) { edit { cfg.deviceName = it.filter { c -> c.isLetterOrDigit() } } }
                    SubNote("Letters/numbers only. A name change takes effect after a reboot.")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun copyInto(dst: FoxSettings.Cfg, src: FoxSettings.Cfg) {
    dst.contrast = src.contrast; dst.backlightPct = src.backlightPct; dst.ledPct = src.ledPct; dst.volumePct = src.volumePct
    dst.delayMs = src.delayMs; dst.vibro = src.vibro; dst.inversion = src.inversion
    dst.timeFmt = src.timeFmt; dst.dateFmt = src.dateFmt; dst.units = src.units; dst.hand = src.hand; dst.theme = src.theme
    dst.nsIdx = src.nsIdx; dst.nsStart = src.nsStart; dst.nsEnd = src.nsEnd
    dst.rgbInstalled = src.rgbInstalled; dst.rgbWhite = src.rgbWhite; dst.rgbLed1 = src.rgbLed1; dst.rgbLed2 = src.rgbLed2; dst.rgbLed3 = src.rgbLed3
    dst.rgbEffect = src.rgbEffect; dst.rgbSpeed = src.rgbSpeed; dst.rgbStep = src.rgbStep; dst.rgbSat = src.rgbSat; dst.rgbWave = src.rgbWave
    dst.sleepMethod = src.sleepMethod; dst.fileNaming = src.fileNaming
    dst.vibroLevelIdx = src.vibroLevelIdx; dst.vibroTriggerIdx = src.vibroTriggerIdx; dst.btEnabled = src.btEnabled
    dst.autoPoweroffIdx = src.autoPoweroffIdx; dst.limitChargeIdx = src.limitChargeIdx
    dst.batteryView = src.batteryView; dst.showClock = src.showClock; dst.midnightFmt = src.midnightFmt; dst.wifiHidden = src.wifiHidden
    dst.statusbarIcons = src.statusbarIcons; dst.shellColorIdx = src.shellColorIdx
    dst.alarmKeepBacklight = src.alarmKeepBacklight; dst.alarmBeep = src.alarmBeep; dst.alarmVibrate = src.alarmVibrate
    dst.deviceName = src.deviceName
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    FoxCard(Modifier.fillMaxWidth()) {
        Column {
            Eyebrow(title.uppercase(), color = Fox.amber)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun RowSlider(label: String, value: Int, min: Int, max: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Fox.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value.toString(), color = Fox.live, fontFamily = FoxMono, fontSize = 13.sp)
        }
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = min.toFloat()..max.toFloat(), enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = Fox.amber, activeTrackColor = Fox.amber, inactiveTrackColor = Fox.line2),
        )
    }
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Fox.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Fox.amber,
                uncheckedThumbColor = Fox.muted, uncheckedTrackColor = Fox.surface2, uncheckedBorderColor = Fox.line2))
    }
}

@Composable
private fun Swatch(index: Int, size: Int = 16) {
    val palette = FoxSettings.RGB_PALETTE
    val mod = Modifier.size(size.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Fox.line2, RoundedCornerShape(4.dp))
    if (index in palette.indices) {
        Box(mod.background(Color(0xFF000000L or (palette[index].second.toLong() and 0xFFFFFFL))))
    } else {
        // "Mixed" — LEDs are set to different colors.
        Box(mod.background(Fox.surface2))
    }
}

@Composable
private fun RowColor(label: String, index: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val palette = FoxSettings.RGB_PALETTE
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (enabled) Fox.fg else Fox.faint, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Fox.surface2).border(1.dp, Fox.line2, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { open = true }.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Swatch(index)
                Spacer(Modifier.width(8.dp))
                Text(if (index in palette.indices) palette[index].first else "Mixed",
                    color = if (enabled) Fox.fg else Fox.faint, fontFamily = FoxMono, fontSize = 12.sp)
                Icon(Icons.Filled.ArrowDropDown, null, tint = Fox.muted, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                palette.forEachIndexed { i, (name, _) ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Swatch(i); Spacer(Modifier.width(10.dp))
                                Text(name, fontFamily = FoxMono, fontSize = 13.sp, color = if (i == index) Fox.amber else Fox.fg)
                            }
                        },
                        onClick = { open = false; onSelect(i) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowDropdown(label: String, options: List<String>, index: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Fox.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Fox.surface2).border(1.dp, Fox.line2, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { open = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(options.getOrElse(index) { "?" }, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
                Icon(Icons.Filled.ArrowDropDown, null, tint = Fox.muted, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, opt ->
                    DropdownMenuItem(text = { Text(opt, fontFamily = FoxMono, fontSize = 13.sp) }, onClick = { open = false; onSelect(i) })
                }
            }
        }
    }
}

@Composable
private fun RowText(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Fox.fg, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            placeholder = { Text("(default)", color = Fox.faint) },
            textStyle = TextStyle(fontFamily = FoxMono, fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Fox.fg, unfocusedTextColor = Fox.fg,
                focusedBorderColor = Fox.amber, unfocusedBorderColor = Fox.line2,
                cursorColor = Fox.amber,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SubNote(t: String) {
    Spacer(Modifier.height(4.dp))
    Text(t, color = Fox.faint, fontSize = 11.sp)
}

@Composable
private fun Note(t: String) {
    FoxCard(Modifier.fillMaxWidth()) { Text(t, color = Fox.muted, fontSize = 13.sp) }
}

@Composable
private fun ActionChip(label: String, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (accent && enabled) Fox.amber else Fox.surface2)
            .border(1.dp, if (accent) Fox.amber else Fox.line2, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (accent && enabled) Fox.amberInk else if (enabled) Fox.fg else Fox.faint,
            fontFamily = FoxMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
