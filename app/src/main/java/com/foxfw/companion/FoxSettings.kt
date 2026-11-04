package com.foxfw.companion

import kotlinx.coroutines.delay

/**
 * Full FoxFW device-settings sync over the existing BLE/USB storage RPC, driven by
 * the "Import Companion Settings" firmware app (fox_import_settings.fap).
 *
 *   READ : mkdir dir, delete current.txt, write request.flag="READ", launch importer, poll current.txt.
 *   WRITE: write pending.txt (changed sections), request.flag="WRITE", launch importer, poll flag gone.
 *
 * The 42-field model + section payloads mirror the firmware importer 1:1.
 */
object FoxSettings {
    const val DIR = "/ext/apps_data/fox_companion"
    const val REQ = "$DIR/request.flag"
    const val PEND = "$DIR/pending.txt"
    const val CUR = "$DIR/current.txt"
    const val IMPORTER = "/ext/apps/System/fox_android_companion.fap"

    // Dropdown option labels (exact to the reference design).
    val BACKLIGHT_OFF = listOf("1s", "5s", "10s", "15s", "30s", "60s")
    private val BACKLIGHT_OFF_MS = intArrayOf(1000, 5000, 10000, 15000, 30000, 60000)
    val THEME = listOf("Classic", "Fox", "Carousel", "Slider", "Tiny")
    val TIME_FMT = listOf("24-hour", "12-hour")
    val DATE_FMT = listOf("D/M/Y", "M/D/Y", "Y/M/D")
    val UNITS = listOf("Metric", "Imperial")
    val NIGHT_LEVEL = listOf("Off", "1", "2", "3", "4", "5", "6")
    val BATTERY_VIEW = listOf("0", "1", "2", "3", "4", "5")
    val SHELL_COLOR = listOf("0", "1", "2", "3", "4", "5", "6", "7")
    val VIBRO_LEVEL = listOf("Off", "13", "16", "19", "21", "24", "27", "30", "33", "36")
    val VIBRO_TRIGGER = listOf("Press", "Release", "Both")
    val AUTO_POWEROFF = listOf("Off", "5m", "10m", "15m", "30m", "45m", "60m", "90m")
    val CHARGE_LIMIT = listOf("Off", "90%", "85%", "80%", "75%", "70%")
    val SLEEP = listOf("Modern", "Legacy")

    /** RGB-backlight palette, index-matched 1:1 to the firmware `colors[]` table
     *  (notification_app.c). Each is (name, 0xRRGGBB). Index 13 = "OFF". */
    val RGB_PALETTE: List<Pair<String, Int>> = listOf(
        "Orange" to 0xFF3C00, "Yellow" to 0xFF9000, "Spring" to 0xA7FF00, "Lime" to 0x00FF00,
        "Aqua" to 0x00FF7F, "Cyan" to 0x00D2D2, "Azure" to 0x007FFF, "Blue" to 0x0000FF,
        "Purple" to 0x7F00FF, "Magenta" to 0xD200D2, "Pink" to 0xFF007F, "Red" to 0xFF0000,
        "White" to 0xFED2C8, "OFF" to 0x000000,
    )
    /** rainbow_mode in the firmware: 0 = solid colors, 1/2 = animated rainbow. */
    val RGB_EFFECT = listOf("Static", "Rainbow", "Rainbow 2")

    fun delayToIdx(ms: Int): Int { val i = BACKLIGHT_OFF_MS.indexOfFirst { it >= ms }; return if (i < 0) BACKLIGHT_OFF_MS.size - 1 else i }
    fun idxToDelay(idx: Int): Int = BACKLIGHT_OFF_MS[idx.coerceIn(0, BACKLIGHT_OFF_MS.size - 1)]

    /** 42-field model, same order as the firmware current.txt line. */
    class Cfg {
        var contrast = 0; var backlightPct = 50; var ledPct = 50; var volumePct = 50
        var delayMs = 30000; var vibro = 1; var inversion = 0
        var timeFmt = 0; var dateFmt = 0; var units = 0; var hand = 0; var theme = 0
        var nsIdx = 0; var nsStart = 0; var nsEnd = 0
        var rgbInstalled = 0; var rgbWhite = 0; var rgbLed1 = 0; var rgbLed2 = 0; var rgbLed3 = 0
        var rgbEffect = 0; var rgbSpeed = 500; var rgbStep = 1; var rgbSat = 255; var rgbWave = 40
        var sleepMethod = 0; var fileNaming = 0
        var vibroLevelIdx = 0; var vibroTriggerIdx = 0
        var btEnabled = 1
        var autoPoweroffIdx = 0; var limitChargeIdx = 0
        var batteryView = 0; var showClock = 0; var midnightFmt = 0; var wifiHidden = 0
        var statusbarIcons = 1; var shellColorIdx = 0
        var alarmKeepBacklight = 0; var alarmBeep = 1; var alarmVibrate = 1
        var deviceName = ""
    }

