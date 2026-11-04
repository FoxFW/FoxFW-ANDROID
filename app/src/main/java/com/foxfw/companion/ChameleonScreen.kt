package com.foxfw.companion

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Common MIFARE Classic keys for the dictionary attack (the Proxmark/Flipper default set, trimmed).
internal val MF_DICT = listOf(
    "FFFFFFFFFFFF", "000000000000", "A0A1A2A3A4A5", "D3F7D3F7D3F7", "B0B1B2B3B4B5",
    "4D3A99C351DD", "1A982C7E459A", "AABBCCDDEEFF", "714C5C886E97", "587EE5F9350F",
    "A0478CC39091", "533CB6C723F6", "8FD0A4F256E9", "0000014B5C31", "B578F38A5C61",
    "96A301BCE267",
).map { h -> ByteArray(6) { ((h.substring(it * 2, it * 2 + 2)).toInt(16)).toByte() } }

private enum class CV { MENU, INFO, IDENTIFY, DUMP, SLOTS, EMULATE, CLONE, ATTACK, EMV, TESTSET, LFEMU, RFUZZ }

@Composable
fun ChameleonScreen(onClose: () -> Unit) {
    val cham = remember { FoxApplication.instance.chameleon }
    val connected = cham.conn == Conn.CONNECTED
    var view by remember { mutableStateOf(CV.MENU) }

    val perms = remember {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { l += android.Manifest.permission.BLUETOOTH_SCAN; l += android.Manifest.permission.BLUETOOTH_CONNECT }
        else l += android.Manifest.permission.ACCESS_FINE_LOCATION
        l.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            res[android.Manifest.permission.BLUETOOTH_CONNECT] == true && res[android.Manifest.permission.BLUETOOTH_SCAN] == true
        else res[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (ok) cham.connect()
    }
    val onConnect: () -> Unit = { if (cham.conn != Conn.DISCONNECTED) cham.disconnect() else launcher.launch(perms) }
    // On a drill-down page, system back returns to the menu rather than exiting the whole tool.
    BackHandler(enabled = view != CV.MENU) { view = CV.MENU }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Drill-down pages keep an in-page back-to-menu arrow; the menu itself relies on the
            // outer Tools header arrow (no duplicate arrow on the top-level page).
            if (view != CV.MENU) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Fox.fg,
                    modifier = Modifier.size(22.dp).clickable { view = CV.MENU })
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("ChameleonUltra", color = Fox.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (view == CV.MENU) "BLE · NORDIC UART" else view.name, color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            val label = when (cham.conn) { Conn.CONNECTED -> "Linked"; Conn.CONNECTING -> "Linking"; Conn.SCANNING -> "Scanning"; else -> "Connect" }
            Box(Modifier.clip(RoundedCornerShape(999.dp)).clickable { onConnect() }) {
                Pill(label, if (connected) Fox.live else Fox.amber, if (connected) Fox.liveDim else Fox.amberDim, dot = true)
            }
        }

        if (!connected) {
            FoxCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (cham.conn == Conn.SCANNING || cham.conn == Conn.CONNECTING) "Looking for your ChameleonUltra…"
                         else "Tap Connect to pair with your ChameleonUltra over Bluetooth.", color = Fox.fg, fontSize = 14.sp)
                    Text("Power it on (long-press a button) so it advertises. If a pairing PIN is requested, enter 123456.",
                        color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
            return@Column
        }

        when (view) {
            CV.MENU -> MenuView { view = it }
            CV.INFO -> InfoView(cham)
            CV.IDENTIFY -> IdentifyView(cham)
            CV.DUMP -> DumpView(cham)
            CV.SLOTS -> SlotsView(cham)
            CV.EMULATE -> EmulateView(cham)
            CV.CLONE -> CloneView(cham)
            CV.ATTACK -> AttackView(cham)
            CV.EMV -> EmvView(cham)
            CV.TESTSET -> TestsetView(cham)
            CV.LFEMU -> LfEmuView(cham)
            CV.RFUZZ -> RfuzzView(cham)
        }
    }
}

