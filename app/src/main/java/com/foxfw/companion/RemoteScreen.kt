package com.foxfw.companion

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill

// The Flipper's own text-field font (haxrcorp4089), generated pixel-for-pixel from the baked OCR glyph
// data into res/font/haxrcorp4089.ttf. Using it in the edit box makes a character look the SAME on the
// phone as on the Flipper — including look-alikes like l / I / 1 — so an OCR mismatch barely shows.
private val FoxPixel = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(com.foxfw.companion.R.font.haxrcorp4089),
)

// Flipper screen: 128x64, u8g2 page buffer. Rendered as amber-on-dark to match the device.
private const val SCREEN_W = 128
private const val SCREEN_H = 64
private val SCREEN_ON = 0xFFFF8A3D.toInt()    // Fox amber
private val SCREEN_OFF = 0xFF0B0A0C.toInt()   // near-black LCD

/** Decode the 1bpp framebuffer into an ARGB bitmap (page layout: buf[x + (y/8)*128], bit y&7). */
private fun frameBitmap(fb: ByteArray): Bitmap? {
    if (fb.size < SCREEN_W * SCREEN_H / 8) return null
    val px = IntArray(SCREEN_W * SCREEN_H)
    for (y in 0 until SCREEN_H) {
        val page = (y shr 3) * SCREEN_W
        val bit = y and 7
        val rowBase = y * SCREEN_W
        for (x in 0 until SCREEN_W) {
            val on = (fb[page + x].toInt() shr bit) and 1
            px[rowBase + x] = if (on == 1) SCREEN_ON else SCREEN_OFF
        }
    }
    return Bitmap.createBitmap(px, SCREEN_W, SCREEN_H, Bitmap.Config.ARGB_8888)
}

/** Decode just the full-width rows [y0,y1) of the framebuffer — used to show ONLY the Flipper's text
 *  field (its frame sits at y8..19) big across the top of the landscape keyboard takeover. */