    private val link get() = FlipperTransport.active

    suspend fun supported(): Boolean = link?.storageStat(IMPORTER) == true

    /** Returns the current device settings, or null on timeout/failure. */
    suspend fun read(): Cfg? {
        val l = FlipperTransport.active ?: return null
        l.storageMkdir(DIR)
        l.storageDelete(CUR, recursive = false)
        if (!l.storageWrite(REQ, "READ".toByteArray())) return null
        // One-shot launch — plain appStart (no forced back-taps). false = loader busy / another app open.
        if (!l.appStart(IMPORTER, "SETTINGS")) { CompanionLink.report(false); return null }
        repeat(16) {
            delay(500)
            val r = l.storageRead(CUR)
            if (r != null && r.isNotEmpty()) { CompanionLink.report(true); return parse(String(r)) }
        }
        CompanionLink.report(false)
        return null
    }

    /**
     * Applies the config. Returns true once the importer clears the flag. If the Flipper's loader is
     * busy (the companion can't launch because another app is open) the change is banked and replayed
     * when access returns — the caller still sees false, but the save isn't lost.
     */
    suspend fun write(c: Cfg): Boolean {
        if (writeOnce(c)) { CompanionLink.report(true); return true }
        CompanionLink.bank("Device settings") { writeOnce(c) }
        return false
    }

    /** One apply attempt, no banking. true once the importer consumes the WRITE flag. */
    private suspend fun writeOnce(c: Cfg): Boolean {
        val l = FlipperTransport.active ?: return false
        l.storageMkdir(DIR)
        if (!l.storageWrite(PEND, buildPending(c).toByteArray())) return false
        if (!l.storageWrite(REQ, "WRITE".toByteArray())) return false
        if (!l.appStart(IMPORTER, "SETTINGS")) return false   // one-shot; false = loader busy -> write() banks it
        repeat(16) {
            delay(500)
            val r = l.storageRead(REQ)
            if (r == null || r.isEmpty()) return true   // flag consumed = applied
        }
        return false
    }

    private fun parse(text: String): Cfg? {
        val p = text.trim().split(",")
        if (p.size < 42) return null
        return try {
            fun g(i: Int) = p[i].trim().toInt()
            Cfg().apply {
                contrast = g(0); backlightPct = g(1); ledPct = g(2); volumePct = g(3)
                delayMs = g(4); vibro = g(5); inversion = g(6)
                timeFmt = g(7); dateFmt = g(8); units = g(9); hand = g(10); theme = g(11)
                nsIdx = g(12); nsStart = g(13); nsEnd = g(14)
                rgbInstalled = g(15); rgbWhite = g(16); rgbLed1 = g(17); rgbLed2 = g(18); rgbLed3 = g(19)
                rgbEffect = g(20); rgbSpeed = g(21); rgbStep = g(22); rgbSat = g(23); rgbWave = g(24)
                sleepMethod = g(25); fileNaming = g(26)
                vibroLevelIdx = g(27); vibroTriggerIdx = g(28); btEnabled = g(29)
                autoPoweroffIdx = g(30); limitChargeIdx = g(31)
                batteryView = g(32); showClock = g(33); midnightFmt = g(34); wifiHidden = g(35)
                statusbarIcons = g(36); shellColorIdx = g(37)
                alarmKeepBacklight = g(38); alarmBeep = g(39); alarmVibrate = g(40)
                deviceName = p.subList(41, p.size).joinToString(",").trim()
            }
        } catch (_: Exception) { null }
    }

    private fun buildPending(m: Cfg): String = buildString {
        append("NOTIF ${m.contrast},${m.backlightPct},${m.ledPct},${m.volumePct},${m.delayMs},${m.vibro},${m.inversion}\n")
        append("LOCALE ${m.timeFmt},${m.dateFmt},${m.units},${m.hand}\n")
        append("THEME ${m.theme}\n")
        append("NIGHTSHIFT ${m.nsIdx},${m.nsStart},${m.nsEnd}\n")
        append("RGB ${m.rgbInstalled},${m.rgbWhite},${m.rgbLed1},${m.rgbLed2},${m.rgbLed3},${m.rgbEffect},${m.rgbSpeed},${m.rgbStep},${m.rgbSat},${m.rgbWave}\n")
        append("INPUT ${m.vibroLevelIdx},${m.vibroTriggerIdx}\n")
        append("BT ${m.btEnabled}\n")
        append("POWER ${m.autoPoweroffIdx},${m.limitChargeIdx}\n")
        append("DESKTOP ${m.batteryView},${m.showClock},${m.midnightFmt},${m.wifiHidden},${m.statusbarIcons},${m.shellColorIdx}\n")
        append("ALARM ${m.alarmKeepBacklight},${m.alarmBeep},${m.alarmVibrate}\n")
        append("SYSTEM ${m.sleepMethod},${m.fileNaming},${m.deviceName}\n")
    }
}
