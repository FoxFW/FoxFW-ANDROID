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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NFC MFKey — recover MIFARE Classic keys from the nonces the Flipper collected with "Detect Reader".
 * Reads /ext/nfc/.mfkey32.log off the connected Flipper, runs the bundled native mfkey32 solver
 * (ChamCrypto, already used by the Chameleon feature) on each two-nonce entry, and can append the
 * recovered keys to the user dictionary (/ext/nfc/assets/mf_classic_dict_user.nfc). Mirrors Flipper
 * Lab's NFC MFKey tool.
 */
private data class Nonce(
    val sector: Int, val keyType: String,
    val uid: Int, val nt0: Int, val nr0: Int, val ar0: Int, val nt1: Int, val nr1: Int, val ar1: Int,
)
private data class RecoveredKey(val sector: Int, val keyType: String, val keyHex: String)

private const val MFKEY_LOG = "/ext/nfc/.mfkey32.log"
private const val USER_DICT = "/ext/nfc/assets/mf_classic_dict_user.nfc"

private fun hex32(s: String?): Int? = s?.trim()?.removePrefix("0x")?.toLongOrNull(16)?.toInt()

private fun parseNonces(text: String): List<Nonce> {
    val out = ArrayList<Nonce>()
    for (raw in text.split("\n")) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val t = line.split(Regex("\\s+"))
        fun after(k: String): String? { val i = t.indexOf(k); return if (i >= 0 && i + 1 < t.size) t[i + 1] else null }
        val uid = hex32(after("cuid")) ?: continue
        val nt0 = hex32(after("nt0")) ?: continue
        val nr0 = hex32(after("nr0")) ?: continue
        val ar0 = hex32(after("ar0")) ?: continue
        val nt1 = hex32(after("nt1")) ?: continue
        val nr1 = hex32(after("nr1")) ?: continue
        val ar1 = hex32(after("ar1")) ?: continue
        out.add(Nonce(after("Sector")?.toIntOrNull() ?: -1, after("key") ?: "?", uid, nt0, nr0, ar0, nt1, nr1, ar1))
    }
    return out
}

