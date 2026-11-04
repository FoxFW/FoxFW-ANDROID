package com.foxfw.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

@Composable
fun TagMakerScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val flipperUp = FoxApplication.instance.ble.conn == Conn.CONNECTED
    val chamUp = FoxApplication.instance.chameleon.conn == Conn.CONNECTED

    var type by remember { mutableStateOf(TagType.NTAG215) }
    var backend by remember { mutableStateOf(Backend.AUTO) }
    var status by remember { mutableStateOf("Pick a type, fill the fields, then Emulate / Write / Clone.") }
    var busy by remember { mutableStateOf(false) }
    var readAnalysis by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // editor fields
    var uidHex by remember { mutableStateOf("04 11 22 33 44 55 66") }
    var em4100 by remember { mutableStateOf("12 34 56 78 9A") }
    var hidFacility by remember { mutableStateOf("12") }
    var hidCard by remember { mutableStateOf("3456") }
    var ndefKind by remember { mutableStateOf("URL") }
    var ndefValue by remember { mutableStateOf("https://flipperzero.one") }
    var contactName by remember { mutableStateOf("Jane Doe") }
    var contactPhone by remember { mutableStateOf("+15551234567") }
    var contactEmail by remember { mutableStateOf("jane@example.com") }

    fun buildTag(): Tag? = try {
        when (type) {
            TagType.EM4100 -> LfTag.em4100(em4100)
            TagType.HID_PROX -> LfTag.hidH10301(
                hidFacility.trim().toInt(), hidCard.trim().toInt(),
            )
            TagType.NTAG213, TagType.NTAG215, TagType.NTAG216, TagType.MIFARE_ULTRALIGHT -> {
                val uid = uidHex.toBytes()
                require(uid.size == 7) { "NTAG/Ultralight UID must be 7 bytes (14 hex)" }
                val msg = when (ndefKind) {
                    "URL" -> NdefBuilder.uriRecord(ndefValue)
                    "Contact" -> NdefBuilder.vcardRecord(contactName, contactPhone, contactEmail)
                    else -> NdefBuilder.textRecord(ndefValue)
                }
                HfTag(type, uid, byteArrayOf(0x00, 0x44), 0x00, NdefBuilder.ntagPages(type, uid, msg))
            }
            TagType.MIFARE_CLASSIC_1K, TagType.MIFARE_CLASSIC_4K -> {
                val uid = uidHex.toBytes(); require(uid.size == 4 || uid.size == 7) { "MIFARE UID = 4 or 7 bytes" }
                HfTag(type, uid, byteArrayOf(0x00, 0x04), 0x08)
            }
            TagType.ISO14443_3A -> {
                val uid = uidHex.toBytes(); require(uid.size == 4 || uid.size == 7) { "UID = 4 or 7 bytes" }
                HfTag(type, uid, byteArrayOf(0x00, 0x44), 0x00)
            }
        }
    } catch (e: Throwable) { status = "⚠ ${e.message}"; null }

    fun backendFor(op: Op, tag: Tag): TagBackend? {
        // Read live connection state at call time (not a stale recomposition snapshot).
        val fUp = FoxApplication.instance.ble.conn == Conn.CONNECTED
        val cUp = FoxApplication.instance.chameleon.conn == Conn.CONNECTED
        return when (backend) {
            Backend.FLIPPER -> if (fUp && op in TagCaps.flipper(tag.type)) FlipperTagBackend else null
            Backend.CHAMELEON -> if (cUp && op in TagCaps.chameleon(tag.type)) ChameleonTagBackend else null
            Backend.AUTO -> when (TagCaps.pick(op, tag.type, fUp, cUp)) {
                Backend.FLIPPER -> FlipperTagBackend; Backend.CHAMELEON -> ChameleonTagBackend; else -> null
            }
        }
    }

    fun run(op: Op) {
        val tag = buildTag() ?: return
        val be = backendFor(op, tag) ?: run {
            val verb = op.name.lowercase()
            val capF = op in TagCaps.flipper(tag.type)
            val capC = op in TagCaps.chameleon(tag.type)
            status = if (!capF && !capC) {
                "No device can $verb a ${type.label}."
            } else {
                val who = listOfNotNull(
                    if (capF) "the Flipper (Device tab)" else null,
                    if (capC) "the ChameleonUltra (Tools → ChameleonUltra)" else null,
                ).joinToString(" or ")
                "Connect $who to $verb a ${type.label}."
            }
            return
        }
        busy = true; status = "${op.name.lowercase().replaceFirstChar { it.uppercase() }} via ${be.name}…"
        readAnalysis = emptyList()
        scope.launch {
            val res = when (op) {
                Op.EMULATE -> be.emulate(tag, 30000)
                Op.WRITE -> be.writeToBlank(tag)
                Op.READ -> be.read(type)
            }
            if (op == Op.READ && res.tag != null) {
                (res.tag as? LfTag)?.let { em4100 = it.data.toHex() }
                (res.tag as? HfTag)?.let { uidHex = it.uid.toHex() }
                (res.tag as? HfTag)?.let { readAnalysis = NfcParse.analyze(it) }
            }
            status = (if (res.ok) "✓ " else "✗ ") + res.message
            busy = false
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Tag Maker", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Eyebrow("NFC / RFID · both devices") }
            Spacer(Modifier.weight(1f))
            Pill(if (busy) "Busy" else "Ready", if (busy) Fox.amber else Fox.live, if (busy) Fox.amberDim else Fox.liveDim, dot = true)
        }

        // type picker
        FoxCard { Column {
            Eyebrow("Type"); Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagType.values().forEach { t -> MChip(t.label, t == type) { type = t } }
            }
        } }

        // editor
        FoxCard { Column {
            Eyebrow("Fields"); Spacer(Modifier.height(8.dp))
            when (type) {
                TagType.EM4100 -> {
                    Field("EM4100 ID (5 bytes)", em4100) { em4100 = it }
                    MChipRow("Randomize") { em4100 = ByteArray(5) { Random.nextInt(256).toByte() }.toHex() }
                }
                TagType.HID_PROX -> {
                    Field("Facility (0–255)", hidFacility) { hidFacility = it }
                    Spacer(Modifier.height(6.dp))
                    Field("Card (0–65535)", hidCard) { hidCard = it }
                }
                TagType.NTAG213, TagType.NTAG215, TagType.NTAG216, TagType.MIFARE_ULTRALIGHT -> {
                    Field("UID (7 bytes)", uidHex) { uidHex = it }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MChip("URL", ndefKind == "URL") { ndefKind = "URL" }
                        MChip("Text", ndefKind == "Text") { ndefKind = "Text" }
                        MChip("Contact", ndefKind == "Contact") { ndefKind = "Contact" }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (ndefKind == "Contact") {
                        Field("Name", contactName) { contactName = it }
                        Spacer(Modifier.height(6.dp))
                        Field("Phone", contactPhone) { contactPhone = it }
                        Spacer(Modifier.height(6.dp))
                        Field("Email", contactEmail) { contactEmail = it }
                    } else {
                        Field(if (ndefKind == "URL") "URL" else "Text", ndefValue) { ndefValue = it }
                    }
                    MChipRow("Random UID") { uidHex = (byteArrayOf(0x04) + ByteArray(6) { Random.nextInt(256).toByte() }).toHex() }
                }
                else -> {
                    Field("UID (4 or 7 bytes)", uidHex) { uidHex = it }
                    MChipRow("Random UID") { uidHex = ByteArray(4) { Random.nextInt(256).toByte() }.toHex() }
                }
            }
        } }

        // backend
        FoxCard { Column {
            Eyebrow("Backend"); Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MChip("Auto", backend == Backend.AUTO) { backend = Backend.AUTO }
                MChip("Flipper${if (!flipperUp) " ⌀" else ""}", backend == Backend.FLIPPER) { backend = Backend.FLIPPER }
                MChip("Chameleon${if (!chamUp) " ⌀" else ""}", backend == Backend.CHAMELEON) { backend = Backend.CHAMELEON }
            }
        } }

        // capability indicator — what each device can do with the selected type
        FoxCard { Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("${type.label} — who can do what")
                Spacer(Modifier.weight(1f))
                Text("⌀ = not connected", color = Fox.faint, fontFamily = FoxMono, fontSize = 8.sp)
            }
            Spacer(Modifier.height(8.dp))
            CapLine("Flipper", TagCaps.flipper(type), flipperUp)
            Spacer(Modifier.height(5.dp))
            CapLine("Chameleon", TagCaps.chameleon(type), chamUp)
        } }

        // actions (greyed by capability)
        val tagType = type
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val canEmu = (TagCaps.flipper(tagType) + TagCaps.chameleon(tagType)).contains(Op.EMULATE)
            val canWrite = (TagCaps.flipper(tagType) + TagCaps.chameleon(tagType)).contains(Op.WRITE)
            BtnWide("Emulate", canEmu && !busy, Modifier.weight(1f)) { run(Op.EMULATE) }
            BtnWide("Write", canWrite && !busy, Modifier.weight(1f)) { run(Op.WRITE) }
            BtnWide("Clone", !busy, Modifier.weight(1f)) { run(Op.READ) }
        }

        // preview
        buildTag()?.let { t ->
            FoxCard { Column {
                Eyebrow("Preview"); Spacer(Modifier.height(6.dp))
                Text(t.toFlipperFile().take(600), color = Fox.muted, fontFamily = FoxMono, fontSize = 9.sp, lineHeight = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BtnWide("Save to library", true, Modifier.weight(1f)) {
                        val ok = TagLibrary.save(t); status = if (ok) "Saved to library." else "Save failed."
                    }
                    BtnWide("Self-test", !busy, Modifier.weight(1f)) {
                        val round = TagCodec.parseFlipperFile(t.toFlipperFile())
                        status = if (round != null && round == t) "✓ Self-test: round-trip OK." else "✗ Self-test: round-trip mismatch."
                    }
                }
            } }
        }

        // library
        val lib = TagLibrary.list()
        if (lib.isNotEmpty()) FoxCard { Column {
            Eyebrow("Library — ${lib.size}"); Spacer(Modifier.height(6.dp))
            lib.take(12).forEach { name ->
                Row(Modifier.fillMaxWidth().clickable {
                    TagLibrary.load(name)?.let { t ->
                        when (t) { is LfTag -> { type = t.type; em4100 = t.data.toHex() }
                            is HfTag -> { type = t.type; uidHex = t.uid.toHex() } }
                        status = "Loaded $name."
                    }
                }.padding(vertical = 5.dp)) {
                    Text(name, color = Fox.fg, fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("load", color = Fox.amber, fontFamily = FoxMono, fontSize = 10.sp)
                }
            }
        } }

        FoxCard { Text(status, color = if (status.startsWith("✗") || status.startsWith("⚠")) Fox.amber else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp) }
        if (readAnalysis.isNotEmpty()) FoxCard { Column {
            Eyebrow("Read analysis")
            Spacer(Modifier.height(6.dp))
            readAnalysis.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(k, color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp, modifier = Modifier.width(88.dp))
                    Text(v, color = if (k.startsWith("NDEF")) Fox.amber else Fox.fg, fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
            }
        } }
    }
}

