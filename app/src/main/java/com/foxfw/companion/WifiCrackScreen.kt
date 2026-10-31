package com.foxfw.companion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-phone WPA PMKID handshake cracker. Paste a hashcat-22000 `WPA*01*...` line (from a BFFB/Marauder
 * capture, or JARVIS's hcxpcapngtool), pick a wordlist, and dictionary-crack it on the phone. Runs on
 * [CpuCrackBackend] today; the GPU (OpenCL) backend plugs in behind [CrackBackend] and is picked
 * automatically when available.
 */
@Composable
fun WifiCrackScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    // Pre-fill from a handshake the Marauder screen auto-shipped here, if any.
    var hc by remember { mutableStateOf(CrackInbox.consume() ?: "") }
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var wordlistName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf(0) }
    var startMs by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(0L) }
    var result by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(
            if (hc.isNotBlank()) "Loaded a captured handshake from Marauder — pick a wordlist, crack."
            else "Paste a WPA*01 (PMKID) or WPA*02 (handshake) line, pick a wordlist, crack.",
        )
    }

    // Start on the dependable CPU backend; switch to the GPU only once it has self-validated against
    // the CPU oracle (off the main thread — the probe runs real GPU work).
    var backend: CrackBackend by remember { mutableStateOf<CrackBackend>(CpuCrackBackend) }
    LaunchedEffect(Unit) {
        val best = withContext(Dispatchers.Default) { CrackBackends.best() }
        if (best !== backend) backend = best
    }
    val parsed = remember(hc) { WpaCrack.parsePmkid(hc.trim()) }
    val eapolT = remember(hc) { if (parsed == null) WpaCrack.parseEapol(hc.trim()) else null }
    val activeName = if (eapolT != null) "CPU · EAPOL" else backend.name

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true; status = "Loading wordlist…"
        scope.launch {
            val lines = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { seq ->
                        seq.map { it.trim() }.filter { it.length in 8..63 }.take(8_000_000).toList()
                    } ?: emptyList()
                }.getOrElse { emptyList() }
            }
            words = lines
            wordlistName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "wordlist"
            loading = false
            status = if (lines.isEmpty()) "Couldn't read that wordlist." else "${lines.size} candidates loaded from $wordlistName."
        }
    }

    val pcapPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "Reading capture…"
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.readBytes() }.getOrNull()
            }
            if (bytes == null) { status = "Couldn't read that file."; return@launch }
            val found = withContext(Dispatchers.Default) { Pcap.toHc22000(bytes) }
            val pmkids = found.count { it.startsWith("WPA*01") }
            val eapols = found.count { it.startsWith("WPA*02") }
            // prefer the PMKID (WPA*01) — it's GPU-accelerated; fall back to the 4-way EAPOL (WPA*02, CPU).
            // Both recover the same passphrase, so either works; this just cracks the faster one first.
            val best = found.firstOrNull { it.startsWith("WPA*01") } ?: found.firstOrNull { it.startsWith("WPA*02") }
            if (best == null) {
                status = "No PMKID or EAPOL handshake found in that capture."
            } else {
                hc = best
                val using = if (best.startsWith("WPA*01")) "PMKID (GPU-fast)" else "4-way EAPOL"
                status = "Loaded $pmkids PMKID + $eapols EAPOL from the capture — cracking the $using. Pick a wordlist, crack."
            }
        }
    }

    fun run() {
        if (words.isEmpty()) { status = "Pick a wordlist first."; return }
        val pmkid = parsed
        val eap = eapolT
        result = ""; tested = 0; busy = true; startMs = System.currentTimeMillis(); nowMs = startMs
        when {
            pmkid != null -> {
                status = "Cracking ${pmkid.ssidText} (PMKID) · ${backend.name}…"
                scope.launch {
                    val hit = withContext(Dispatchers.IO) {
                        backend.crackPmkid(pmkid, words) { n -> tested = n; nowMs = System.currentTimeMillis() }
                    }
                    busy = false; nowMs = System.currentTimeMillis(); result = hit ?: ""
                    status = if (hit != null) "Cracked ${pmkid.ssidText}." else "Exhausted ${words.size} candidates — not in this list."
                }
            }
            eap != null -> {
                if (!eap.supported) {
                    busy = false
                    status = "Key version ${eap.keyver} (802.11w / AES-CMAC) isn't supported yet — crack a PMKID instead."
                    return
                }
                status = "Cracking ${eap.ssidText} (4-way EAPOL) · CPU…"
                scope.launch {
                    val hit = withContext(Dispatchers.Default) {
                        WpaCrack.crackEapol(eap, words.asSequence()) { n -> tested = n; nowMs = System.currentTimeMillis() }
                    }
                    busy = false; nowMs = System.currentTimeMillis(); result = hit ?: ""
                    status = if (hit != null) "Cracked ${eap.ssidText}." else "Exhausted ${words.size} candidates — not in this list."
                }
            }
            else -> { busy = false; status = "That isn't a WPA*01 (PMKID) or WPA*02 (handshake) line." }
        }
    }

    val elapsed = ((if (busy) System.currentTimeMillis() else nowMs) - startMs).coerceAtLeast(1)
    val rate = if (startMs == 0L) 0 else (tested * 1000L / elapsed)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Fox.line, RoundedCornerShape(10.dp)).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.ArrowBack, null, tint = Fox.fg, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Handshake Crack", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow("WPA2 PMKID + EAPOL · on-phone")
            }
            Spacer(Modifier.weight(1f))
            Pill(activeName, Fox.amber, Fox.amberDim)
        }

        FoxCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("HC22000 LINE · WPA*01 (PMKID) / WPA*02 (EAPOL)")
                OutlinedTextField(
                    value = hc, onValueChange = { hc = it },
                    placeholder = { Text("WPA*01*… (PMKID)  or  WPA*02*… (4-way handshake)", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FoxMono, fontSize = 11.sp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActBtn("Paste", Modifier.weight(1f)) { clip.getText()?.let { hc = it.text.trim() } }
                    ActBtn("Load .pcap", Modifier.weight(1f)) { pcapPicker.launch(arrayOf("*/*")) }
                    ActBtn("Clear", Modifier.weight(1f)) { hc = "" }
                }
                if (hc.isNotBlank()) {
                    when {
                        parsed != null -> Text(
                            "✓ PMKID · ${parsed.ssidText}  ·  AP ${macOf(parsed.apMac)}  ·  STA ${macOf(parsed.staMac)}",
                            color = Fox.live, fontFamily = FoxMono, fontSize = 11.sp,
                        )
                        eapolT != null -> Text(
                            "✓ EAPOL (keyver ${eapolT.keyver}) · ${eapolT.ssidText}  ·  AP ${macOf(eapolT.apMac)}" +
                                if (!eapolT.supported) "  ·  keyver 3 unsupported" else "",
                            color = if (eapolT.supported) Fox.live else Fox.warn, fontFamily = FoxMono, fontSize = 11.sp,
                        )
                        else -> Text("Not a valid WPA*01 / WPA*02 line.", color = Fox.crit, fontSize = 11.sp)
                    }
                }
                Text("WPA2 only. WPA3-SAE can't be cracked offline — only a WPA3-transition AP's WPA2 PMKID can.",
                    color = Fox.faint, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }

        FoxCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("WORDLIST")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (words.isEmpty()) "No wordlist — pick a .txt" else "$wordlistName · ${words.size} candidates",
                        color = if (words.isEmpty()) Fox.muted else Fox.fg, fontSize = 12.sp,
                        fontFamily = FoxMono, modifier = Modifier.weight(1f),
                    )
                    ActBtn(if (loading) "Loading…" else "Pick") { if (!loading) picker.launch(arrayOf("text/*", "*/*")) }
                }
                Text("Push a wordlist to the phone (e.g. your CrackStation/rockyou) and pick it here.",
                    color = Fox.faint, fontSize = 10.5.sp)
            }
        }

        ActBtn(if (busy) "Cracking…" else "⚡ Crack", accent = true,
            enabled = !busy && (parsed != null || eapolT != null) && words.isNotEmpty()) { run() }

        if (startMs != 0L) {
            FoxCard(border = if (result.isNotEmpty()) Fox.live.copy(alpha = 0.5f) else Fox.line) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (result.isNotEmpty()) {
                        Eyebrow("PASSPHRASE")
                        Text(result, color = Fox.live, fontFamily = FoxMono, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("tested $tested · ${rate}/s", color = Fox.muted, fontFamily = FoxMono, fontSize = 12.sp)
                }
            }
        }

        Text(status, color = Fox.faint, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

private fun macOf(b: ByteArray) = b.joinToString(":") { "%02x".format(it) }

@Composable
private fun ActBtn(text: String, modifier: Modifier = Modifier, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(11.dp))
            .background(if (!enabled) Fox.surface2.copy(alpha = 0.4f) else if (accent) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (accent && enabled) Fox.amber else Fox.line, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled) { onClick() }.padding(vertical = 12.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (!enabled) Fox.faint else if (accent) Fox.amber else Fox.fg,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
