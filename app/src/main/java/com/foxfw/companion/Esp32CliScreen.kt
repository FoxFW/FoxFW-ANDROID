package com.foxfw.companion

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.launch

/**
 * ESP32 CLI terminal — the Flipper-CLI layout (console + pinned Send/Save input, full-screen while the
 * keyboard is up) but talking to the ESP32. It drives the **shared** [Esp32Hub]: one connection and one
 * console across the whole ESP32 area, so whatever you type here appears in — and shares the live link
 * with — the ESP32 page and the domain terminals, and vice-versa. Transport is auto-determined by the
 * hub on first entry (direct USB-OTG board else Flipper relay); Fox boards get the Fox dialect. The
 * input box persists per-page via [EspSession]; Save appends to the daily CLI log.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Esp32CliScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current

    val session = remember { EspSession.get("cli") }
    LaunchedEffect(Unit) { Esp32Hub.enter(ctx) }   // connect once for the whole ESP32 area

    val lines = Esp32Hub.lines
    var savedUpTo by Esp32Hub.savedUpToState
    val foxBoard by Esp32Hub.foxBoardState
    val mode by Esp32Hub.modeState
    val connected = Esp32Hub.connected
    val overVia = if (mode == Esp32Mode.USB) "USB-OTG" else "Flipper relay"

    val listState = rememberLazyListState()
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }

    var text by session.text
    var focused by remember { mutableStateOf(false) }
    var hadKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) hadKeyboard = true
        else if (hadKeyboard) { hadKeyboard = false; if (focused) focus.clearFocus() }
    }
    BackHandler(enabled = focused) { focus.clearFocus() }
    fun submit() {
        val c = text.trim(); if (c.isEmpty()) return
        Esp32Hub.log("› $c"); scope.launch { Esp32Hub.sendOne(c) }; text = ""; focus.clearFocus()
    }
    fun saveNow() {
        val path = CliLog.append(ctx, lines.subList(savedUpTo, lines.size).toList())
        savedUpTo = lines.size
        Esp32Hub.log(if (path != null) "[log saved → $path]" else "[nothing new to save]")
    }
    // Flush unsaved lines to the daily log on leave — the link itself stays up (shared, hub-owned).
    DisposableEffect(Unit) {
        onDispose {
            if (savedUpTo < lines.size) {
                runCatching { CliLog.append(ctx, lines.subList(savedUpTo, lines.size).toList()) }
                savedUpTo = lines.size
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Fox.bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)) {
        if (!focused) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Fox.fg,
                modifier = Modifier.size(24.dp).clickable { onClose() })
            Spacer(Modifier.width(10.dp))
            Column {
                Text("ESP32 CLI", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow(if (connected) "over $overVia" else "connecting…")
            }
            Spacer(Modifier.weight(1f))
            if (connected) Pill(if (foxBoard) "FOX" else "ESP32", Fox.live, Fox.liveDim) else Pill("offline", Fox.muted, Fox.line)
        }

        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fox.bg).border(1.dp, Fox.line, RoundedCornerShape(12.dp))) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)) {
                items(lines) { l ->
                    Text(l, color = if (l.startsWith("›")) Fox.amber else if (l.startsWith("[")) Fox.live else Fox.fg,
                        fontFamily = FoxMono, fontSize = 10.5.sp, lineHeight = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { if (!focused) Text("ENTER ESP32 COMMAND", fontSize = 11.sp) },
                modifier = Modifier.weight(1f).fillMaxHeight().onFocusChanged { focused = it.isFocused },
                textStyle = TextStyle(fontFamily = FoxMono, fontSize = 12.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EspTermBtn("Send", Modifier.weight(1f), enabled = true) { submit() }
                EspTermBtn("Save", Modifier.weight(1f), enabled = lines.isNotEmpty()) { saveNow() }
            }
        }
    }
}

@Composable
private fun EspTermBtn(text: String, modifier: Modifier = Modifier, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier.width(74.dp).clip(RoundedCornerShape(11.dp))
            .background(if (enabled) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (enabled) Fox.amber else Fox.line, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Fox.amber else Fox.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}
