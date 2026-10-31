package com.foxfw.companion

/**
 * Dual-board dialect bridge. The app's whole control layer speaks the stock ESP32-Marauder CLI (see
 * [Marauder]). A stock-Marauder board understands that directly. A board running **Fox ESP32 FW**
 * does NOT — it uses an uppercase colon-AT vocabulary (`WIFISCANAP`, `WIFIATTACK:DEAUTH`, `BLESPAM:IOS`)
 * and a different output format, and it is synchronous/self-terminating (no `stopscan`). This object
 * translates between the two so the same app drives either board.
 *
 * Pure (no Android / USB / BLE) so it is host-unit-testable, exactly like [Marauder].
 *
 * Full table + rationale: project doc COMMAND_MAPPING_MARAUDER_TO_FOX_ESP32_2026-10-31.md.
 */
object FoxEsp32Dialect {

    /** Reply to `info` that identifies a Fox ESP32 FW board (used for board detection on connect). */
    const val FOX_BANNER = "Fox ESP32 Firmware"

    /** True when a serial line is the Fox ESP32 FW identity banner (reply to `info`). */
    fun isFoxBanner(line: String): Boolean = line.trim().contains(FOX_BANNER, ignoreCase = true)

    /**
     * Result of translating one Marauder CLI command for a Fox board.
     * [send] = Fox lines to write to the board, in order (empty = nothing goes to the board).
     * [localReply] = a line to feed straight back to the app's line callback without touching the
     * board — used for swallowed commands (`stopscan`) and unsupported ones, so the UI/relay never
     * sits waiting for a reply that will never come.
     */
    data class Out(val send: List<String>, val localReply: String? = null)

    private fun fox(vararg lines: String) = Out(lines.toList())
    private fun swallow(reply: String) = Out(emptyList(), reply)
    private fun unsupported(cmd: String) = Out(emptyList(), "[fox] not supported on Fox ESP32 FW: $cmd")

    /** Map a Marauder BLE-spam device class to Fox's BLESPAM mode. */
    private fun bleSpamMode(type: String): String = when (type.trim().lowercase()) {
        "sourapple", "applejuice" -> "IOS"
        "google", "android" -> "GOOGLE"
        "samsung" -> "SAMSUNG"
        "windows" -> "WINDOWS"
        "flipper" -> "FLIPPER"
        "all" -> "ALL"
        else -> "ALL"
    }