private fun frameBitmapRows(fb: ByteArray, y0: Int, y1: Int): Bitmap? {
    if (fb.size < SCREEN_W * SCREEN_H / 8) return null
    val h = y1 - y0
    if (h <= 0) return null
    val px = IntArray(SCREEN_W * h)
    for (y in y0 until y1) {
        val page = (y shr 3) * SCREEN_W
        val bit = y and 7
        val rowBase = (y - y0) * SCREEN_W
        for (x in 0 until SCREEN_W) {
            val on = (fb[page + x].toInt() shr bit) and 1
            px[rowBase + x] = if (on == 1) SCREEN_ON else SCREEN_OFF
        }
    }
    return Bitmap.createBitmap(px, SCREEN_W, h, Bitmap.Config.ARGB_8888)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(fle: FlipperBle, onClose: () -> Unit, launchApp: String? = null) {
    val connected = fle.conn == Conn.CONNECTED

    // Start the stream on enter, stop + release on leave.
    DisposableEffect(connected) {
        if (connected) fle.startScreenStream()
        onDispose { fle.stopScreenStream() }
    }

    // Optional: open a specific Flipper app on entry (e.g. the SubGHz waveform editor, a paint app),
    // then just mirror + drive it with the D-pad below. Matches the first installed .fap whose name
    // contains the query; if none is found the user is told to install / open it themselves.
    var launchStatus by remember { mutableStateOf<String?>(null) }
    if (launchApp != null) LaunchedEffect(connected) {
        if (!connected) { launchStatus = "Connect the Flipper (Device tab) first."; return@LaunchedEffect }
        launchStatus = "Opening on the Flipper…"
        val ok = launchFapMatching(fle, launchApp)
        launchStatus = if (ok) null
        else "No app matching \"$launchApp\" found on the SD — install it, or open it on the Flipper yourself."
    }

    // When the type-box is focused the keyboard is up, so we collapse the header + D-pad to just
    // [screen] → [text box] → [buttons] stacked right above the keyboard, and scroll them into view.
    var kbFocused by remember { mutableStateOf(false) }
    val scroll = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(kbFocused) { if (kbFocused) scroll.animateScrollTo(scroll.maxValue) }
    // Closing the keyboard (Back / swipe-down) doesn't clear the text-field focus on its own, so the
    // compact layout would stick. Watch the IME: when it hides after being shown, drop focus — that
    // fires onFocusChanged(false) and restores the header + D-pad.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    var hadKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) hadKeyboard = true
        else if (hadKeyboard) { hadKeyboard = false; focus.clearFocus() }
    }

    // In LANDSCAPE the whole page goes full-screen (via a Dialog, so the app's title bar + bottom nav are
    // gone): either the keyboard takeover (when the Flipper is on a text field — owned by KeyboardPanel,
    // reported back as `takeover`) or, otherwise, a big mirror on the left with the D-pad + Back on the
    // right. The portrait Column below renders nothing but KeyboardPanel while landscape (it keeps the
    // queue + text-screen detection alive behind the overlay).
    var takeover by remember { mutableStateOf(false) }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Rotate-to-landscape hint: when the Flipper opens a keyboard while the phone is in PORTRAIT, briefly
    // pop a "rotate for the big keyboard" animation in the centre (plays twice, then disappears). Skipped
    // if already in landscape (the big keyboard is already up). KeyboardPanel reports the text-screen state.
    var flipperOnText by remember { mutableStateOf(false) }
    var rotateHint by remember { mutableStateOf(false) }
    LaunchedEffect(flipperOnText) { rotateHint = flipperOnText && !landscape }
    LaunchedEffect(landscape) { if (landscape) rotateHint = false }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize()
            .then(if (takeover || landscape) Modifier else Modifier.verticalScroll(scroll).imePadding())
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (takeover || landscape) 0.dp else if (kbFocused) 12.dp else 8.dp),
    ) {
        if (!landscape && !kbFocused) Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Screen Mirror", color = Fox.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("LIVE MIRROR · RPC INPUT", color = Fox.muted, fontFamily = FoxMono,
                    fontSize = 10.sp, letterSpacing = 1.sp)
            }
            val streaming by remember { derivedStreaming() }
            Pill(if (connected && streaming) "Live" else "Idle",
                if (connected && streaming) Fox.live else Fox.faint,
                if (connected && streaming) Fox.liveDim else Fox.surface2, dot = true)
        }

        if (!landscape && !kbFocused) launchStatus?.let {
            Text(it, color = Fox.amber, fontFamily = FoxMono, fontSize = 10.sp, lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 8.dp))
        }

        // The mirrored display — thin bezel so the screen fills the width (bigger). Hidden in landscape,
        // which shows its own full-screen layout (keyboard takeover or big-mirror + D-pad) over the app.
        if (!landscape) androidx.compose.material3.Surface(
            shape = RoundedCornerShape(14.dp), color = Fox.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, Fox.line),
        ) {
            Box(Modifier.padding(6.dp)) {
                val count = ScreenState.frameCount         // read -> recompose each frame
                val fb = ScreenState.frame
                val bmp = remember(count) { fb?.let { frameBitmap(it) } }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(SCREEN_W.toFloat() / SCREEN_H)
                        .clip(RoundedCornerShape(8.dp)).background(Color(SCREEN_OFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(), contentDescription = "Flipper screen",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.None,   // crisp, non-blurred pixels
                        )
                    } else {
                        Text(
                            when {
                                connected -> "Waiting for frames…"
                                fle.conn == Conn.CONNECTING || fle.conn == Conn.SCANNING -> "Reconnecting…"
                                else -> "Not connected"
                            },
                            color = Fox.faint, fontFamily = FoxMono, fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        if (!landscape && !kbFocused) ControlPad(enabled = connected, fle = fle)
        KeyboardPanel(enabled = connected, fle = fle, compact = kbFocused,
            onFocusChange = { kbFocused = it }, onTakeoverChange = { takeover = it; if (it) kbFocused = false },
            onTextScreenChange = { flipperOnText = it })
    }

    // Landscape, NOT on a Flipper text field: full-screen mirror layout — big screen on the left, D-pad +
    // Back on the right, no title bars. (The text-field case is the keyboard takeover, owned by KeyboardPanel.)
    if (landscape && !takeover) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { onClose() },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false,
            ),
        ) {
            LandscapeMirror(connected = connected, fle = fle)
        }
    }

    // Centred rotate hint overlay (portrait only, non-blocking — disappears on its own after 2 plays).
    if (rotateHint && !landscape) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            RotateHint(onDone = { rotateHint = false })
        }
    }
    }
}

/** Landscape full-screen mirror: the Flipper screen blown up on the left, the D-pad control cluster on the
 *  right. No title/subtitle/pill/back arrow — just the screen and the controls. (System-back or rotating to
 *  portrait exits; the hosting Dialog's onDismissRequest handles it.) */
