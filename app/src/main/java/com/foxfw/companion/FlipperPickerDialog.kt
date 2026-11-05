package com.foxfw.companion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import com.foxfw.companion.ui.SignalBars

/**
 * Bluetooth device picker. Opened from the connect button once permissions are granted: lists every
 * Flipper Zero the radio can see (plus already-paired ones) so a user with more than one chooses which
 * to link. Tapping a row connects; Refresh rescans; Cancel dismisses without connecting.
 */
@Composable
fun FlipperPickerDialog(
    fle: FlipperBle,
    onConnect: (FoundFlipper) -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Fox.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Select a Flipper", color = Fox.fg, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                if (fle.discovering) ScanSpinner()
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val found = fle.discovered
                Text(
                    when {
                        found.isEmpty() && fle.discovering -> "Scanning for nearby Flipper Zero's…"
                        found.isEmpty() -> "No Flipper Zero's found. Make sure Bluetooth is on and the Flipper is powered, then tap Refresh."
                        else -> "Tap the Flipper you want to connect to."
                    },
                    color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 16.sp,
                )
                if (found.isNotEmpty()) {
                    Spacer(Modifier.size(12.dp))
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (f in found) FlipperRow(f) { onConnect(f) }
                    }
                }
            }
        },
        // Refresh deliberately does NOT dismiss the dialog — it just rescans in place.
        confirmButton = {
            TextButton(onClick = onRefresh, enabled = !fle.discovering) {
                if (fle.discovering) {
                    // Title already shows a spinner; animate just the trailing dots.
                    ScanningLabel()
                } else {
                    Icon(Icons.Outlined.Refresh, null, tint = Fox.amber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh", color = Fox.amber)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = Fox.muted) }
        },
    )
}

@Composable
private fun FlipperRow(f: FoundFlipper, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Fox.surface2)
            .border(BorderStroke(1.dp, Fox.line2), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.name, color = Fox.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(3.dp))
            Text(
                if (f.address.isNotBlank()) f.address else "unknown address",
                color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp,
            )
        }
        if (f.bonded) {
            Pill("PAIRED", Fox.violet, Fox.violetDim)
            Spacer(Modifier.width(10.dp))
        }
        SignalBars(level = rssiLevel(f.rssi))
    }
}

/** Map a BLE RSSI (dBm) to a 0–4 bar level; null (a bonded device not advertising) shows no bars. */
private fun rssiLevel(rssi: Int?): Int = when {
    rssi == null -> 0
    rssi >= -60 -> 4
    rssi >= -70 -> 3
    rssi >= -80 -> 2
    rssi >= -95 -> 1
    else -> 0
}

@Composable
private fun ScanSpinner() {
    val t = rememberInfiniteTransition(label = "scan")
    val a by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "scan-a",
    )
    Icon(Icons.Outlined.Refresh, null, tint = Fox.amber, modifier = Modifier.size(16.dp).rotate(a))
}

/** "Scanning" with cycling trailing dots (. .. ... ..). The word stays put — the dots live in a
 *  fixed-width slot — so the button doesn't wobble while a scan runs. */
@Composable
private fun ScanningLabel() {
    val dots = listOf(".", "..", "...", "..")
    var i by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(400)
            i = (i + 1) % dots.size
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Scanning", color = Fox.amber)
        Box(Modifier.width(18.dp)) {
            Text(dots[i], color = Fox.amber, modifier = Modifier.align(Alignment.CenterStart))
        }
    }
}
