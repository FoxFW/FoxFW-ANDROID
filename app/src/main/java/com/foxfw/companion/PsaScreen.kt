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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.ui.Eyebrow
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.GradientBar
import com.foxfw.companion.ui.Pill

private const val PSA_SPACE = 33554432.0f // 2^25 candidates across both windows

@Composable
fun PsaScreen(onClose: () -> Unit) {
    val fle = FoxApplication.instance.ble
    val connected = fle.conn == Conn.CONNECTED
    val phase = PsaOffload.phase
    val tested = PsaOffload.tested
    val kps = PsaOffload.kps

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("PSA Offload", color = Fox.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Eyebrow("TEA BRUTE · OVER BLE")
            }
            Spacer(Modifier.weight(1f))
            val p = when {
                phase == PsaOffload.Phase.CRACKING -> Triple("Cracking", Fox.amber, Fox.amberDim)
                connected -> Triple("Armed", Fox.live, Fox.liveDim)
                else -> Triple("Offline", Fox.faint, Fox.surface2)
            }
            Pill(p.first, p.second, p.third, dot = true)
        }

        FoxCard(Modifier.fillMaxWidth()) {
            Column {
                Text(
                    if (connected)
                        "Armed. Trigger PSA decrypt on the Flipper (Sub-GHz → open a PSA capture → " +
                            "Decrypt). Because the phone is linked it offloads the ~2²⁵ TEA brute here and " +
                            "the plaintext is sent back automatically."
                    else
                        "Connect the Flipper (Device tab) to serve its PSA decrypt offload. You can still " +
                            "run the self-test below without a Flipper.",
                    color = Fox.muted, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text("Jobs served this session: ${PsaOffload.served}", color = Fox.faint,
                    fontFamily = FoxMono, fontSize = 10.sp)
            }
        }

        if (phase == PsaOffload.Phase.CRACKING) {
            FoxCard(Modifier.fillMaxWidth()) {
                Column {
                    Eyebrow("Cracking")
                    Spacer(Modifier.height(10.dp))
                    GradientBar((tested / PSA_SPACE).coerceIn(0f, 1f))
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("%,d keys".format(tested), color = Fox.fg, fontFamily = FoxMono, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text("%,d keys/s".format(kps), color = Fox.amber, fontFamily = FoxMono, fontSize = 11.sp)
                    }
                }
            }
        }

        if (phase == PsaOffload.Phase.FOUND || phase == PsaOffload.Phase.NOT_FOUND ||
            phase == PsaOffload.Phase.CANCELLED) {
            FoxCard(Modifier.fillMaxWidth()) {
                Column {
                    Eyebrow(
                        when (phase) {
                            PsaOffload.Phase.FOUND -> "Cracked"
                            PsaOffload.Phase.NOT_FOUND -> "No key in window"
                            else -> "Cancelled"
                        },
                        color = if (phase == PsaOffload.Phase.FOUND) Fox.live else Fox.faint,
                    )
                    Spacer(Modifier.height(8.dp))
                    MonoKv("w0", PsaOffload.w0); MonoKv("w1", PsaOffload.w1)
                    if (phase == PsaOffload.Phase.FOUND) {
                        MonoKv("counter", PsaOffload.counter)
                        MonoKv("plain v0", PsaOffload.dv0)
                        MonoKv("plain v1", PsaOffload.dv1)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (connected) "Sent to the Flipper — it fills Serial/Btn/Cnt/CRC/Seed."
                            else "(not connected — result shown here only)",
                            color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp,
                        )
                    }
                }
            }
        }

        // Self-test: a known BF1 vector (counter 0x23ABCD12) cracked locally, no Flipper needed.
        PsaButton(
            if (phase == PsaOffload.Phase.CRACKING) "Cracking…" else "Run self-test (known vector)",
            enabled = phase != PsaOffload.Phase.CRACKING,
        ) {
            PsaOffload.onRequest(0x271D247B, 0x0E2BAFC7, sendOverBle = false)
        }
        Text(
            "Self-test cracks a synthetic PSA frame (expect counter 0x23ABCD12) end-to-end on the " +
                "phone — proves the TEA engine before you rely on a live offload. Takes a few seconds.",
            color = Fox.faint, fontFamily = FoxMono, fontSize = 10.sp, lineHeight = 14.sp,
        )

        if (phase == PsaOffload.Phase.CRACKING) {
            PsaButton("Cancel", enabled = true) { PsaOffload.onCancel() }
        }
    }
}

@Composable
private fun MonoKv(k: String, v: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = Fox.faint, fontFamily = FoxMono, fontSize = 11.sp, modifier = Modifier.width(78.dp))
        Text("0x%08X".format(v), color = Fox.fg, fontFamily = FoxMono, fontSize = 11.sp)
    }
}

@Composable
private fun PsaButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Fox.surface2 else Fox.surface2.copy(alpha = 0.4f))
            .border(1.dp, Fox.line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Fox.fg else Fox.faint, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold)
    }
}
