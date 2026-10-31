package com.foxfw.companion

/**
 * Transport-agnostic ESP32 Marauder control for the BFFB v2 (ESP32-C5, dual-band). Pure command
 * strings + output parsers — no Android/USB/BLE here. A [MarauderLink] pumps serial lines in and
 * takes commands out; both the USB-OTG and the Flipper-BLE-relay transports speak this same CLI.
 *
 * Command + line formats verified against the BFFB deauth FAP (bffb-deauth) and the Marauder CLI.
 */
object Marauder {
    /** An access point from `list -a`. Band is derived from the channel (C5 5 GHz is ch >= 15). */
    data class Ap(
        val index: Int,
        val channel: Int,
        val rssi: Int,
        val essid: String,
        val selected: Boolean,
    ) {
        val band: BandScan.Band get() = if (channel >= 15) BandScan.Band.GHZ5 else BandScan.Band.GHZ24
    }

    // ---- commands (as the BFFB FAP drives them) ----
    const val SCAN_AP = "scanap"     // scan APs on both bands (C5)
    const val SCAN_STA = "scansta"   // scan stations of selected APs
    const val STOP = "stopscan"      // stop any scan/attack
    const val LIST_AP = "list -a"    // dump the indexed AP list
    const val LIST_STA = "list -s"   // dump the indexed station list

    fun selectAp(index: Int) = "select -a $index"
    fun selectAllAp() = "select -a all"
    fun deauth(includeStations: Boolean) = if (includeStations) "attack -t deauth -c" else "attack -t deauth"
    fun beaconList() = "attack -t beacon -l"
    fun beaconRandom() = "attack -t beacon -r"
    fun probeFlood() = "attack -t probe"

    private val HEAD = Regex("""^\[(-?\d+)]\[CH:(-?\d+)]""")
    private const val SEL = " (selected)"

    /**
     * Parse a Marauder `list -a` line: `[<idx>][CH:<ch>] <essid> <rssi> [(selected)]`.
     * ESSID may contain spaces; RSSI is the trailing signed int; an empty/hidden ESSID -> "<hidden>".
     * Returns null for any line that isn't an AP row.
     */
    fun parseAp(line: String): Ap? {
        if (line.isEmpty() || line[0] != '[') return null
        val m = HEAD.find(line) ?: return null
        val idx = m.groupValues[1].toIntOrNull() ?: return null
        val ch = m.groupValues[2].toIntOrNull() ?: return null
        val br = line.indexOf("] ")
        if (br < 0) return null
        var rest = line.substring(br + 2).trim()

        var selected = false
        if (rest.endsWith(SEL)) {
            selected = true
            rest = rest.dropLast(SEL.length).trimEnd()
        }

        var rssi = 0
        var essid = rest
        val lastSp = rest.lastIndexOf(' ')
        if (lastSp >= 0) {
            rest.substring(lastSp + 1).toIntOrNull()?.let {
                rssi = it
                essid = rest.substring(0, lastSp).trimEnd()
            }
        } else {
            // only an RSSI left (hidden ESSID prints as spaces, trimmed away) -> no ESSID
            rest.toIntOrNull()?.let { rssi = it; essid = "" }
        }
        if (essid.isEmpty()) essid = "<hidden>"
        return Ap(idx, ch, rssi, essid, selected)
    }

    /** A station (client) from `list -s`: `[<idx>] <MAC>`. */
    data class Sta(val index: Int, val mac: String)

    private val STA = Regex("""^\[(\d+)]\s+([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})""")

    fun parseSta(line: String): Sta? {
        val m = STA.find(line.trim()) ?: return null
        val idx = m.groupValues[1].toIntOrNull() ?: return null
        return Sta(idx, m.groupValues[2])
    }

    // ---- full command catalog: everything the app can drive on the board ----

    /** What (if any) argument a [Cmd] needs. The UI picks the input affordance from this. */
    enum class ArgKind { NONE, INDEX, CHANNEL, TEXT, CHOICE }

    /**
     * One Marauder CLI command. [template] is the literal command; a `{}` placeholder is filled by
     * [build] with the (trimmed) argument. [danger] marks attacks/spam that the UI gates behind an
     * authorized-use confirm. [choices] lists the allowed values for an [ArgKind.CHOICE] arg.
     */
    data class Cmd(
        val id: String,
        val label: String,
        val template: String,
        val arg: ArgKind = ArgKind.NONE,
        val choices: List<String> = emptyList(),
        val danger: Boolean = false,
        val desc: String = "",
    )

    data class Category(val name: String, val cmds: List<Cmd>)

    /**
     * Turn a chosen command + optional argument into the exact serial line, or null when a required
     * argument is missing/blank (so the UI never sends a half command).
     */
    fun build(cmd: Cmd, arg: String?): String? {
        if (!cmd.template.contains("{}")) return cmd.template
        val a = arg?.trim().orEmpty()
        if (a.isEmpty()) return null
        return cmd.template.replace("{}", a)
    }