@Composable
private fun CapLine(name: String, caps: Set<Op>, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp))
            .background(if (connected) Fox.live else Fox.faint))
        Spacer(Modifier.width(7.dp))
        Text(name, color = if (connected) Fox.fg else Fox.muted, fontSize = 12.sp, modifier = Modifier.width(88.dp))
        CapBadge("EMU", Op.EMULATE in caps)
        Spacer(Modifier.width(5.dp))
        CapBadge("WRITE", Op.WRITE in caps)
        Spacer(Modifier.width(5.dp))
        CapBadge("READ", Op.READ in caps)
    }
}

@Composable
private fun CapBadge(label: String, on: Boolean) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp))
            .background(if (on) Fox.live.copy(alpha = 0.16f) else Fox.surface2)
            .border(1.dp, if (on) Fox.live.copy(alpha = 0.5f) else Fox.line, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(label, color = if (on) Fox.live else Fox.faint, fontFamily = FoxMono, fontSize = 9.sp)
    }
}

private fun String.toBytes(): ByteArray =
    trim().split(Regex("[\\s:]+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label, fontSize = 11.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp))
        .background(if (selected) Fox.violet.copy(alpha = 0.25f) else Fox.surface2)
        .border(1.dp, if (selected) Fox.violet else Fox.line, RoundedCornerShape(8.dp))
        .clickable { onClick() }.padding(horizontal = 11.dp, vertical = 7.dp)) {
        Text(text, color = if (selected) Fox.fg else Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
    }
}

@Composable
private fun MChipRow(text: String, onClick: () -> Unit) {
    Spacer(Modifier.height(6.dp))
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Fox.surface2).border(1.dp, Fox.line, RoundedCornerShape(8.dp))
        .clickable { onClick() }.padding(horizontal = 11.dp, vertical = 7.dp)) {
        Text(text, color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp)
    }
}

@Composable
private fun BtnWide(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(11.dp))
        .background(if (enabled) Fox.surface2 else Fox.surface2.copy(alpha = 0.4f))
        .border(1.dp, Fox.line, RoundedCornerShape(11.dp))
        .clickable(enabled = enabled) { onClick() }.padding(vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Fox.fg else Fox.faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