@Composable private fun MenuView(open: (CV) -> Unit) {
    val items = listOf(
        Triple(CV.INFO, "Device info", "firmware · battery · mode"),
        Triple(CV.IDENTIFY, "Identify / Read all", "sweep every HF + LF chip on a card/fob"),
        Triple(CV.DUMP, "Dump MIFARE", "dictionary keys → read all blocks"),
        Triple(CV.SLOTS, "Slots", "8 slots · activate · tag type"),
        Triple(CV.EMULATE, "Emulate", "load a read card into a slot"),
        Triple(CV.CLONE, "Clone / Write", "EM410x→T5577 · UID→magic · gen1a/gen2"),
        Triple(CV.ATTACK, "Attacks", "dictionary · mfkey32 · darkside"),
        Triple(CV.EMV, "EMV bank card", "read PAN · expiry · AIDs · txn log"),
        Triple(CV.TESTSET, "Load test set", "program 8 slots with sample tags"),
        Triple(CV.LFEMU, "LF EM4100 emulate", "present an EM410x ID to a reader"),
        Triple(CV.RFUZZ, "Reader fuzz", "malformed emulated cards"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((v, title, sub) in items) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Fox.surface)
                    .border(1.dp, Fox.line, RoundedCornerShape(14.dp)).clickable { open(v) }
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Fox.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(sub, color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = Fox.faint, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable private fun InfoView(cham: ChameleonBle) {
    var fw by remember { mutableStateOf("…"); }; var model by remember { mutableStateOf("…") }
    var mode by remember { mutableStateOf("…") }; var batt by remember { mutableStateOf("…") }; var git by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        cham.command(ChameleonProto.GET_DEVICE_MODEL) { st, d -> if (ok(st) && d != null && d.isNotEmpty()) model = if (d[0].toInt() == 0) "Ultra" else "Lite" }
        cham.command(ChameleonProto.GET_APP_VERSION) { st, d -> if (ok(st) && d != null && d.size >= 2) fw = "v${d[0].toInt() and 0xFF}.${d[1].toInt() and 0xFF}" }
        cham.command(ChameleonProto.GET_DEVICE_MODE) { st, d -> if (ok(st) && d != null && d.isNotEmpty()) mode = if (d[0].toInt() != 0) "reader" else "emulator" }
        cham.command(ChameleonProto.GET_BATTERY_INFO) { st, d -> if (ok(st) && d != null) ChameleonProto.parseBattery(d)?.let { batt = "${it.mv} mV · ${it.pct}%" } }
        cham.command(ChameleonProto.GET_GIT_VERSION) { st, d -> if (ok(st) && d != null && d.isNotEmpty()) git = String(d).trim() }
    }
    FoxCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Device")
            InfoRow("Model", model); InfoRow("Firmware", fw); InfoRow("Mode", mode); InfoRow("Battery", batt)
            if (git.isNotEmpty()) InfoRow("Build", git)
        }
    }
}

@Composable private fun IdentifyView(cham: ChameleonBle) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<String>() }
    fun run() { if (busy) return; lines.clear(); busy = true; scope.launch { sweepFob(cham, { lines.add(it) }, { busy = false }) } }
    LaunchedEffect(Unit) { run() }
    FoxCard(border = Fox.amber.copy(alpha = 0.42f)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Identify / Read all", color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Pill(if (busy) "Scanning" else "Done", if (busy) Fox.warn else Fox.live, if (busy) Fox.amberDim else Fox.liveDim, dot = busy)
            }
            Text("Present a card or key fob to the ChameleonUltra. It sweeps every HF + LF chip type it supports.",
                color = Fox.muted, fontFamily = FoxMono, fontSize = 10.sp, lineHeight = 14.sp)
            if (lines.isEmpty() && !busy) Text("Nothing found. 125 kHz crypto immobilizers (Hitag2/Megamos) and the Sub-GHz remote are outside this device.",
                color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
            for (l in lines) Text(l, color = Fox.amber, fontFamily = FoxMono, fontSize = 12.sp, lineHeight = 16.sp)
            ActionButton(if (busy) "SCANNING…" else "SCAN AGAIN", enabled = !busy) { run() }
        }
    }
}