    /**
     * The ESP32-Marauder C5 CLI, grouped for the control UI. Every entry is verified against the live
     * `help` dump of a BFFB v2 on Marauder v1.20.0. Complex multi-argument commands (`join -a <i> -p
     * <pw>`, `ssid -a -n <name>`, `add -a -b <mac> ...`) are left to the screen's raw-console field
     * rather than encoded here — the console can send any line the board understands.
     */
    val catalog: List<Category> = listOf(
        Category(
            "Scan", listOf(
                Cmd("scanap", "Scan APs", "scanap", desc = "both bands (C5)"),
                Cmd("scansta", "Scan stations", "scansta", desc = "clients of selected APs"),
                Cmd("scanall", "Scan all", "scanall", desc = "APs + stations"),
                Cmd("packetcount", "Packet count", "packetcount"),
                Cmd("stopscan", "Stop", "stopscan", desc = "stop any scan/attack"),
            ),
        ),
        Category(
            "Sniff", listOf(
                Cmd("sniffpwn", "Sniff handshakes", "sniffpwn", desc = "PMKID + EAPOL"),
                Cmd("sniffpmkid", "Sniff PMKID", "sniffpmkid", desc = "PMKID only"),
                Cmd("sniffsae", "Sniff SAE", "sniffsae", desc = "WPA3 handshake"),
                Cmd("sniffbeacon", "Sniff beacons", "sniffbeacon"),
                Cmd("sniffprobe", "Sniff probes", "sniffprobe"),
                Cmd("sniffdeauth", "Sniff deauths", "sniffdeauth"),
                Cmd("sniffraw", "Sniff raw", "sniffraw", desc = "all 802.11 frames"),
                Cmd("sniffpinescan", "Sniff Pinescan", "sniffpinescan"),
                Cmd("sniffmultissid", "Sniff multi-SSID", "sniffmultissid"),
                Cmd("sniffskim", "Sniff skimmers", "sniffskim", desc = "card-skimmer BLE"),
            ),
        ),
        Category(
            "WiFi attacks", listOf(
                Cmd("deauth", "Deauth (selected)", "attack -t deauth", danger = true, desc = "selected APs"),
                Cmd("deauth_targeted", "Deauth + capture", "attack -t deauth -c", danger = true, desc = "deauth then grab handshake"),
                Cmd("beacon_list", "Beacon spam (list)", "attack -t beacon -l", danger = true),
                Cmd("beacon_random", "Beacon spam (random)", "attack -t beacon -r", danger = true),
                Cmd("beacon_ap", "Beacon spam (AP clone)", "attack -t beacon -a", danger = true),
                Cmd("probe", "Probe flood", "attack -t probe", danger = true),
                Cmd("rickroll", "Rickroll beacons", "attack -t rickroll", danger = true),
                Cmd("attack_quiet", "Quiet (channel switch)", "attack -t quiet", danger = true),
                Cmd("attack_csa", "CSA flood", "attack -t csa", danger = true),
                Cmd("attack_sae", "SAE flood (WPA3)", "attack -t sae", danger = true),
                Cmd("attack_badmsg", "Bad-msg (+capture)", "attack -t badmsg -c", danger = true),
                Cmd("karma", "Karma", "karma -p {}", ArgKind.INDEX, danger = true, desc = "probe index"),
                Cmd("evilportal", "Evil Portal", "evilportal -c start", danger = true, desc = "rogue captive portal"),
                Cmd("spoofat", "Spoof AP", "spoofat -t {}", ArgKind.INDEX, danger = true, desc = "AP index"),
            ),
        ),
        Category(
            "Bluetooth", listOf(
                Cmd("sniffbt", "Sniff BT", "sniffbt -t {}", ArgKind.CHOICE,
                    listOf("airtag", "flipper", "flock", "meta"), desc = "device class"),
                Cmd("blespam", "BLE spam", "blespam -t {}", ArgKind.CHOICE,
                    listOf("sourapple", "applejuice", "google", "samsung", "windows", "flipper", "all"),
                    danger = true),
                Cmd("sourapple", "Sour Apple", "blespam -t sourapple", danger = true, desc = "Apple popup spam"),
            ),
        ),
        Category(
            "Channel", listOf(
                Cmd("channel_get", "Get channel", "channel"),
                Cmd("channel_set", "Set channel", "channel -s {}", ArgKind.CHANNEL, desc = "1–14 / 36+"),
            ),
        ),
        Category(
            "Lists & targets", listOf(
                Cmd("list_ap", "List APs", "list -a"),
                Cmd("list_sta", "List stations", "list -s"),
                Cmd("list_ssid", "List SSIDs", "list -c"),
                Cmd("select_ap", "Select AP", "select -a {}", ArgKind.INDEX, desc = "index(es), comma-sep"),
                Cmd("select_ap_all", "Select all APs", "select -a all"),
                Cmd("select_sta", "Select station", "select -s {}", ArgKind.INDEX),
                Cmd("select_ssid", "Select SSID", "select -c {}", ArgKind.INDEX),
                Cmd("clearlist_ap", "Clear AP list", "clearlist -a"),
                Cmd("clearlist_sta", "Clear station list", "clearlist -s"),
                Cmd("clearlist_ssid", "Clear SSID list", "clearlist -c"),
                Cmd("ssid_add", "Add SSIDs (random)", "ssid -a -g {}", ArgKind.TEXT, desc = "count"),
                Cmd("ssid_remove", "Remove SSID", "ssid -r {}", ArgKind.INDEX),
                Cmd("save_ap", "Save APs to SD", "save -a"),
                Cmd("load_ap", "Load APs from SD", "load -a"),
                Cmd("randapmac", "Random AP MAC", "randapmac"),
                Cmd("randstamac", "Random STA MAC", "randstamac"),
                Cmd("cloneapmac", "Clone AP MAC", "cloneapmac -a {}", ArgKind.INDEX),
            ),
        ),
        Category(
            "Network recon", listOf(
                Cmd("pingscan", "Ping scan", "pingscan"),
                Cmd("arpscan", "ARP scan", "arpscan -f"),
                Cmd("portscan", "Port scan", "portscan -a"),
                Cmd("foxhunt_ble", "Fox-hunt (BLE)", "foxhunt -b"),
                Cmd("foxhunt_wifi", "Fox-hunt (WiFi)", "foxhunt -w"),
                Cmd("mactrack", "MAC track", "mactrack"),
            ),
        ),
        Category(
            "GPS / wardrive", listOf(
                Cmd("wardrive", "Wardrive", "wardrive", desc = "log APs+GPS to SD"),
                Cmd("wardrivepoi", "Wardrive POI", "wardrivepoi {}", ArgKind.TEXT, desc = "label"),
                Cmd("gpsdata", "GPS data", "gpsdata", desc = "BFFB v2 GPS fix"),
                Cmd("nmea", "NMEA stream", "nmea"),
                Cmd("gpstracker_start", "GPS track start", "gpstracker -c start"),
                Cmd("gpstracker_stop", "GPS track stop", "gpstracker -c stop"),
                Cmd("upload", "Upload captures", "upload -d {}", ArgKind.CHOICE,
                    listOf("wdg", "wigle", "both"), desc = "WDGoWars / WiGLE"),
            ),
        ),
        Category(
            "System", listOf(
                Cmd("info", "Info", "info", desc = "firmware / board"),
                Cmd("settings", "Settings", "settings", desc = "list settings"),
                Cmd("settings_reset", "Reset settings", "settings -r"),
                Cmd("brightness", "Brightness", "brightness -s {}", ArgKind.TEXT, desc = "0–9"),
                Cmd("led", "LED colour", "led -s {}", ArgKind.TEXT, desc = "hex e.g. FF9A3D"),
                Cmd("update_sd", "Update (SD)", "update -s"),
                Cmd("update_web", "Update (web)", "update -w"),
                Cmd("reboot", "Reboot", "reboot"),
            ),
        ),
    )

