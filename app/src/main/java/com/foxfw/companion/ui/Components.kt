package com.foxfw.companion.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxfw.companion.R

@Composable
fun FoxLogo(size: Dp, modifier: Modifier = Modifier, glow: Boolean = true) {
    val t = rememberInfiniteTransition(label = "foxglow")
    val a by t.animateFloat(
        initialValue = 0.4f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "glow",
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (glow) Box(
            Modifier.matchParentSize().graphicsLayer { alpha = a }.background(
                Brush.radialGradient(
                    listOf(Fox.amber.copy(alpha = 0.38f), Fox.violet.copy(alpha = 0.28f), Color.Transparent)
                )
            )
        )
        Image(
            painter = painterResource(R.drawable.foxfw_fox),
            contentDescription = "FoxFW",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun GradientText(text: String, fontSize: Int = 18, weight: FontWeight = FontWeight.Bold, letterSpacing: Double = 0.5) {
    Text(
        text = text,
        style = TextStyle(
            brush = Fox.gradient(),
            fontSize = fontSize.sp,
            fontWeight = weight,
            letterSpacing = letterSpacing.sp,
        ),
    )
}

@Composable
fun Eyebrow(text: String, color: Color = Fox.faint) {
    Text(text.uppercase(), color = color, fontFamily = FoxMono, fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
}

@Composable
fun FoxCard(
    modifier: Modifier = Modifier,
    border: Color = Fox.line,
    bg: Color = Fox.surface,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    // chassis treatment: a soft top-light gradient on the default surface; solid when a caller tints it
    val brush = if (bg == Fox.surface) Brush.verticalGradient(listOf(Fox.surface2, Fox.surface)) else SolidColor(bg)
    Box(
        modifier.clip(shape).background(brush).border(BorderStroke(1.dp, border), shape).padding(15.dp)
    ) { content() }
}

/** A recessed instrument "screen": deep background, faint measurement graticule, centre axis. The
 *  spine of the Field-Instrument look — put a live trace, a readout or a header strip inside it. */
@Composable
fun GraticuleScreen(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.clip(shape).background(Fox.screen).border(BorderStroke(1.dp, Color.Black), shape).drawBehind {
            val sx = 24.dp.toPx(); val sy = 20.dp.toPx()
            var gx = sx; while (gx < size.width) { drawLine(Fox.grat, Offset(gx, 0f), Offset(gx, size.height), 1f); gx += sx }
            var gy = sy; while (gy < size.height) { drawLine(Fox.grat, Offset(0f, gy), Offset(size.width, gy), 1f); gy += sy }
            drawLine(Fox.axis, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        },
        content = content,
    )
}

@Composable
fun Pill(text: String, fg: Color, bg: Color, dot: Boolean = false) {
    Surface(shape = RoundedCornerShape(999.dp), color = bg, border = BorderStroke(1.dp, fg.copy(alpha = 0.4f))) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dot) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(fg))
                Spacer(Modifier.width(6.dp))
            }
            Text(text.uppercase(), color = fg, fontFamily = FoxMono, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun SignalBars(level: Int, on: Color = Fox.live, off: Color = Fox.line2) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 4) {
            Box(
                Modifier.width(3.dp).height((5 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i < level) on else off)
            )
        }
    }
}

data class Proto(val name: String, val color: Color, val bg: Color)

val protoVag = Proto("VAG", Color(0xFFB79BFF), Color(0x24967DFF))
val protoPsa = Proto("PSA", Color(0xFF8ECBFF), Color(0x1E5AA9FF))
val protoFiat = Proto("FIAT", Color(0xFFFF9E6B), Fox.amberDim)
val protoKia = Proto("KIA", Color(0xFF6FE0C0), Color(0x1E49DFAA))
val protoKeeloq = Proto("KeeLoq", Color(0xFFFFD166), Color(0x1EFFB020))
val protoHitag = Proto("Hitag2", Color(0xFFFF8FA3), Color(0x1EFF5C78))
val protoPandora = Proto("Pandora", Color(0xFF9AD0FF), Color(0x1A5AA9FF))

@Composable
fun ProtocolBadge(p: Proto) {
    Surface(shape = RoundedCornerShape(9.dp), color = p.bg, border = BorderStroke(1.dp, p.color.copy(alpha = 0.35f))) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(p.name, color = p.color, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun GradientBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(999.dp)).background(Fox.surface2)) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height)
                .clip(RoundedCornerShape(999.dp))
                .background(Fox.gradient())
        )
    }
}
