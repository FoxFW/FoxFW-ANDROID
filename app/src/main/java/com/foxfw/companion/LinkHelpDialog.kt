package com.foxfw.companion

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxMono

/**
 * Shown ~15s into a connect that won't complete. Two variants, chosen by [FlipperBle.linkHelp]:
 *  - STALE_BOND: we reached the Flipper but couldn't finish linking — almost always an old BLE pairing
 *    left over from a firmware/name change. Walk the user through "Forget" + deep-link to BT settings.
 *  - NOT_FOUND: we never reached the device — a softer "is it on / nearby?" nudge.
 */
@Composable
fun LinkHelpDialog(fle: FlipperBle) {
    val help = fle.linkHelp ?: return
    val ctx = LocalContext.current
    val openBtSettings: () -> Unit = {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    when (help) {
        LinkHelp.STALE_BOND -> AlertDialog(
            onDismissRequest = { fle.dismissLinkHelp() },
            containerColor = Fox.surface,
            icon = { Icon(Icons.Outlined.Warning, null, tint = Fox.amber, modifier = Modifier.size(26.dp)) },
            title = { Text("Can't finish connecting", color = Fox.fg, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Your phone found the Flipper but couldn't finish linking. This almost always means " +
                            "an old Bluetooth pairing from before a firmware or name change. Clearing it (\"Forget\") " +
                            "and reconnecting fixes it.",
                        color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 16.sp,
                    )
                    Step(1, "Open Android Settings → Connections (or \"Connected devices\") → Bluetooth")
                    Step(2, "Tap the gear / ⋯ next to your Flipper")
                    Step(3, "Choose Forget / Unpair")
                    Step(4, "Come back here and tap connect again")
                    Text(
                        "The exact wording varies by phone brand.",
                        color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { openBtSettings() }) { Text("Open Bluetooth Settings", color = Fox.amber) }
            },
            dismissButton = {
                TextButton(onClick = { fle.dismissLinkHelp() }) { Text("Keep trying", color = Fox.muted) }
            },
        )

        LinkHelp.NOT_FOUND -> AlertDialog(
            onDismissRequest = { fle.dismissLinkHelp() },
            containerColor = Fox.surface,
            icon = { Icon(Icons.Outlined.BluetoothDisabled, null, tint = Fox.amber, modifier = Modifier.size(26.dp)) },
            title = { Text("Flipper not reachable", color = Fox.fg, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Couldn't reach your Flipper. Check that it's powered on and nearby, that Bluetooth " +
                            "is enabled on it (Settings → Bluetooth), and that your phone's Bluetooth is on, then try again.",
                        color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 16.sp,
                    )
                    Text(
                        "If it keeps finding the Flipper but never links, you may need to \"Forget\" an old pairing in your phone's Bluetooth settings.",
                        color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp, lineHeight = 15.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { fle.dismissLinkHelp() }) { Text("OK", color = Fox.amber) }
            },
            dismissButton = {
                TextButton(onClick = { openBtSettings() }) { Text("Bluetooth Settings", color = Fox.muted) }
            },
        )
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("$n.", color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Fox.fg, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 16.sp)
    }
}
