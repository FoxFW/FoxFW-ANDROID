package com.foxfw.companion

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Brush
import com.foxfw.companion.ui.Fox
import com.foxfw.companion.ui.FoxCard
import com.foxfw.companion.ui.FoxLogo
import com.foxfw.companion.ui.FoxMono
import com.foxfw.companion.ui.GradientText

/**
 * "What's New" for the firmware/companion release. Shown once automatically per
 * version (tracked in SharedPreferences) and re-openable from Settings.
 * Keep [VERSION] in lockstep with build.gradle versionName and the FoxFW tag.
 */
object WhatsNew {
    const val VERSION = "2.1"
    private const val PREFS = "foxfw_prefs"
    private const val KEY = "whatsnew_seen_v"

    fun shouldShow(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY, "") != VERSION
    }

    fun markSeen(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, VERSION).apply()
    }

    data class Item(val icon: ImageVector, val title: String, val body: String, val tag: String)

    val items = listOf(
        Item(
            Icons.Outlined.Wifi,
            "Wi-Fi attack suite",
            "Drive a BFFB v2 (ESP32-C5 Esp32) end to end from the phone — over the Flipper relay or " +
                "USB. Scan 2.4 + 5 GHz, deauth-assisted handshake capture, continuous auto-harvest, and an " +
                "Evil Portal manager with a live captured-cred view. Tools → Esp32.",
            "WIFI",
        ),
        Item(
            Icons.Outlined.Hub,
            "One ESP32 connection, one console",
            "Connect the Esp32 board once and it stays connected as you move around the app — the " +
                "Wi-Fi, BLE, GPS, IR, Sub-GHz and RFID terminals all share the one link and a single " +
                "console, so scans and captures show up in the same place wherever they came from.",
            "ESP32",
        ),
        Item(
            Icons.Outlined.Usb,
            "Connect over USB",
            "Pair the Flipper straight to the phone with a USB-OTG cable, not just over Bluetooth. The " +
                "same features work either way, and USB is the faster link for screen mirroring and " +
                "large file transfers.",
            "CONNECT",
        ),
        Item(
            Icons.Outlined.ScreenShare,
            "Screen mirror & remote",
            "Mirror the Flipper's screen on the phone and drive it — the D-pad, OK and Back — with an " +
                "on-screen keyboard for typing. Works in portrait and landscape. Device tab.",
            "DEVICE",
        ),
        Item(
            Icons.Outlined.SystemUpdate,
            "Firmware updates from the app",
            "Check for and install FoxFW updates over the existing link, no computer needed — the app " +
                "downloads the build and flashes it to the Flipper for you. Device tab.",
            "UPDATE",
        ),
        Item(
            Icons.Outlined.Lan,
            "On-phone network recon",
            "New LAN Scanner (finds your subnet, connect-scans every host for common services) and BLE " +
                "Explorer (scan → connect → full GATT tree, read / subscribe / write any characteristic) — " +
                "both run entirely on the phone. Tools → LAN Scanner / BLE Explorer.",
            "RECON",
        ),
        Item(
            Icons.Outlined.Password,
            "WPA handshake cracking",
            "Load a .pcap or hc22000 and crack WPA on the phone — PMKID (WPA*01) and the 4-way EAPOL " +
                "handshake (WPA*02), including 802.11w/PMF (AES-CMAC), on the GPU or CPU. Tools → Handshake Crack.",
            "WIFI",
        ),
        Item(
            Icons.Outlined.GraphicEq,
            "Live Sub-GHz bridge",
            "Decode, Car & Gate Clone and Pandora now capture and transmit live through the " +
                "Flipper's CC1101 (internal / external / auto) over BLE — no SD shuffling. Powered " +
                "by the new fox_subghz_io radio app on the firmware.",
            "RADIO",
        ),
        Item(
            Icons.Outlined.Timeline,
            "Sub-GHz viewer & trimmer",
            "See any capture as a waveform, then select a section and trim everything outside it to " +
                "save a clean .sub. The Decode capture window is now adjustable (1–30s) and a live " +
                "capture can be stopped early, keeping what was recorded so far.",
            "RADIO",
        ),
        Item(
            Icons.Outlined.DirectionsCar,
            "Full Sub-GHz decoder parity",
            "The on-phone ArkeEngine now decodes the same Sub-GHz protocols the firmware does — the " +
                "whole set, including full KeeLoq and Alutech rolling-code decode (KIA, VAG, PSA, Fiat, " +
                "Subaru, the gate families and more) — straight from a Flipper RAW capture.",
            "DECODE",
        ),
        Item(
            Icons.Outlined.Contactless,
            "ChameleonUltra over BLE",
            "Connect a ChameleonUltra from the Tools tab: read HF/LF, browse and write slots, and " +
                "run clone flows — all over Nordic UART BLE.",
            "NFC",
        ),
        Item(
            Icons.Outlined.Key,
            "Hitag2 / Fiat recovery",
            "Guess-and-determine Hitag2 key recovery offloads to the phone when the Flipper streams " +
                "a job — jump straight to the recovery screen and get the clone secret back.",
            "OFFLOAD",
        ),
        Item(
            Icons.Outlined.Memory,
            "PSA TEA offload",
            "When the Flipper's PSA decrypt offloads its ~2²⁵ TEA brute, the phone now cracks it " +
                "(multi-threaded) and sends the plaintext back automatically. Tools → PSA Offload, " +
                "with a built-in self-test.",
            "OFFLOAD",
        ),
        Item(
            Icons.Outlined.Contactless,
            "NFC / RFID Tag Maker",
            "Build a tag (MIFARE, NTAG/Ultralight, NDEF URL/text/contact, EM410x/HID) and emulate it, write " +
                "it to a blank, or clone one — via the Flipper (HF) or ChameleonUltra (HF + EM410x). " +
                "Tools → Tag Maker. (Flipper physical write + the LF protocol long-tail are a follow-up.)",
            "NFC",
        ),
        Item(
            Icons.Outlined.VpnKey,
            "MIFARE key recovery (MFKey)",
            "Recover MIFARE Classic keys on the phone from the nonces the Flipper collects with " +
                "Detect Reader — it reads the .mfkey32.log off the device, solves each one, and can add " +
                "the recovered keys straight to your user dictionary. Tools → MFKey.",
            "NFC",
        ),
        Item(
            Icons.Outlined.Brush,
            "Wallpaper Painter",
            "Draw a 128×64 wallpaper for the Flipper right on the phone and send it across. Tools → Painter.",
            "WALLPAPER",
        ),
        Item(
            Icons.Outlined.Sensors,
            "Pandora suite (native)",
            "The whole Pandora keyfob suite now runs natively on the phone: emulate the real D-605 / " +
                "MAX (ARM) and DXL-5000 (PIC) firmware, and the code-grabber recovers the unlock code " +
                "— knock-code (ARM) or PIN (PIC) — straight from a dump. D-605/MAX forge + transmit " +
                "live via the Flipper as OOK (the field-proven modulation). Tools → Pandora.",
            "RF",
        ),
    )
}

@Composable
fun WhatsNewSheet(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF1B1522), Fox.page), radius = 1600f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FoxLogo(72.dp)
            GradientText("WHAT'S NEW", fontSize = 24)
            Text(
                "FoxFW ${WhatsNew.VERSION} · companion",
                color = Fox.muted, fontFamily = FoxMono, fontSize = 11.sp,
            )
            Spacer(Modifier.height(2.dp))

            WhatsNew.items.forEach { item ->
                FoxCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Fox.surface2),
                            contentAlignment = Alignment.Center,
                        ) { Icon(item.icon, null, tint = Fox.violet, modifier = Modifier.size(19.dp)) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.title, color = Fox.fg, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                                )
                                Text(
                                    item.tag, color = Fox.amber, fontFamily = FoxMono,
                                    fontSize = 9.sp, letterSpacing = 1.sp,
                                )
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(item.body, color = Fox.muted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Fox.gradient())
                    .clickable { onDismiss() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Got it", color = Color(0xFF14101B), fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                )
            }
        }
    }
}