@Composable
private fun LandscapeMirror(connected: Boolean, fle: FlipperBle) {
    Row(
        Modifier.fillMaxSize().background(Fox.bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // LEFT: the screen, as big as the left two-thirds allows (keeps the 2:1 aspect).
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(14.dp), color = Fox.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Fox.line),
            ) {
                Box(Modifier.padding(6.dp)) {
                    val count = ScreenState.frameCount
                    val fb = ScreenState.frame
                    val bmp = remember(count) { fb?.let { frameBitmap(it) } }
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(SCREEN_W.toFloat() / SCREEN_H)
                            .clip(RoundedCornerShape(8.dp)).background(Color(SCREEN_OFF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) Image(
                            bitmap = bmp.asImageBitmap(), contentDescription = "Flipper screen",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None,
                        ) else Text(
                            when {
                                connected -> "Waiting for frames…"
                                fle.conn == Conn.CONNECTING || fle.conn == Conn.SCANNING -> "Reconnecting…"
                                else -> "Not connected"
                            },
                            color = Fox.faint, fontFamily = FoxMono, fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        // RIGHT: just the D-pad + Flipper-Back cluster, centred.
        Column(
            Modifier.width(300.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ControlPad(enabled = connected, fle = fle)
        }
    }
}

/** One queued keyboard operation, executed as part of a batch ~2s after the user stops typing. */
private sealed class KbOp {
    data class Ins(val c: Char) : KbOp()
    object Del : KbOp()
    object Submit : KbOp()
}

/** Edit the Flipper's text field from the phone: the box MIRRORS the name shown on the Flipper (read by
 *  OCR off the screen mirror) and keeps its cursor pinned to the END, so every keystroke — a letter or
 *  Backspace — lands at the end of the name on the Flipper. ENTER steers to OK and saves. */
@Composable
private fun KeyboardPanel(enabled: Boolean, fle: FlipperBle, compact: Boolean,
                         onFocusChange: (Boolean) -> Unit, onTakeoverChange: (Boolean) -> Unit,
                         onTextScreenChange: (Boolean) -> Unit) {
    val kb = remember { FlipperKeyboard() }
    val pending = remember { ArrayDeque<KbOp>() }   // ops queued (insert/delete/submit), drained in batches
    var lastInputAt by remember { mutableStateOf(0L) }
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var focused by remember { mutableStateOf(false) }
    var executing by remember { mutableStateOf(false) }   // true while a batch is being sent
    var status by remember { mutableStateOf("") }
    var onTextScreen by remember { mutableStateOf(false) }
    fun setText(t: String) { field = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length)) }

    // Shared enqueue helpers — the portrait text box and the landscape on-screen keyboard both feed the
    // SAME queue + batch worker, so fast taps just stack and a running batch is never interrupted.
    fun bump() { lastInputAt = android.os.SystemClock.elapsedRealtime() }
    fun enqIns(c: Char) { synchronized(pending) { pending.addLast(KbOp.Ins(c)) }; bump() }
    fun enqDel() { synchronized(pending) { if (pending.lastOrNull() is KbOp.Ins) pending.removeLast() else pending.addLast(KbOp.Del) }; bump() }
    fun enqSubmit() { synchronized(pending) { pending.addLast(KbOp.Submit) }; bump() }

    LaunchedEffect(Unit) { FlipperOcr.ensureLoaded() }

    // Landscape keyboard takeover — engages ONLY when the user themselves rotates to landscape while the
    // Flipper is on a text-entry screen. We never force the orientation; we just follow the phone's.
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val takeoverActive = enabled && onTextScreen && landscape
    LaunchedEffect(takeoverActive) { onTakeoverChange(takeoverActive) }
    // Live flag the batch worker can read for its debounce: on the landscape on-screen keyboard each tap
    // is an explicit op (no IME to wait on), so we commit much sooner than the portrait text box needs.
    val takeoverNow = androidx.compose.runtime.rememberUpdatedState(takeoverActive)

    // Watch whether the Flipper is ACTUALLY on a text-entry screen. Everything below gates on this, so we
    // never OCR the desktop/boot logo into the box, and never fire keystrokes into a menu.
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(400)
            val fb = ScreenState.frame
            val onText = fb != null && FlipperKeyboard.isTextInput(fb)
            if (onText != onTextScreen) {
                onTextScreen = onText
                onTextScreenChange(onText)
                if (!onText) { synchronized(pending) { pending.clear() }; setText(""); status = "Open a text field on the Flipper to edit its text" }
                else status = ""
            }
        }
    }

    // Debounced BATCH typer: type a whole word fast, and ~1s after the last keystroke the worker reads
    // the Flipper screen ONCE to anchor, then steers the whole batch in one burst WITHOUT re-reading
    // between letters. A batch runs to completion before the next starts (no mid-word interruption).
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            if (synchronized(pending) { pending.isEmpty() }) { kotlinx.coroutines.delay(80); continue }
            val quietNeeded = if (takeoverNow.value) 300L else 1000L   // snappy on the landscape pad
            val quiet = android.os.SystemClock.elapsedRealtime() - lastInputAt
            if (quiet < quietNeeded) { kotlinx.coroutines.delay(quietNeeded - quiet); continue }
            val batch = synchronized(pending) { val l = pending.toList(); pending.clear(); l }
            if (batch.isNotEmpty()) { executing = true; executeBatch(fle, kb, batch); executing = false }
        }
    }

    // Live mirror: while the Flipper is on a text field (and we're not mid-edit), OCR it and reflect it
    // into the box — so the box is ALWAYS filled with the on-device name the moment the field appears,
    // even before the user taps it. A long name that shows "…" fills in as Backspace shortens it to fit.
    LaunchedEffect(enabled, onTextScreen) {
        if (!enabled || !onTextScreen) return@LaunchedEffect
        var lastRead: String? = null
        while (true) {
            kotlinx.coroutines.delay(400)
            if (synchronized(pending) { pending.isNotEmpty() } || executing) continue   // user mid-edit
            if (android.os.SystemClock.elapsedRealtime() - lastInputAt < 1200) continue   // just typed
            val fb = ScreenState.frame ?: continue
            if (!FlipperKeyboard.isTextInput(fb)) continue
            val t = FlipperOcr.read(fb)
            if (t == lastRead) {                                                          // stable across 2 reads
                if (t != field.text) setText(t)
                status = "mirroring the Flipper" + (if (FlipperOcr.looksScrolled(fb)) " · long name (…) — Backspace to reveal the start" else "")
            }
            lastRead = t
        }
    }

    // LANDSCAPE TAKEOVER — a full-screen overlay (Dialog, so it covers the app's header + bottom nav) that
    // looks like a blown-up Flipper screen: the cropped text field across the very top, the Fox keyboard
    // filling everything else, orange-on-black. Each tap drives the Flipper key-by-key through the same
    // queue, so a word typed fast just stacks and the device catches up. Engages only in landscape.
    if (takeoverActive) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false,
            ),
        ) {
            LandscapeKbTakeover(
                onChar = { c -> enqIns(c) },
                onDel = { enqDel() },
                onOk = { enqSubmit() },
            )
        }
        return
    }

    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (!onTextScreen) {
        // Nothing to edit unless the Flipper is on a text-entry screen — hide the whole editor.
        Text("No text field open on the Flipper. Open a rename (or any text entry) and it'll appear here to edit.",
            color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp, lineHeight = 14.sp)
      } else {
        if (!compact) Row(verticalAlignment = Alignment.CenterVertically) {
            com.foxfw.companion.ui.Eyebrow("Edit on Flipper")
            Spacer(Modifier.weight(1f))
            Text("mirroring the Flipper's text", color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp)
        }
        androidx.compose.material3.OutlinedTextField(
            value = field,
            onValueChange = { nv ->
                if (!enabled) return@OutlinedTextField
                if (!onTextScreen) { setText(""); return@OutlinedTextField }   // nothing on the Flipper to edit
                // Only accept characters the Flipper keyboard can actually produce — anything else the
                // user types (emoji, curly quotes, tab…) is dropped so the box can't hold an un-typeable name.
                val old = field.text; val new = nv.text.filter { FlipperKeyboard.supports(it) }
                var i = 0
                while (i < old.length && i < new.length && old[i] == new[i]) i++
                val removed = old.length - i
                val added = new.substring(i)
                // HARD SAFETY CAP: the cursor is pinned to the end with no selection, so a single keystroke
                // can delete at most ONE character. A bigger delete is an IME/mirror desync (the bug that
                // wiped whole names) — reject it outright so it can never mass-delete on the Flipper.
                if (removed > 1 && added.isEmpty()) { setText(old); return@OutlinedTextField }
                synchronized(pending) {
                    // A deletion cancels a still-queued insert if the batch ends in one; otherwise it
                    // becomes a real Flipper delete (long Back, at the end of the name).
                    repeat(removed) { if (pending.lastOrNull() is KbOp.Ins) pending.removeLast() else pending.addLast(KbOp.Del) }
                    for (c in added) pending.addLast(KbOp.Ins(c))
                }
                lastInputAt = android.os.SystemClock.elapsedRealtime()
                setText(new)   // pin the cursor to the end — no mid-string editing to reconcile
            },
            enabled = enabled,
            singleLine = true,
            // Ascii + no autocorrect: stop the phone keyboard composing "words", which (combined with the
            // mirror reassigning the field) made a single Backspace delete a whole word at once.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                autoCorrect = false,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                synchronized(pending) { pending.addLast(KbOp.Submit) }   // after any queued letters, steer to OK
                lastInputAt = android.os.SystemClock.elapsedRealtime()
            }),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FoxPixel, fontSize = 18.sp),
            placeholder = { Text("tap to edit the Flipper's text…", color = Fox.faint, fontFamily = FoxMono, fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { f -> focused = f.isFocused; onFocusChange(f.isFocused); if (f.isFocused) lastInputAt = 0L }
                .onPreviewKeyEvent { e ->
                    // Backspace on an empty box fires no onValueChange — catch it here so you can keep
                    // deleting the name on the Flipper even once the box has emptied.
                    if (enabled && onTextScreen && e.type == KeyEventType.KeyDown && e.key == Key.Backspace && field.text.isEmpty()) {
                        synchronized(pending) { if (pending.lastOrNull() is KbOp.Ins) pending.removeLast() else pending.addLast(KbOp.Del) }
                        lastInputAt = android.os.SystemClock.elapsedRealtime()
                        true
                    } else false
                },
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Fox.fg, unfocusedTextColor = Fox.fg,
                focusedBorderColor = Fox.amber, unfocusedBorderColor = Fox.line,
                cursorColor = Fox.amber,
                focusedContainerColor = Fox.surface, unfocusedContainerColor = Fox.surface,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KbChip("⌫  Backspace", enabled && onTextScreen, Modifier.weight(1f)) {
                synchronized(pending) { if (pending.lastOrNull() is KbOp.Ins) pending.removeLast() else pending.addLast(KbOp.Del) }
                lastInputAt = android.os.SystemClock.elapsedRealtime()
                if (field.text.isNotEmpty()) setText(field.text.dropLast(1))
            }
            KbChip("⏎  Save", enabled && onTextScreen, Modifier.weight(1f)) {
                synchronized(pending) { pending.addLast(KbOp.Submit) }
                lastInputAt = android.os.SystemClock.elapsedRealtime()
            }
        }
        if (status.isNotEmpty()) Text(status, color = Fox.amber, fontFamily = FoxMono, fontSize = 9.sp, lineHeight = 13.sp)
        Text(
            "The box mirrors the name on the Flipper and pins the cursor to the end — type or Backspace to change the end of the name, ENTER saves.",
            color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, lineHeight = 13.sp,
        )
      }
    }
}