@Composable private fun DumpView(cham: ChameleonBle) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Present a MIFARE Classic card, then Dump.") }
    val blocks = remember { mutableStateListOf<String>() }
    FoxCard(border = Fox.amber.copy(alpha = 0.42f)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dump MIFARE Classic", color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f)); if (busy) Pill("Working", Fox.warn, Fox.amberDim, dot = true)
            }
            Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
            for (b in blocks) Text(b, color = Fox.fg, fontFamily = FoxMono, fontSize = 11.sp)
            ActionButton(if (busy) "DUMPING…" else "DUMP", enabled = !busy) {
                blocks.clear(); busy = true; status = "Scanning…"
                scope.launch { dumpMifare(cham, { blocks.add(it) }, { status = it }, { busy = false }) }
            }
        }
    }
}

@Composable private fun SlotsView(cham: ChameleonBle) {
    var active by remember { mutableStateOf(-1) }
    var hf by remember { mutableStateOf<IntArray?>(null) }; var lf by remember { mutableStateOf<IntArray?>(null) }
    fun reload() {
        cham.command(ChameleonProto.GET_ACTIVE_SLOT) { st, d -> if (ok(st) && d != null && d.isNotEmpty()) active = d[0].toInt() and 0xFF }
        cham.command(ChameleonProto.GET_SLOT_INFO) { st, d -> if (ok(st) && d != null) ChameleonProto.parseSlotInfo(d)?.let { hf = it.first; lf = it.second } }
    }
    LaunchedEffect(Unit) { reload() }
    FoxCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Slots · tap to activate")
            for (i in 0 until 8) {
                val isA = i == active
                val h = hf?.getOrNull(i)?.takeIf { it != 0 }?.let { ChameleonProto.tagName(it) }
                val l = lf?.getOrNull(i)?.takeIf { it != 0 }?.let { ChameleonProto.tagName(it) }
                val sub = listOfNotNull(h, l).joinToString(" · ").ifEmpty { "empty" }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (isA) Fox.amberDim else Fox.surface2)
                        .border(1.dp, if (isA) Fox.amber.copy(alpha = 0.5f) else Fox.line, RoundedCornerShape(10.dp))
                        .clickable { cham.command(ChameleonProto.SET_ACTIVE_SLOT, byteArrayOf(i.toByte())) { st, _ -> if (ok(st)) active = i } }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", color = if (isA) Fox.amber else Fox.muted, fontFamily = FoxMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(sub, color = if (sub == "empty") Fox.faint else Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    if (isA) Text("ACTIVE", color = Fox.amber, fontFamily = FoxMono, fontSize = 9.sp, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable private fun EmulateView(cham: ChameleonBle) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Read a card (Identify) first, then load it into the active slot to emulate it.") }
    var busy by remember { mutableStateOf(false) }
    val read = ChamSession.lastRead
    FoxCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("Emulate")
            if (read == null) Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
            else {
                Text("Captured: ${read.summary}", color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
                Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
                ActionButton(if (busy) "LOADING…" else "LOAD INTO ACTIVE SLOT", enabled = !busy) {
                    busy = true; status = "Programming active slot…"
                    scope.launch { emulate(cham, read, { status = it }, { busy = false }) }
                }
            }
        }
    }
}

@Composable private fun CloneView(cham: ChameleonBle) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Read a card first. EM410x clones to a blank T5577; a MIFARE UID writes to a gen1a magic card.") }
    var busy by remember { mutableStateOf(false) }
    val read = ChamSession.lastRead
    FoxCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("Clone / Write")
            Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
            val em = read?.em410x
            if (em != null) ActionButton(if (busy) "WRITING…" else "EM410x → T5577", enabled = !busy) {
                busy = true; status = "Present a blank T5577…"
                cham.command(ChameleonProto.EM410X_WRITE_T55XX,
                    ChameleonProto.em410xWriteT55xx(em, ChameleonProto.CH_T55_NEW, ChameleonProto.CH_T55_OLD), 4000) { st, _ ->
                    status = if (st == ChameleonProto.STATUS_SUCCESS || st == ChameleonProto.STATUS_LF_OK)
                        "Wrote EM410x ${ChameleonProto.hex(em)} to T5577." else "Write failed (is a T5577 present?)"; busy = false
                }
            }
            if (read?.hfUid != null) ActionButton(if (busy) "WRITING…" else "UID → gen1a magic", enabled = !busy) {
                busy = true; status = "Present a gen1a magic card…"
                scope.launch { writeGen1aUid(cham, read, { status = it }, { busy = false }) }
            }
            ActionButton("Active slot magic: cycle off→gen1a→gen2", enabled = !busy) {
                val next = (ChamSession.magic + 1) % 3; ChamSession.magic = next
                cham.command(if (next == 1) ChameleonProto.MF1_SET_GEN1A_MODE else ChameleonProto.MF1_SET_GEN2_MODE, byteArrayOf(if (next == 0) 0 else 1)) { st, _ ->
                    status = if (ok(st)) "Active slot magic = ${listOf("off","gen1a","gen2")[next]}" else "magic set failed (MIFARE slot?)"
                }
            }
        }
    }
}

