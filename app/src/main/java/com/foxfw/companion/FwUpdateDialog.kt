package com.foxfw.companion

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Fox

/**
 * On connect (USB or BLE), if a newer FoxFW than the installed one has been downloaded in the
 * background, ask once per release whether to update. The choice is remembered (app prefs):
 *   • Yes       → jump to Tools → Firmware Update to flash it.
 *   • No        → dismiss this release for good, so users who want to stay on an older build aren't
 *                 nagged about it again.
 *   • Remind me → close for now; ask again on the next connect.
 */
@Composable
fun FwUpdateDialog(fle: FlipperBle, onUpdate: () -> Unit) {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf<String?>(null) }
    val linked = fle.conn == Conn.CONNECTED || UsbLink.usbConnected

    LaunchedEffect(linked) {
        show = false
        if (linked) {
            FwRepo.load(ctx)
            val installed = runCatching {
                (FlipperTransport.active ?: fle).deviceInfo()?.firstOrNull { it.first.lowercase().filter(Char::isLetterOrDigit) == "foxversion" }?.second
            }.getOrNull()
            val t = FwRepo.latestTag
            if (t != null && FwRepo.updateAvailable(ctx, installed) && FwRepo.dismissedTag(ctx) != t) {
                tag = t; show = true
            }
        }
    }

    if (!show) return
    AlertDialog(
        onDismissRequest = { show = false }, // tap-outside behaves like "Remind me"
        title = { Text("Update to FoxFW $tag?", color = Fox.amber) },
        text = {
            Text(
                "FoxFW $tag is downloaded and ready. Update now, or keep your current version — " +
                    "you can always update later from Tools → Firmware Update.",
                color = Fox.fg, fontSize = 13.sp, lineHeight = 18.sp,
            )
        },
        confirmButton = { TextButton(onClick = { show = false; onUpdate() }) { Text("Yes", color = Fox.amber) } },
        dismissButton = {
            Row {
                TextButton(onClick = { show = false }) { Text("Remind me", color = Fox.muted) }
                TextButton(onClick = { FwRepo.dismissPopup(ctx); show = false }) { Text("No", color = Fox.muted) }
            }
        },
        containerColor = Fox.surface,
    )
}