/** Wait for the mirror to settle (the keyboard isn't mid-redraw) then recover the cursor state, or
 *  null if the current frame doesn't look like the text_input keyboard. */
private suspend fun settleReadState(): FlipperKeyboard.Snapshot? {
    var last = ScreenState.frameCount
    var stableSince = android.os.SystemClock.elapsedRealtime()
    val deadline = stableSince + 700
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
        kotlinx.coroutines.delay(40)
        val c = ScreenState.frameCount
        if (c != last) { last = c; stableSince = android.os.SystemClock.elapsedRealtime() }
        else if (android.os.SystemClock.elapsedRealtime() - stableSince >= 130) break
    }
    val fb = ScreenState.frame ?: return null
    if (!FlipperKeyboard.isTextInput(fb)) return null    // only anchor on a real text-entry screen
    return FlipperKeyboard.readState(fb)
}

/** Drive the batch one op at a time, VERIFYING focus from the live mirror before every commit. For
 *  each insert: steer to where the model thinks the key is, re-read the screen, and only press OK if the
 *  cursor truly landed on that key — otherwise re-anchor from the real position and try again. The blind
 *  burst approach drifted (one bad move cascaded through the whole word); this self-corrects per letter.
 *  Runs to completion before the next batch starts, so a word in flight is never interrupted. */