@Composable private fun AttackView(cham: ChameleonBle) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Present a MIFARE Classic card.") }
    var busy by remember { mutableStateOf(false) }
    val keys = remember { mutableStateListOf<String>() }
    FoxCard(border = Fox.violet.copy(alpha = 0.42f)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Key recovery"); Spacer(Modifier.weight(1f)); if (busy) Pill("Working", Fox.warn, Fox.amberDim, dot = true)
            }
            Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
            for (k in keys) Text(k, color = Fox.live, fontFamily = FoxMono, fontSize = 12.sp)
            ActionButton("Dictionary (check common keys)", enabled = !busy) {
                keys.clear(); busy = true; status = "Checking keys…"
                scope.launch { dictAttack(cham, { keys.add(it) }, { status = it }, { busy = false }) }
            }
            ActionButton("Darkside (recover a key)", enabled = !busy) {
                keys.clear(); busy = true; status = "Darkside acquire…"
                scope.launch { darksideAttack(cham, { keys.add(it) }, { status = it }, { busy = false }) }
            }
            ActionButton("Nested — acquire nonces (weak PRNG)", enabled = !busy) {
                keys.clear(); busy = true; status = "Nested acquire…"
                scope.launch { nestedAcquire(cham, { keys.add(it) }, { status = it }, { busy = false }) }
            }
            ActionButton("Static-nested — acquire (static PRNG)", enabled = !busy) {
                keys.clear(); busy = true; status = "Static-nested acquire…"
                scope.launch { staticNestedAcquire(cham, { keys.add(it) }, { status = it }, { busy = false }) }
            }
            ActionButton("Hardnested — acquire (hard PRNG)", enabled = !busy) {
                keys.clear(); busy = true; status = "Hardnested acquire…"
                scope.launch { hardnestedAcquire(cham, { keys.add(it) }, { status = it }, { busy = false }) }
            }
            ActionButton("Enc-nested — Fudan FM11RF08S backdoor", enabled = !busy) {
                keys.clear(); busy = true; status = "Enc-nested acquire…"
                scope.launch { encNestedAcquire(cham, { keys.add(it) }, { status = it }, { busy = false }) }
            }
            Spacer(Modifier.height(2.dp))
            Eyebrow("mfkey32 — emulate + capture reader auths")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { ActionButton("1· ARM", enabled = !busy) { keys.clear(); busy = true; scope.launch { mfkey32Arm(cham, { status = it }, { busy = false }) } } }
                Box(Modifier.weight(1f)) { ActionButton("2· COLLECT+CRACK", enabled = !busy) { busy = true; status = "Collecting…"; scope.launch { mfkey32Collect(cham, { keys.add(it) }, { status = it }, { busy = false }) } } }
            }
            Spacer(Modifier.height(2.dp))
            Eyebrow("NTAG password capture")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { ActionButton("1· ARM", enabled = !busy) { keys.clear(); busy = true; scope.launch { ntagPwdArm(cham, { status = it }, { busy = false }) } } }
                Box(Modifier.weight(1f)) { ActionButton("2· COLLECT", enabled = !busy) { busy = true; status = "Collecting…"; scope.launch { ntagPwdCollect(cham, { keys.add(it) }, { status = it }, { busy = false }) } } }
            }
            ActionButton("HF sniff (5 s reader↔card trace)", enabled = !busy) { busy = true; status = "Sniffing…"; scope.launch { hfSniff(cham, { status = it }, { busy = false }) } }
            Text("Nonces, keys, PWDs and traces are saved under Android/data/com.foxfw.companion/files/chameleon/.",
                color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

@Composable private fun TestsetView(cham: ChameleonBle) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Programs slots 1–8 with sample tag types (MF 1K/2K/4K/Mini, NTAG215/213, Ultralight, EM410x) and saves to flash.") }
    val lines = remember { mutableStateListOf<String>() }
    FoxCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Load test set")
            Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
            for (l in lines) Text(l, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
            ActionButton(if (busy) "LOADING…" else "LOAD 8 SLOTS", enabled = !busy) {
                lines.clear(); busy = true; status = "Programming…"
                scope.launch { loadTestset(cham, { lines.add(it) }, { status = it }, { busy = false }) }
            }
        }
    }
}

