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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono

/**
 * Reusable terminal / CLI widgets, shared by the ESP32 and Flipper terminals.
 * [CliInput] is a command line with recent-command recall chips; [CliTerminal] wraps a scrollback
 * view plus that input in a card. Pure UI — the caller supplies [onSend] and the scrollback [lines].
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CliInput(hint: String, enabled: Boolean = true, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val history = remember { mutableStateListOf<String>() }
    fun submit() {
        val c = text.trim()
        if (c.isEmpty()) return
        if (history.lastOrNull() != c) { history.add(c); if (history.size > 50) history.removeAt(0) }
        onSend(c); text = ""; focus.clearFocus()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { if (!focused) Text(hint, fontSize = 11.sp) },
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                textStyle = TextStyle(fontFamily = FoxMono, fontSize = 12.sp),
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            TermBtn("Send", enabled) { submit() }
        }
        if (history.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                history.takeLast(6).reversed().forEach { h -> TermChip(h) { text = h } }
            }
        }
    }
}

@Composable
fun CliTerminal(
    title: String,
    subtitle: String? = null,
    lines: List<String>,
    hint: String,
    enabled: Boolean = true,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
) {
    FoxCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(title)
                Spacer(Modifier.weight(1f))
                Text("clear", color = Fox.muted, fontSize = 11.sp, modifier = Modifier.clickable { onClear() })
            }
            if (subtitle != null) Text(subtitle, color = Fox.faint, fontSize = 10.sp, lineHeight = 14.sp)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                if (lines.isEmpty()) {
                    Text("— no output yet —", color = Fox.faint, fontFamily = FoxMono, fontSize = 10.5.sp)
                } else {
                    lines.takeLast(80).forEach { l ->
                        Text(
                            l,
                            color = if (l.startsWith("›")) Fox.amber else Fox.fg,
                            fontFamily = FoxMono, fontSize = 10.5.sp, lineHeight = 13.sp,
                        )
                    }
                }
            }
            CliInput(hint, enabled, onSend)
        }
    }
}

@Composable
private fun TermBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(11.dp))
            .background(if (enabled) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (enabled) Fox.amber else Fox.line, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 11.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Fox.amber else Fox.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun TermChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Fox.surface2)
            .border(1.dp, Fox.line, RoundedCornerShape(8.dp))
            .clickable { onClick() }.padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(
            if (text.length > 22) text.take(21) + "…" else text,
            color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp, maxLines = 1,
        )
    }
}