private suspend fun executeBatch(fle: FlipperBle, kb: FlipperKeyboard, batch: List<KbOp>) {
    var anchor: FlipperKeyboard.Snapshot? = settleReadState() ?: return  // not a text-entry screen — drop it
    for (op in batch) {
        anchor = when (op) {
            is KbOp.Ins -> typeVerified(fle, kb, op.c, anchor)
            KbOp.Del -> {
                // long Back = DEL, position-independent; re-anchor after since the field changed.
                fle.sendKeys(listOf(FlipperKeyboard.Press(Proto.KEY_BACK, long = true)))
                kotlinx.coroutines.delay(170); settleReadState() ?: anchor
            }
            KbOp.Submit -> { submitVerified(fle, kb, anchor); return }   // keyboard closes after save
        }
    }
}

/** Move onto the target key, confirm from the mirror that we're really on it, then press OK (long = shift
 *  for uppercase). Retries up to 4×, re-reading the true position each time. If focus is in the text box,
 *  the ONLY recovery is a single DOWN then a re-read of where it lands. Returns the resting position (the
 *  cursor stays on the key after insert) so the next letter can chain without an extra read, or null. */
private suspend fun typeVerified(
    fle: FlipperBle, kb: FlipperKeyboard, ch: Char, start: FlipperKeyboard.Snapshot?,
): FlipperKeyboard.Snapshot? {
    val target = kb.navFor(ch) ?: return start                      // unsupported char — skip, keep anchor
    var cur = start
    repeat(4) {
        if (cur == null) cur = settleReadState()
        val s = cur ?: return@repeat
        if (s.inText) {                                             // drifted into the name field up top
            fle.sendKeys(listOf(FlipperKeyboard.Press(Proto.KEY_DOWN)))  // the one way out: press DOWN once…
            kotlinx.coroutines.delay(210)
            cur = settleReadState()                                 // …then see where focus actually landed
            return@repeat
        }
        kb.setState(s)
        val moves = kb.pathTo(ch)
        if (moves.isNotEmpty()) { fle.sendKeys(moves); kotlinx.coroutines.delay(110L + moves.size * 70L) }
        val after = settleReadState()
        if (after != null && !after.inText && !after.inButtons &&
            after.layer == target.layer && after.row == target.row && after.col == target.col) {
            fle.sendKeys(listOf(FlipperKeyboard.Press(Proto.KEY_OK, long = target.shift)))  // landed → commit
            kotlinx.coroutines.delay(150)
            return after
        }
        cur = after                                                 // missed → re-anchor and retry
    }
    return cur
}

