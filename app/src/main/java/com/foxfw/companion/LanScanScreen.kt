package com.foxfw.companion

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** On-phone LAN recon: find the subnet you're on, connect-scan every host for common services. */
@Composable
fun LanScanScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val self = remember { localIpv4(ctx) }           // Pair(ip, prefix) or null
    val hosts = remember { mutableStateListOf<LanScan.Host>() }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var status by remember {
        mutableStateOf(
            if (self == null) "Join a WiFi network to scan its LAN." else "Ready — ${self.first}/${self.second}.",
        )
    }

    fun scan() {
        val s = self ?: run { status = "Not on a WiFi network."; return }
        if (busy) return
        hosts.clear(); busy = true
        val targets = LanScan.hostsForCidr(s.first, s.second)
        status = "Scanning ${targets.size} hosts on /${s.second}…"
        scope.launch {
            withContext(Dispatchers.Default) {
                LanScan.scan(
                    targets,
                    onProgress = { d, t -> progress = d to t },
                    onHost = { h -> scope.launch { if (hosts.none { it.ip == h.ip }) hosts.add(h) } },
                )
            }
            hosts.sortBy { LanScan.ipToInt(it.ip) ?: 0 }
            busy = false
            status = "Found ${hosts.size} live host(s) of ${targets.size} scanned."
        }
    }

    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
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
                Text("LAN Scanner", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow("network recon · on-phone")
            }
            Spacer(Modifier.weight(1f))
            if (busy) Pill("${progress.first}/${progress.second}", Fox.amber, Fox.amberDim)
            else Pill("${hosts.size} hosts", Fox.live, Fox.liveDim)
        }

        FoxCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    self?.let { "This device: ${it.first}/${it.second}" } ?: "No IPv4 WiFi address.",
                    color = Fox.fg, fontFamily = FoxMono, fontSize = 12.sp,
                )
                MBtnL(if (busy) "Scanning…" else "⚡ Scan LAN", accent = true, enabled = !busy && self != null) { scan() }
                Text(status, color = Fox.faint, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(hosts, key = { it.ip }) { h ->
                FoxCard {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.ip, color = Fox.live, fontFamily = FoxMono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${h.openPorts.size} open", color = Fox.muted, fontSize = 11.sp)
                        }
                        h.name?.let { Text(it, color = Fox.muted, fontSize = 10.5.sp, fontFamily = FoxMono) }
                        Text(
                            h.openPorts.joinToString("  ") { "${it}·${LanScan.serviceName(it)}" },
                            color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

/** The phone's IPv4 address + CIDR prefix on the active (WiFi) network, or null. */
private fun localIpv4(ctx: Context): Pair<String, Int>? {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val net = cm.activeNetwork ?: return null
    val lp = cm.getLinkProperties(net) ?: return null
    val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
    val ip = la.address.hostAddress ?: return null
    return ip to la.prefixLength
}

@Composable
private fun MBtnL(text: String, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
            .background(if (!enabled) Fox.surface2.copy(alpha = 0.4f) else if (accent) Fox.amberDim else Fox.surface2)
            .border(1.dp, if (accent && enabled) Fox.amber else Fox.line, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled) { onClick() }.padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (!enabled) Fox.faint else if (accent) Fox.amber else Fox.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
