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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.runtime.mutableStateListOf
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
 * Process-lifetime holder for the Flipper CLI so the console log, scroll-back and the text box survive
 * leaving and reopening the terminal (same persistence the ESP32 console has). One [FlipperCli] with its
 * onLine wired once; every visit renders this same buffer.
 */
internal object FlipperCliSession {
    val cli = FlipperCli()
    val lines = mutableStateListOf<String>()
    val savedUpToState = mutableStateOf(0)
    var savedUpTo by savedUpToState
    val textState = mutableStateOf("")
    private var wired = false
    fun append(l: String) {
        lines.add(l)
        if (lines.size > 3000) { val drop = lines.size - 3000; lines.removeRange(0, drop); savedUpTo = (savedUpTo - drop).coerceAtLeast(0) }
    }
    fun init() { if (!wired) { cli.onLine { append(it) }; wired = true } }
}

/**
 * Flipper CLI terminal. A fixed-height scrollback (its own scroll) with the command input + Send/Save
 * pinned at the bottom, always visible above the keyboard. Commands run on the Flipper via the Fox
 * companion IO daemon over the live link (BLE/USB). Console + text box persist across navigation
 * (see [FlipperCliSession]); Save appends to the daily log.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlipperTerminalScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val cli = FlipperCliSession.cli
    val lines = FlipperCliSession.lines
    var savedUpTo by FlipperCliSession.savedUpToState
    fun log(l: String) = FlipperCliSession.append(l)
    LaunchedEffect(Unit) { FlipperCliSession.init() }
    val linked = FlipperTransport.active != null
    val listState = rememberLazyListState()
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }

    var text by FlipperCliSession.textState
    var focused by remember { mutableStateOf(false) }
    var hadKeyboard by remember { mutableStateOf(false) }
    // When the keyboard is dismissed (e.g. hardware Back) but the field keeps focus, drop focus so the
    // normal view (headers + nav) comes back. Immersive is driven by focus, not the animating inset.
    LaunchedEffect(imeVisible) {
        if (imeVisible) hadKeyboard = true
        else if (hadKeyboard) { hadKeyboard = false; if (focused) focus.clearFocus() }
    }
    // Back closes the keyboard first (focus cleared -> header returns); with no keyboard, the app-level
    // BackHandler leaves the tool.
    BackHandler(enabled = focused) { focus.clearFocus() }
    fun submit() {
        val c = text.trim(); if (c.isEmpty()) return
        log("› $c"); scope.launch { cli.run(c) }; text = ""; focus.clearFocus()
    }
    fun saveNow() {
        val path = CliLog.append(ctx, lines.subList(savedUpTo, lines.size).toList())
        savedUpTo = lines.size
        log(if (path != null) "[log saved → $path]" else "[nothing new to save]")
    }

    Column(Modifier.fillMaxSize().background(Fox.bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)) {
        if (!focused) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Fox.fg,
                modifier = Modifier.size(24.dp).clickable { onClose() })
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Flipper CLI", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow(if (linked) "over ${FlipperTransport.kind}" else "no Flipper link")
            }
            Spacer(Modifier.weight(1f))
            if (linked) Pill(FlipperTransport.kind.name, Fox.live, Fox.liveDim) else Pill("offline", Fox.muted, Fox.line)
        }

        // Scrollback — fills remaining height, scrolls on itself (never pushes the input off-screen).
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fox.screen).border(1.dp, Fox.line, RoundedCornerShape(12.dp))) {
            if (lines.isEmpty()) {
                Text("— no output yet —\nRuns on the Flipper via the Fox companion IO app (BLE or USB).\ne.g. device_info · ps · storage list /ext",
                    color = Fox.faint, fontFamily = FoxMono, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(12.dp))
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(lines) { l ->
                        Text(l, color = if (l.startsWith("›")) Fox.amber else if (l.startsWith("[")) Fox.live else Fox.fg,
                            fontFamily = FoxMono, fontSize = 10.5.sp, lineHeight = 13.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Input + Send/Save — pinned, always visible above the keyboard.
        // FIXED height so fillMaxHeight is bounded — otherwise, as an unweighted sibling of the
        // weighted scrollback, the field grabs all the remaining height and the log collapses.
        Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { if (!focused) Text("ENTER CLI COMMAND", fontSize = 11.sp) },
                modifier = Modifier.weight(1f).fillMaxHeight().onFocusChanged { focused = it.isFocused },
                textStyle = TextStyle(fontFamily = FoxMono, fontSize = 12.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TermBtn2("Send", Modifier.weight(1f), enabled = true) { submit() }
                TermBtn2("Save", Modifier.weight(1f), enabled = lines.isNotEmpty()) { saveNow() }
            }
        }
    }
}

@Composable
private fun TermBtn2(text: String, modifier: Modifier = Modifier, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier.width(74.dp).clip(RoundedCornerShape(11.dp))
            .background(if (enabled) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (enabled) Fox.amber else Fox.line, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Fox.amber else Fox.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}
