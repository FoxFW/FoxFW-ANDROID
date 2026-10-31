package com.foxfw.companion

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill

/** Generic BLE GATT explorer: scan → connect → walk the service tree → read / subscribe / write. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BleGattScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val client = remember { BleGattClient(ctx) }
    val writeFields = remember { mutableStateMapOf<String, String>() } // "svc|char" -> hex text (open = present)

    DisposableEffect(Unit) { onDispose { client.stopScan(); client.disconnect() } }

    val perms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) client.startScan()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Fox.fg, modifier = Modifier.size(22.dp).clickable { onClose() })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("BLE Explorer", color = Fox.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("GATT · SCAN · READ · SUBSCRIBE", color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            when (client.conn) {
                Conn.CONNECTED -> Pill("Linked", Fox.live, Fox.liveDim, dot = true)
                Conn.CONNECTING -> Pill("Linking", Fox.amber, Fox.amberDim)
                else -> if (client.scanning) Pill("Scanning", Fox.amber, Fox.amberDim)
                else Pill("${client.devices.size} seen", Fox.muted, Fox.surface2)
            }
        }

        if (client.conn == Conn.CONNECTED) {
            FoxCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(client.connectedName ?: "(unnamed device)", color = Fox.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    client.connectedAddr?.let { Text(it, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp) }
                    GattBtn("Disconnect") { client.disconnect() }
                    client.status?.let { Text(it, color = Fox.faint, fontSize = 11.sp) }
                }
            }
            client.services.forEach { svc ->
                FoxCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(GattSpec.serviceName(svc.uuid), color = Fox.amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(svc.uuid.toString(), color = Fox.faint, fontFamily = FoxMono, fontSize = 9.5.sp)
                        svc.chars.forEach { ch -> CharRow(client, svc.uuid, ch, writeFields) }
                    }
                }
            }
        } else {
            GattBtn(if (client.scanning) "Stop scan" else "⚡ Scan for devices", accent = !client.scanning) {
                if (client.scanning) client.stopScan() else launcher.launch(perms)
            }
            client.status?.let { Text(it, color = Fox.faint, fontSize = 11.sp) }
            client.devices.forEach { d ->
                FoxCard(modifier = Modifier.fillMaxWidth().clickable { client.connect(d.address) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name ?: "(no name)", color = if (d.name != null) Fox.fg else Fox.muted,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(d.address, color = Fox.muted, fontFamily = FoxMono, fontSize = 10.5.sp)
                        }
                        Text("${d.rssi} dBm", color = rssiColor(d.rssi), fontFamily = FoxMono, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharRow(client: BleGattClient, svc: java.util.UUID, ch: BleGattClient.CharUi, writeFields: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>) {
    val key = "$svc|${ch.uuid}"
    val props = GattSpec.properties(ch.props)
    val readable = ch.props and 0x02 != 0
    val canNotify = ch.props and 0x10 != 0 || ch.props and 0x20 != 0
    val canWrite = ch.props and 0x08 != 0 || ch.props and 0x04 != 0
    val v = ch.value
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(GattSpec.charName(ch.uuid), color = Fox.fg, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        if (props.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            props.forEach { PropChip(it) }
        }
        if (v != null) {
            val human = GattSpec.interpret(ch.uuid, v)
            if (human != null) Text(human, color = Fox.live, fontFamily = FoxMono, fontSize = 12.sp)
            Text(GattSpec.formatValue(v), color = Fox.muted, fontFamily = FoxMono, fontSize = 10.5.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (readable) ActChip("Read", Fox.info) { client.read(svc, ch.uuid) }
            if (canNotify) ActChip(if (ch.notifying) "Stop" else "Notify", if (ch.notifying) Fox.warn else Fox.live) { client.toggleNotify(svc, ch.uuid) }
            if (canWrite) ActChip(if (writeFields.containsKey(key)) "Cancel" else "Write", Fox.amber) {
                if (writeFields.containsKey(key)) writeFields.remove(key) else writeFields[key] = ""
            }
        }
        if (writeFields.containsKey(key)) {
            val hex = writeFields[key] ?: ""
            OutlinedTextField(
                value = hex, onValueChange = { writeFields[key] = it }, singleLine = true,
                label = { Text("hex bytes e.g. 01 A2 FF", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            val bytes = parseHex(hex)
            ActChip(if (bytes != null) "Send ${bytes.size} byte(s)" else "Enter valid hex", if (bytes != null) Fox.live else Fox.faint) {
                if (bytes != null) { client.write(svc, ch.uuid, bytes, noResponse = ch.props and 0x04 != 0 && ch.props and 0x08 == 0); writeFields.remove(key) }
            }
        }
    }
}

/** Parse "01 A2 FF" / "01a2ff" to bytes; null if empty, odd length, or non-hex. */
private fun parseHex(s: String): ByteArray? {
    val clean = s.filter { !it.isWhitespace() }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    return try { ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() } } catch (_: Throwable) { null }
}

private fun rssiColor(rssi: Int) = when { rssi >= -60 -> Fox.live; rssi >= -80 -> Fox.warn; else -> Fox.muted }

@Composable
private fun PropChip(text: String) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Fox.surface2).border(1.dp, Fox.line, RoundedCornerShape(6.dp))
        .padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(text, color = Fox.muted, fontFamily = FoxMono, fontSize = 9.sp)
    }
}

@Composable
private fun ActChip(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
        .clickable { onClick() }.padding(horizontal = 11.dp, vertical = 6.dp)) {
        Text(text, color = color, fontFamily = FoxMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GattBtn(text: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (accent) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (accent) Fox.amber else Fox.line, RoundedCornerShape(12.dp))
            .clickable { onClick() }.padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (accent) Fox.amber else Fox.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
