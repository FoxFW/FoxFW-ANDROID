package com.foxfw.companion.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.foxfw.companion.R

/** FoxFW 2.1 brand palette — dark "device-screen" world, amber -> violet gradient. */
object Fox {
    // Field-instrument chassis — warm, amber-biased neutrals (a grey biased toward the accent)
    val bg = Color(0xFF0E0B08)
    val page = Color(0xFF0A0807)
    val surface = Color(0xFF16110A)
    val surface2 = Color(0xFF1C160D)
    val line = Color(0xFF2B2418)
    val line2 = Color(0xFF39301F)

    val fg = Color(0xFFECE4D6)
    val muted = Color(0xFF9C9384)
    val faint = Color(0xFF6E6757)

    val amber = Color(0xFFFF9A3D)
    val amberInk = Color(0xFF1A0F06)
    val pink = Color(0xFFFF6FB0)
    val violet = Color(0xFFB06BFF)

    val live = Color(0xFF4FE08A)
    val warn = Color(0xFFFFB020)
    val crit = Color(0xFFFF5C5C)
    val info = Color(0xFF5AA9FF)

    // recessed-screen + phosphor tokens for the instrument surfaces
    val screen = Color(0xFF080705)
    val phos = Color(0xFFFFD49B)
    val grat = Color(0x12FFB45A)
    val axis = Color(0x22FFB45A)
    val hi = Color(0x10FFE0AA)

    val amberDim = Color(0x24FF9A3D)
    val violetDim = Color(0x26B06BFF)
    val liveDim = Color(0x244FE08A)

    /** The signature FOXFW gradient (amber -> pink -> violet). */
    fun gradient(from: Offset = Offset.Zero, to: Offset = Offset.Infinite) =
        Brush.linearGradient(listOf(Color(0xFFFF9A3D), Color(0xFFFF6FB0), Color(0xFF9D6BFF)), from, to)
}

private val FoxColorScheme = darkColorScheme(
    primary = Fox.amber,
    onPrimary = Fox.amberInk,
    secondary = Fox.violet,
    background = Fox.bg,
    onBackground = Fox.fg,
    surface = Fox.surface,
    onSurface = Fox.fg,
    surfaceVariant = Fox.surface2,
    outline = Fox.line2,
    error = Fox.crit,
)

/** Display face — Chakra Petch, a squared techy face that fits the RF/tactical brand. */
val Display = FontFamily(
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

/** Data face — IBM Plex Mono, for frequencies, hex, RSSI and labels. */
val FoxMono = FontFamily(
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

val FoxType = Typography(
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.3.sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FoxMono, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = FoxMono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.4.sp),
    labelMedium = TextStyle(fontFamily = FoxMono, fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun FoxFWTheme(content: @Composable () -> Unit) {
    // Deliberately dark-only: the firmware's identity is a dark device screen.
    MaterialTheme(colorScheme = FoxColorScheme, typography = FoxType) {
        // Make Chakra Petch the default for any Text() that doesn't set its own family.
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = Display, color = Fox.fg),
            content = content,
        )
    }
}