/** Steer to the Save button, confirm we're on it, then press OK — same verify-before-commit discipline. */
private suspend fun submitVerified(fle: FlipperBle, kb: FlipperKeyboard, start: FlipperKeyboard.Snapshot?) {
    var cur = start
    repeat(4) {
        if (cur == null) cur = settleReadState()
        val s = cur ?: return@repeat
        if (s.inText) {
            fle.sendKeys(listOf(FlipperKeyboard.Press(Proto.KEY_DOWN)))
            kotlinx.coroutines.delay(210); cur = settleReadState(); return@repeat
        }
        kb.setState(s)
        val moves = kb.pathToSubmit()
        if (moves.isNotEmpty()) { fle.sendKeys(moves); kotlinx.coroutines.delay(110L + moves.size * 70L) }
        val after = settleReadState()
        if (after != null && after.inButtons && after.row == 2) {
            fle.sendKeys(listOf(FlipperKeyboard.Press(Proto.KEY_OK)))
            kotlinx.coroutines.delay(150)
            return
        }
        cur = after
    }
    // Couldn't confirm the button — last-resort blind submit from the best-known position.
    cur?.let { kb.setState(it) }; fle.sendKeys(kb.submit())
}

@Composable
private fun KbChip(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(11.dp)).background(Fox.surface)
            .border(1.dp, Fox.line, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Fox.fg else Fox.faint, fontFamily = FoxMono, fontSize = 11.sp)
    }
}

// The exact Fox keyboard layout (applications/services/gui/modules/text_input.c), replicated on the phone.
private val FOX_LETTERS = listOf("qwertyuiop", "asdfghjkl:", "zxcvbnm ./")
private val FOX_SYMBOLS = listOf("1234567890", ":/.-_=@#&", "!?%+*,;()")

// Blown-up Flipper screen palette: amber ink on near-black, exactly the mirror's own two colours.
private val KB_INK = Color(SCREEN_ON)
private val KB_BG = Color(SCREEN_OFF)

/**
 * Landscape takeover: the WHOLE page becomes a blown-up Flipper screen. The Flipper's text field (cropped
 * straight from the live mirror) is the top line; the Fox keyboard — orange-on-black, exact layout — fills
 * everything else. Tapping a key enqueues one op for the batch worker, so a word typed fast just stacks and
 * the device catches up. Long-press a letter for UPPERCASE (the firmware's shift). SYM/ABC flips; OK saves.
 * No title, no pills, no app chrome — it's drawn in a full-screen Dialog over the app.
 */
