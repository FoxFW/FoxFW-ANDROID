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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** History: persistent log of decodes/cracks with per-entry share and export-all. Phone-side only. */
@Composable
fun HistoryScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    remember { HistoryStore.ensureLoaded(); true }
    val entries = HistoryStore.entries
    val fmt = remember { SimpleDateFormat("MMM d · HH:mm:ss", Locale.US) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .border(1.dp, Fox.line, RoundedCornerShape(10.dp)).clickable { onClose() },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, null, tint = Fox.fg, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text("History", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Eyebrow("DECODES · CRACKS") }
            if (entries.isNotEmpty()) {
                HBtn("Export all", accent = false) { Sharing.share(ctx, "foxfw-history.txt", HistoryStore.exportText()) }
                Spacer(Modifier.width(8.dp))
                HBtn("Clear", accent = false) { HistoryStore.clear() }
            }
        }

        if (entries.isEmpty()) FoxCard {
            Text("No history yet. Decode a fob (Decode tab) or run a crack — results land here and can be shared out.",
                color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 16.sp)
        }

        entries.forEach { e ->
            FoxCard { Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(e.kind.uppercase(), if (e.kind == "crack") Fox.amber else Fox.live, Fox.surface2)
                    Spacer(Modifier.width(8.dp))
                    Text(e.title, color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(fmt.format(Date(e.tMs)), color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(e.summary, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
                if (e.payload != null) {
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Spacer(Modifier.weight(1f))
                        HBtn("share") {
                            val safe = e.title.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            val mime = if (e.ext == "sub") "application/octet-stream" else "text/plain"
                            Sharing.share(ctx, "$safe.${e.ext}", e.payload, mime)
                        }
                    }
                }
            } }
        }
    }
}

@Composable
private fun HBtn(label: String, accent: Boolean = true, onClick: () -> Unit) {
    val bg = if (accent) Fox.amber else Fox.surface
    val fg = if (accent) Fox.amberInk else Fox.fg
    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(bg)
        .border(1.dp, Fox.line, RoundedCornerShape(9.dp))
        .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