@Composable private fun LfEmuView(cham: ChameleonBle) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Enter a 10-hex-digit EM410x ID (5 bytes) and emulate it on the active slot.") }
    var idHex by remember { mutableStateOf("0102030405") }
    FoxCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("LF EM4100 emulate")
            androidx.compose.material3.OutlinedTextField(
                value = idHex, onValueChange = { idHex = it.filter { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' }.take(10) },
                singleLine = true, label = { Text("EM410x ID (hex)", color = Fox.muted, fontFamily = FoxMono) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FoxMono, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Fox.fg, unfocusedTextColor = Fox.fg, focusedBorderColor = Fox.amber,
                    unfocusedBorderColor = Fox.line, cursorColor = Fox.amber, focusedContainerColor = Fox.surface, unfocusedContainerColor = Fox.surface),
            )
            Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
            ActionButton(if (busy) "APPLYING…" else "EMULATE", enabled = !busy && idHex.length == 10) {
                val id = ByteArray(5) { (idHex.substring(it * 2, it * 2 + 2)).toInt(16).toByte() }
                busy = true; status = "Applying…"; scope.launch { lfEmulateEm410x(cham, id, { status = it }, { busy = false }) }
            }
        }
    }
}

@Composable private fun RfuzzView(cham: ChameleonBle) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Program a malformed emulated card on the active slot, then present it to a reader under test.") }
    FoxCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Reader fuzz")
            Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
            FUZZ_CASES.forEachIndexed { idx, fc ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Fox.surface2)
                        .border(1.dp, Fox.line, RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) { busy = true; status = "Applying ${fc.label}…"; scope.launch { rfuzzApply(cham, idx, { status = it }, { busy = false }) } }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text(fc.label, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp) }
            }
        }
    }
}

@Composable private fun EmvView(cham: ChameleonBle) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Present a contactless bank card, then Read.") }
    var raw by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<String>() }
    FoxCard(border = Fox.amber.copy(alpha = 0.42f)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("EMV bank card", color = Fox.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f)); if (busy) Pill("Reading", Fox.warn, Fox.amberDim, dot = true)
            }
            Text(status, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)
            val show = if (raw) (ChamSession.lastEmv?.let { EmvCore.rawLines(it) } ?: emptyList()) else lines
            for (l in show) Text(l, color = Fox.amber, fontFamily = FoxMono, fontSize = 12.sp, lineHeight = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    ActionButton(if (busy) "READING…" else "READ", enabled = !busy) {
                        lines.clear(); busy = true; raw = false; status = "Scanning…"
                        scope.launch { emvRead(cham, { lines.add(it) }, { status = it }, { busy = false }) }
                    }
                }
                if (ChamSession.lastEmv != null) Box(Modifier.weight(1f)) {
                    ActionButton(if (raw) "SUMMARY" else "RAW TLV", enabled = !busy) { raw = !raw }
                }
            }
            Text("Plaintext EMV fields only (PAN, expiry, cardholder, AIDs, counters, offline log). No PIN, no keys, no CVV — a contactless card never exposes those.",
                color = Fox.faint, fontFamily = FoxMono, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

// ---- shared small UI helpers ----
@Composable private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp); Spacer(Modifier.weight(1f))
        Text(v, color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
    }
}

@Composable private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fox.amberDim)
            .border(1.dp, Fox.amber.copy(alpha = if (enabled) 0.5f else 0.2f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }.padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Fox.amber else Fox.faint, fontFamily = FoxMono, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

private fun ok(st: Int) = st == ChameleonProto.STATUS_SUCCESS