@Composable
private fun LandscapeKbTakeover(onChar: (Char) -> Unit, onDel: () -> Unit, onOk: () -> Unit) {
    var symLayer by remember { mutableStateOf(false) }
    val rows = if (symLayer) FOX_SYMBOLS else FOX_LETTERS

    Column(Modifier.fillMaxSize().background(KB_BG).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // ---- Top line: ONLY the Flipper's text field, cropped from the mirror and blown up full width ----
        // Crop to the field INTERIOR (text band y9..18) only — exclude the Flipper's own frame (top y8 /
        // bottom y19), which scales to a broken dotted line, so the single solid amber border is all you see.
        val count = ScreenState.frameCount
        val fb = ScreenState.frame
        val strip = remember(count) { fb?.let { frameBitmapRows(it, 9, 19) } }
        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(6.dp))
                .background(KB_BG).border(1.5.dp, KB_INK, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (strip != null) Image(
                bitmap = strip.asImageBitmap(), contentDescription = "Flipper text field",
                modifier = Modifier.fillMaxSize().padding(2.dp),
                contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None,
            ) else Text("…", color = KB_INK, fontFamily = FoxMono)
        }

        // ---- Everything else: the Fox keyboard, filling the rest of the page ----
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            // char grid
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val cols = 10   // longest row; every key is weight 1 of this, so ALL char keys are one width
                rows.forEach { row ->   // and shorter rows (sym rows 2 & 3) are centred under the first row
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        val pad = (cols - row.length) / 2f           // equal weight each side centres the row
                        if (pad > 0f) Spacer(Modifier.weight(pad))
                        for (c in row) {
                            val isLetter = c in 'a'..'z'
                            FoxKey(
                                label = if (c == ' ') "_" else c.toString(),   // the Flipper draws space as "_"
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onTap = { onChar(c) },
                                onLong = if (isLetter) ({ onChar(c.uppercaseChar()) }) else null,
                            )
                        }
                        if (pad > 0f) Spacer(Modifier.weight(pad))
                    }
                }
            }
            // right button column — DEL / SYM·ABC / OK, exactly as the firmware draws it
            Column(Modifier.width(118.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FoxKey("⌫", Modifier.fillMaxWidth().weight(1f), onTap = onDel)
                FoxKey(if (symLayer) "ABC" else "SYM", Modifier.fillMaxWidth().weight(1f), onTap = { symLayer = !symLayer })
                FoxKey("OK", Modifier.fillMaxWidth().weight(1f), primary = true, onTap = onOk)
            }
        }
    }
}

/** One key on the blown-up Fox keyboard: amber outline on black, inverts to a filled amber box while held
 *  (just like a selected key on the real Flipper). Tap types; long-press (letters) types uppercase. */