@Composable
fun NfcMfkeyScreen(fle: FlipperBle, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connected = fle.conn == Conn.CONNECTED

    val nonces = remember { mutableStateListOf<Nonce>() }
    val results = remember { mutableStateListOf<RecoveredKey>() }
    var recovering by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf(if (connected) "Loading nonces from the Flipper…" else "Connect the Flipper (Device tab) first.") }
    var busyDict by remember { mutableStateOf(false) }

    // manual single-nonce entry (Flipper Lab's "Enter the nonce manually")
    val m = remember { mutableStateListOf("", "", "", "", "", "", "") } // cuid, nt0, nr0, ar0, nt1, nr1, ar1
    var manualKey by remember { mutableStateOf<String?>(null) }
    var manualBusy by remember { mutableStateOf(false) }
    fun runManual() {
        val v = m.map { hex32(it) }
        if (v.any { it == null }) { status = "Enter all 7 values as hex (e.g. 2a234f80)."; return }
        if (!ChamCrypto.available) { status = "The native key solver isn't available on this device."; return }
        manualBusy = true; manualKey = null; status = "Running mfkey32…"
        scope.launch(Dispatchers.Default) {
            val key = ChamCrypto.mfkey32(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!, v[5]!!, v[6]!!)
            withContext(Dispatchers.Main) {
                manualBusy = false
                if (key >= 0) { manualKey = "%012X".format(key and 0xFFFFFFFFFFFFL); status = "Recovered key." }
                else { status = "No key found for those nonces." }
            }
        }
    }

    fun loadLog() {
        if (!connected) { status = "Connect the Flipper first."; return }
        status = "Reading $MFKEY_LOG…"
        scope.launch {
            val bytes = fle.storageRead(MFKEY_LOG)
            nonces.clear(); results.clear()
            if (bytes == null) { status = "No nonce log on the Flipper — run NFC → Detect Reader against a reader first."; return@launch }
            nonces.addAll(parseNonces(bytes.toString(Charsets.UTF_8)))
            status = if (nonces.isEmpty()) "The nonce log is empty — collect some with Detect Reader first." else "${nonces.size} nonce set(s). Tap Recover (≈1–2 min each)."
        }
    }
    LaunchedEffect(connected) { if (connected && nonces.isEmpty()) loadLog() }

    fun recover() {
        if (recovering) { job?.cancel(); recovering = false; status = "Stopped."; return }
        if (!ChamCrypto.available) { status = "The native key solver isn't available on this device."; return }
        if (nonces.isEmpty()) { status = "Nothing to recover — load a nonce log first."; return }
        recovering = true; results.clear()
        job = scope.launch(Dispatchers.Default) {
            var found = 0
            for ((i, n) in nonces.withIndex()) {
                if (!isActive) break
                withContext(Dispatchers.Main) { status = "Recovering ${i + 1}/${nonces.size} — sector ${n.sector} key ${n.keyType} (≈1–2 min)…" }
                val key = ChamCrypto.mfkey32(n.uid, n.nt0, n.nr0, n.ar0, n.nt1, n.nr1, n.ar1)
                if (key >= 0) {
                    val hex = "%012X".format(key and 0xFFFFFFFFFFFFL)
                    found++
                    withContext(Dispatchers.Main) { results.add(RecoveredKey(n.sector, n.keyType, hex)) }
                }
            }
            withContext(Dispatchers.Main) { recovering = false; status = "Done — recovered $found of ${nonces.size}." }
        }
    }

    fun addToDict() {
        if (results.isEmpty() || busyDict) return
        busyDict = true; status = "Updating the user dictionary…"
        scope.launch {
            try {
                val existing = fle.storageRead(USER_DICT)?.toString(Charsets.UTF_8) ?: ""
                val have = existing.split("\n").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toMutableSet()
                val add = results.map { it.keyHex.uppercase() }.distinct().filter { it !in have }
                if (add.isEmpty()) { status = "Those keys are already in the dictionary."; return@launch }
                fle.storageMkdir("/ext/nfc/assets")
                val merged = buildString {
                    append(existing.trimEnd('\n'))
                    if (existing.isNotBlank()) append("\n")
                    append(add.joinToString("\n")); append("\n")
                }.trimStart('\n')
                val ok = fle.storageWrite(USER_DICT, merged.toByteArray(), null)
                status = if (ok) "Added ${add.size} new key(s) to the user dictionary." else "Couldn't write the dictionary (is the Flipper on its desktop?)."
            } catch (e: Exception) { status = "Dictionary update failed: ${e.message}" } finally { busyDict = false }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NFC MFKey", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow("MIFARE CLASSIC · KEY RECOVERY")
            }
            Box(Modifier.width(40.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Fox.line, RoundedCornerShape(9.dp))
                .clickable { loadLog() }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text("⟳", color = Fox.amber, fontSize = 16.sp)
            }
        }

        FoxCard {
            Text("Collect nonces on the Flipper with NFC → Detect Reader (emulate a saved Mifare Classic card, then tap it to a reader). This reads /ext/nfc/.mfkey32.log and recovers the keys on-phone.",
                color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp, lineHeight = 14.sp)
        }

        // nonces + actions
        FoxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("NONCES"); Spacer(Modifier.width(8.dp))
                    Text("${nonces.size}", color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Pill(if (connected) "FLPR" else "offline", if (connected) Fox.live else Fox.muted, if (connected) Fox.liveDim else Fox.line)
                }
                if (nonces.isNotEmpty()) Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    nonces.forEach { n ->
                        Text("sector ${n.sector}  key ${n.keyType}  ·  uid %08X".format(n.uid), color = Fox.muted, fontFamily = FoxMono, fontSize = 10.5.sp)
                    }
                }
                MBtn(if (recovering) "■ Stop" else "⚡ Recover keys", Modifier.fillMaxWidth(), accent = recovering || nonces.isNotEmpty()) { recover() }
            }
        }

        Text(status, color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp, lineHeight = 15.sp)

        // manual nonce entry
        FoxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("ENTER NONCE MANUALLY")
                val labels = listOf("cuid", "nt0", "nr0", "ar0", "nt1", "nr1", "ar1")
                var r = 0
                while (r < 7) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HexField(labels[r], m[r], Modifier.weight(1f)) { m[r] = it }
                        if (r + 1 < 7) HexField(labels[r + 1], m[r + 1], Modifier.weight(1f)) { m[r + 1] = it }
                        else Spacer(Modifier.weight(1f))
                    }
                    r += 2
                }
                MBtn(if (manualBusy) "Running…" else "Run mfkey32", Modifier.fillMaxWidth(), accent = !manualBusy) { if (!manualBusy) runManual() }
                manualKey?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Key", color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp); Spacer(Modifier.width(10.dp))
                        Text(it, color = Fox.live, fontFamily = FoxMono, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // results
        if (results.isNotEmpty()) FoxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("RECOVERED KEYS"); Spacer(Modifier.weight(1f))
                    Text("${results.size}", color = Fox.live, fontFamily = FoxMono, fontSize = 12.sp)
                }
                results.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("S${r.sector} ${r.keyType}", color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                        Text(r.keyHex, color = Fox.live, fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.width(2.dp))
                MBtn(if (busyDict) "…" else "Add to user dictionary", Modifier.fillMaxWidth(), accent = connected && !busyDict) { addToDict() }
            }
        }
    }
}

@Composable
private fun HexField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.lowercase().filter { it.isDigit() || it in 'a'..'f' }.take(8)) },
        label = { Text(label, fontSize = 10.sp) }, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FoxMono, fontSize = 13.sp),
        modifier = modifier,
    )
}