    /** First token of a comma/space list argument (Fox select/clone take a single index). */
    private fun firstIndex(arg: String): String = arg.trim().split(',', ' ').firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /**
     * Translate one Marauder CLI line into what a Fox ESP32 FW board should receive.
     * Unknown/unmapped lines pass through unchanged, so a raw Fox command typed into the console
     * still works and anything we didn't map at worst reaches the board as-is.
     */
    fun toFox(marauder: String): Out {
        val cmd = marauder.trim()
        val lc = cmd.lowercase()

        // ---- scan / list (async→sync: map the live poll to a fresh rescan) ----
        when (lc) {
            "scanap", "list -a" -> return fox("WIFISCANAP")
            "scansta", "list -s" -> return fox("WIFISCANSTA")
            "scanall" -> return fox("WIFISCANAP", "WIFISCANSTA")
            "list -c" -> return unsupported(cmd)
            "packetcount" -> return fox("WIFIPACKETCOUNT")
            "stopscan" -> return swallow("OK")            // Fox self-terminates; nothing to stop
        }

        // ---- select / targets ----
        if (lc == "select -a all") return unsupported(cmd)
        if (lc.startsWith("select -a ")) return fox("WIFISELECT:AP:" + firstIndex(cmd.substring(10)))
        if (lc.startsWith("select -s ")) return fox("WIFISELECT:STA:" + firstIndex(cmd.substring(10)))
        if (lc.startsWith("select -c ")) return unsupported(cmd)
        if (lc.startsWith("clearlist")) return unsupported(cmd)
        if (lc.startsWith("ssid ")) return unsupported(cmd)
        if (lc == "save -a" || lc == "load -a") return unsupported(cmd)

        // ---- WiFi attacks ----
        when (lc) {
            "attack -t deauth" -> return fox("WIFIATTACK:DEAUTH")
            "attack -t deauth -c" -> return fox("WIFIATTACK:DEAUTH", "WIFIPCAP")
            "attack -t beacon -r" -> return fox("WIFIATTACK:BEACON:RANDOM:5")
            "attack -t beacon -l" -> return fox("WIFIATTACK:BEACON:RANDOM:5") // approx: no saved-list concept
            "attack -t beacon -a" -> return unsupported(cmd)                  // AP-clone beacon: no Fox equiv
            "attack -t probe" -> return fox("WIFIATTACK:PROBE")
            "attack -t rickroll" -> return fox("WIFIATTACK:RICKROLL")
            "attack -t quiet" -> return fox("WIFIATTACK:QUIET")
            "attack -t csa" -> return fox("WIFIATTACK:CSA")
            "attack -t sae" -> return fox("WIFIATTACK:SAE")
            "attack -t badmsg -c" -> return fox("WIFIATTACK:BADPACKET")
        }
        if (lc.startsWith("karma")) return fox("WIFIATTACK:KARMA")           // -p index dropped (Fox has none)
        if (lc == "evilportal -c start") return fox("WIFIFOXPORTAL:START")
        if (lc == "evilportal -c stop") return fox("WIFIFOXPORTAL:STOP")
        if (lc.startsWith("spoofat")) return fox("SPOOFAT")                   // -t index dropped

        // ---- channel ----
        if (lc == "channel") return fox("WIFICHANNEL")
        if (lc.startsWith("channel -s ")) return fox("WIFICHANNEL:" + firstIndex(cmd.substring(11)))

        // ---- sniff family ----
        when (lc) {
            "sniffpwn" -> return fox("WIFIPCAP")                             // PMKID + EAPOL full capture
            "sniffpmkid" -> return fox("WIFISNIFF:PMKID")
            "sniffsae" -> return fox("WIFISNIFF:SAE")
            "sniffbeacon" -> return fox("WIFISNIFF:BEACON")
            "sniffprobe" -> return fox("WIFISNIFF:PROBE")
            "sniffdeauth" -> return fox("WIFISNIFF:DEAUTH")
            "sniffraw" -> return fox("WIFISNIFF:RAW")
            "sniffmultissid" -> return fox("WIFISNIFF:MULTISSID")
            "sniffpinescan", "sniffskim" -> return unsupported(cmd)
        }

        // ---- Bluetooth ----
        if (lc.startsWith("blespam -t ")) return fox("BLESPAM:" + bleSpamMode(cmd.substring(11)))
        if (lc.startsWith("sniffbt -t ")) return fox("BLETAGSCAN:" + cmd.substring(11).trim().uppercase())

        // ---- network recon ----
        when (lc) {
            "pingscan" -> return fox("WIFIPINGSCAN")
            "arpscan -f" -> return fox("WIFIARPSCAN")
            "mactrack" -> return fox("WIFIMACTRACK")
            "portscan -a" -> return unsupported(cmd)                         // Fox needs ip:start:end args
            "foxhunt -b", "foxhunt -w" -> return unsupported(cmd)
        }

        // ---- MAC ops ----
        when (lc) {
            "randapmac", "randstamac" -> return fox("WIFIMAC:RAND")
        }
        if (lc.startsWith("cloneapmac")) return fox("WIFIMAC:CLONEAP")        // uses selected AP; index dropped

        // ---- GPS / wardrive ----
        when (lc) {
            "wardrive" -> return fox("WIFIWARDRIVE")
            "gpsdata" -> return fox("GPS:FIX")
            "nmea" -> return unsupported(cmd)
            "gpstracker -c start" -> return fox("GPSTRACK")
            "gpstracker -c stop" -> return swallow("OK")
        }
        if (lc.startsWith("wardrivepoi ")) return fox("GPSPOI:MARK:" + cmd.substring(12).trim())
        if (lc.startsWith("upload")) return unsupported(cmd)

        // ---- system ----
        when (lc) {
            "info" -> return fox("info")
            "settings" -> return fox("SETTINGS")
            "settings -r" -> return unsupported(cmd)
            "reboot" -> return unsupported(cmd)
            "update -s", "update -w" -> return unsupported(cmd)
        }
        if (lc.startsWith("brightness") || lc.startsWith("led ")) return unsupported(cmd)

        // ---- anything else: pass through unchanged (raw Fox commands, power-user console) ----
        return fox(cmd)
    }

    // ----- inbound: Fox output line -> Marauder-format line (or null to swallow) -----

    private val AP = Regex("""^AP:(\d+)\s+ssid:"(.*)"\s+bssid:(\S+)\s+ch:(-?\d+)\s+rssi:(-?\d+)\s+enc:(\S+)\s*$""")
    private val STA = Regex("""^STA:(\d+)\s+mac:(\S+)\s+rssi:(-?\d+)\s*$""")
    private val TERMINATORS = setOf("SCANDONE", "DONE", "SNIFFDONE", "ATTACKDONE")

    /**
     * Translate one line the Fox board emitted back into the Marauder line format the app's parsers
     * ([Marauder.parseAp]/[Marauder.parseSta]) expect. Returns null for lines to swallow (scan/attack
     * terminators). Non-AP/STA status lines (OK / ERROR / ECHO / COUNT / CHANNEL / MAC / MACTRACK and
     * the board's own capture notifications) pass through unchanged so the console + capture
     * heuristics keep working.
     *
     * [selectedApIndex] is the last AP index the adapter selected (via WIFISELECT:AP:n), so the
     * rebuilt row can carry Marauder's ` (selected)` suffix; pass -1 if none.
     */
    fun fromFox(line: String, selectedApIndex: Int): String? {
        val l = line.trim()
        if (l.isEmpty()) return null
        if (l in TERMINATORS) return null

        AP.find(l)?.let { m ->
            val idx = m.groupValues[1]
            val essid = m.groupValues[2]
            val ch = m.groupValues[4]
            val rssi = m.groupValues[5]
            val sel = if (idx.toIntOrNull() == selectedApIndex && selectedApIndex >= 0) " (selected)" else ""
            // Marauder row: [idx][CH:ch] essid rssi [(selected)]  — hidden/empty essid printed blank.
            return "[$idx][CH:$ch] $essid $rssi$sel"
        }
        STA.find(l)?.let { m ->
            return "[${m.groupValues[1]}] ${m.groupValues[2]}"
        }
        return l // status / capture lines pass through
    }
}
