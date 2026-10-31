package com.foxfw.companion

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill

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

@Composable
fun RemoteScreen(fle: FlipperBle, onClose: () -> Unit) {
    val connected = fle.conn == Conn.CONNECTED

    // Start the stream on enter, stop + release on leave.
    DisposableEffect(connected) {
        if (connected) fle.startScreenStream()
        onDispose { fle.stopScreenStream() }
    }

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .imePadding()
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Fox.fg,
                modifier = Modifier.size(22.dp).clickable { onClose() })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Remote Screen", color = Fox.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("LIVE MIRROR · RPC INPUT", color = Fox.muted, fontFamily = FoxMono,
                    fontSize = 10.sp, letterSpacing = 1.sp)
            }
            val streaming by remember { derivedStreaming() }
            Pill(if (connected && streaming) "Live" else "Idle",
                if (connected && streaming) Fox.live else Fox.faint,
                if (connected && streaming) Fox.liveDim else Fox.surface2, dot = true)
        }

        // The mirrored display — thin bezel so the screen fills the width (bigger).
        androidx.compose.material3.Surface(
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

        Spacer(Modifier.height(2.dp))
        ControlPad(enabled = connected, fle = fle)
        Spacer(Modifier.height(4.dp))
        KeyboardPanel(enabled = connected, fle = fle)
    }
}

/** Type on the Flipper with the phone keyboard by driving its on-screen text_input. */
@Composable
private fun KeyboardPanel(enabled: Boolean, fle: FlipperBle) {
    val kb = remember { FlipperKeyboard() }
    var typed by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.foxfw.companion.ui.Eyebrow("Type on Flipper")
            Spacer(Modifier.weight(1f))
            Text("open a text field first", color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp)
        }
        androidx.compose.material3.OutlinedTextField(
            value = typed,
            onValueChange = { new ->
                if (!enabled) return@OutlinedTextField
                var i = 0
                while (i < typed.length && i < new.length && typed[i] == new[i]) i++
                repeat(typed.length - i) { fle.sendBackspace() }          // deletions
                val add = new.substring(i)
                val keys = ArrayList<FlipperKeyboard.Press>()
                for (ch in add) keys += kb.type(ch)                        // additions -> key paths
                fle.sendKeys(keys)
                typed = new
            },
            enabled = enabled,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FoxMono, fontSize = 15.sp),
            placeholder = { Text("tap here and type…", color = Fox.faint, fontFamily = FoxMono, fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Fox.fg, unfocusedTextColor = Fox.fg,
                focusedBorderColor = Fox.amber, unfocusedBorderColor = Fox.line,
                cursorColor = Fox.amber,
                focusedContainerColor = Fox.surface, unfocusedContainerColor = Fox.surface,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KbChip("⌫  Backspace", enabled, Modifier.weight(1f)) {
                fle.sendBackspace()
                if (typed.isNotEmpty()) typed = typed.dropLast(1)
            }
            KbChip("⏎  Save", enabled, Modifier.weight(1f)) {
                fle.sendKeys(kb.submit()); typed = ""
            }
            KbChip("⟲  Resync", enabled, Modifier.weight(1f)) {
                kb.resync(); typed = ""
            }
        }
        Text(
            "Drives the Flipper keyboard key-by-key. If the on-screen cursor drifts, tap Resync (assumes the keyboard was just opened).",
            color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, lineHeight = 13.sp,
        )
    }
}

@Composable
private fun KbChip(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(11.dp)).background(Fox.surface)
            .border(1.dp, Fox.line, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Fox.fg else Fox.faint, fontFamily = FoxMono, fontSize = 11.sp)
    }
}

/** The D-pad + OK + Back control cluster. */
@Composable
private fun ControlPad(enabled: Boolean, fle: FlipperBle) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // D-pad: 3x3 with OK in the centre
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DirButton(Icons.Filled.KeyboardArrowUp, enabled) { fle.sendInputTap(Proto.KEY_UP) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                DirButton(Icons.Filled.KeyboardArrowLeft, enabled) { fle.sendInputTap(Proto.KEY_LEFT) }
                OkButton(enabled, onTap = { fle.sendInputTap(Proto.KEY_OK) },
                    onLong = { fle.sendInputLong(Proto.KEY_OK) })
                DirButton(Icons.Filled.KeyboardArrowRight, enabled) { fle.sendInputTap(Proto.KEY_RIGHT) }
            }
            DirButton(Icons.Filled.KeyboardArrowDown, enabled) { fle.sendInputTap(Proto.KEY_DOWN) }
        }

        Spacer(Modifier.weight(1f))

        // Back (tap = short, long-press = hold-to-exit on the Flipper)
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(70.dp).clip(CircleShape).background(Fox.surface)
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
                Icon(Icons.Filled.Undo, "Back",
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
        Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(Fox.surface)
            .border(1.dp, Fox.line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) Fox.fg else Fox.faint, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun OkButton(enabled: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    Box(
        Modifier.size(58.dp).clip(CircleShape).background(Fox.amberDim)
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