@Composable
private fun FoxKey(
    label: String, modifier: Modifier = Modifier, primary: Boolean = false,
    onTap: () -> Unit, onLong: (() -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val filled = primary || pressed
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (filled) KB_INK else KB_BG)
            .border(1.5.dp, KB_INK, RoundedCornerShape(8.dp))
            .pointerInput(onTap, onLong) {
                detectTapGestures(
                    onPress = { pressed = true; try { tryAwaitRelease() } finally { pressed = false } },
                    onTap = { onTap() },
                    onLongPress = onLong?.let { fn -> { _ -> fn() } },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) KB_BG else KB_INK,
            fontFamily = FoxMono, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

/** Brief "rotate your phone" hint: a phone glyph tips from portrait to landscape twice (with a curved
 *  arrow cue), then calls [onDone]. Shown centred when the Flipper opens a keyboard while still portrait. */
@Composable
private fun RotateHint(onDone: () -> Unit) {
    val angle = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        repeat(2) {
            angle.snapTo(0f)
            kotlinx.coroutines.delay(220)
            angle.animateTo(90f, androidx.compose.animation.core.tween(
                620, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            kotlinx.coroutines.delay(520)
        }
        onDone()
    }
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(18.dp), color = Fox.surface,
        border = BorderStroke(1.dp, Fox.line), shadowElevation = 10.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                RotateArc(Modifier.size(100.dp))               // static curved arrow cue
                PhoneGlyph(Modifier.rotate(angle.value))       // the tipping phone
            }
            Spacer(Modifier.height(14.dp))
            Text("Rotate for the big keyboard", color = Fox.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text("turn your phone sideways", color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
        }
    }
}

/** A portrait phone outline in amber, with a speaker notch and a home bar. */
@Composable
private fun PhoneGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier.size(42.dp, 74.dp)) {
        val w = size.width; val h = size.height
        val amber = Color(SCREEN_ON)
        drawRoundRect(
            color = amber,
            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.18f, w * 0.18f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.09f),
        )
        drawLine(amber,
            androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.085f),
            androidx.compose.ui.geometry.Offset(w * 0.62f, h * 0.085f),
            strokeWidth = w * 0.07f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(amber,
            androidx.compose.ui.geometry.Offset(w * 0.33f, h * 0.93f),
            androidx.compose.ui.geometry.Offset(w * 0.67f, h * 0.93f),
            strokeWidth = w * 0.07f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** A clockwise curved arrow arcing over the phone — the rotate cue. */
@Composable
private fun RotateArc(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val inset = s * 0.05f
        val col = Color(SCREEN_ON).copy(alpha = 0.5f)
        val sw = s * 0.045f
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawArc(
            color = col, startAngle = -95f, sweepAngle = 125f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(s - inset * 2, s - inset * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = cap),
        )
        // arrowhead at the arc end (angle 30° on the circle; clockwise tangent points +90° from radius)
        val cx = size.width / 2f; val cy = size.height / 2f
        val rad = (s - inset * 2) / 2f
        val end = Math.toRadians(30.0)
        val ex = cx + rad * kotlin.math.cos(end).toFloat()
        val ey = cy + rad * kotlin.math.sin(end).toFloat()
        val dir = Math.toRadians(30.0 + 90.0)      // tangent (clockwise)
        val head = s * 0.11f
        for (d in listOf(148.0, -148.0)) {
            val a = dir + Math.toRadians(d)
            drawLine(col,
                androidx.compose.ui.geometry.Offset(ex, ey),
                androidx.compose.ui.geometry.Offset(ex + head * kotlin.math.cos(a).toFloat(), ey + head * kotlin.math.sin(a).toFloat()),
                strokeWidth = sw, cap = cap)
        }
    }
}

/** The D-pad + OK + Back control cluster. */
@Composable
private fun ControlPad(enabled: Boolean, fle: FlipperBle) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // D-pad: 3x3 with OK in the centre
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DirButton(Icons.Filled.KeyboardArrowUp, enabled) { fle.sendInputTap(Proto.KEY_UP) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                DirButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, enabled) { fle.sendInputTap(Proto.KEY_LEFT) }
                OkButton(enabled, onTap = { fle.sendInputTap(Proto.KEY_OK) },
                    onLong = { fle.sendInputLong(Proto.KEY_OK) })
                DirButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, enabled) { fle.sendInputTap(Proto.KEY_RIGHT) }
            }
            DirButton(Icons.Filled.KeyboardArrowDown, enabled) { fle.sendInputTap(Proto.KEY_DOWN) }
        }

        Spacer(Modifier.weight(1f))

        // Back (tap = short, long-press = hold-to-exit on the Flipper)
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(62.dp).clip(CircleShape).background(Fox.surface)
                    .border(1.dp, Fox.line2, CircleShape)
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectTapGestures(
                            onTap = { fle.sendInputTap(Proto.KEY_BACK) },
                            onLongPress = { fle.sendInputLong(Proto.KEY_BACK) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Back",
                    tint = if (enabled) Fox.fg else Fox.faint, modifier = Modifier.size(26.dp))
            }
            Text("BACK", color = Fox.muted, fontFamily = FoxMono, fontSize = 9.sp, letterSpacing = 1.sp)
            Text("hold = exit", color = Fox.faint, fontFamily = FoxMono, fontSize = 8.sp)
        }
    }
}

@Composable
private fun DirButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(Fox.surface)
            .border(1.dp, Fox.line, RoundedCornerShape(15.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) Fox.fg else Fox.faint, modifier = Modifier.size(27.dp))
    }
}

@Composable
private fun OkButton(enabled: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Fox.amberDim)
            .border(1.dp, Fox.amber.copy(alpha = if (enabled) 0.6f else 0.2f), CircleShape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLong() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("OK", color = if (enabled) Fox.amber else Fox.faint,
            fontFamily = FoxMono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** Snapshot-state read helper so the Pill tracks streaming without extra imports. */
private fun derivedStreaming() = androidx.compose.runtime.derivedStateOf { ScreenState.streaming }

/** Launch the first installed /ext/apps FAP whose filename contains [query] (case-insensitive). */
private suspend fun launchFapMatching(fle: FlipperBle, query: String): Boolean {
    val cats = fle.storageList("/ext/apps")?.filter { it.isDir } ?: return false
    for (cat in cats) {
        val faps = fle.storageList("/ext/apps/${cat.name}")?.filter { !it.isDir && it.name.endsWith(".fap", true) } ?: continue
        val match = faps.firstOrNull { it.name.contains(query, true) } ?: continue
        return fle.appStartSafe("/ext/apps/${cat.name}/${match.name}", "")
    }
    return false
}