    /** True when a serial line looks like a captured-handshake notification (sniffpwn). Heuristic. */
    fun isHandshakeHit(line: String): Boolean {
        val l = line.uppercase()
        return "PMKID" in l || "EAPOL" in l || "WPA HANDSHAKE" in l
    }

    /** True when a line is a live capture-progress/notify line (for console colouring). */
    private val CAP_RE_COMPLETE = Regex("""Complete EAPOL:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val CAP_RE_PWND = Regex("""Pwnd\s*#?:\s*(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Returns a human label when a serial line reports a REAL capture (a complete 4-way EAPOL
     * handshake, or a pwnagotchi/PMKID "Pwnd" hit) with a non-zero count — else null. Verified against
     * ESP32-Marauder WiFiScan.cpp which prints `Complete EAPOL: <n>` and `Pwnd #: <n>` (and the
     * partial-frame `Received EAPOL: <n>`, which we deliberately do NOT treat as a capture). The count
     * is in the label so the UI can de-dupe and only notify on a NEW capture. Captures are saved as
     * .pcap on the board's SD — no hashcat line is printed to serial.
     */
    fun captureEvent(line: String): String? {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#") || l.startsWith("›")) return null
        if (l.startsWith("Received EAPOL", ignoreCase = true)) return null // partial frame, not a capture
        CAP_RE_COMPLETE.find(l)?.let { m ->
            if ((m.groupValues[1].toIntOrNull() ?: 0) > 0) return "Full WPA handshake captured · ${m.groupValues[1]} total"
        }
        CAP_RE_PWND.find(l)?.let { m ->
            if ((m.groupValues[1].toIntOrNull() ?: 0) > 0) return "Handshake/PMKID captured · Pwnd #${m.groupValues[1]}"
        }
        if ("WPA*0" in l.uppercase()) return l // a hashcat line, if any build ever prints one
        return null
    }
}

/**
 * A live link to a Marauder board (implemented by the USB-OTG and Flipper-BLE-relay transports).
 * [send] writes one CLI command (newline appended by the transport); incoming serial lines are
 * delivered to the callback registered via [onLine]. [connected] reflects the live link.
 */
interface MarauderLink {
    val connected: Boolean
    suspend fun send(command: String)
    fun onLine(cb: (String) -> Unit)
    suspend fun disconnect()
}
