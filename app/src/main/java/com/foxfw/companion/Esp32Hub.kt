package com.foxfw.companion

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one shared ESP32 connection + the one shared console for the whole ESP32 area — the ESP32 page,
 * the six domain terminals (WiFi/BLE/GPS/IR/SubGHz/RFID) and the free-form ESP32 CLI all drive this
 * single transport and render this single `lines` buffer.
 *
 * Lifecycle (per the user): connect ONCE, on the first ESP32 page opened ([enter]); stay connected
 * across in-app navigation — leaving to the Archive and coming back does NOT reconnect; disconnect only
 * when explicitly told ([reset]) or when the app is backgrounded ([onAppBackground], wired from
 * [FoxApplication] via activity-start counting). On returning to the foreground the board reconnects if
 * it had been connected. If the user never opens the ESP32 area it never connects.
 *
 * Everything board-scoped lives here (links, mode, detection, fox/stock, targets, the capture pipeline),
 * populated by a single `onLine` wired once — so the networks list, ⚡ capture banner and auto-ship work
 * no matter which ESP32 page the output arrived on. Per-page UI (input text, scroll, command-list open,
 * portal fields) stays in the screens / [EspSession].
 */
internal object Esp32Hub {
    private var appCtx: Context? = null
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var usbLink: Esp32UsbLink? = null
    val bleLink = Esp32BleLink()

    val modeState = mutableStateOf(Esp32Mode.RELAY)
    var mode by modeState
    val usbConnectedState = mutableStateOf(false)
    var usbConnected by usbConnectedState
    val foxBoardState = mutableStateOf(false)
    var foxBoard by foxBoardState
    val detectState = mutableStateOf(EspDetect.PENDING)
    var detect by detectState
    val statusState = mutableStateOf("Connect the board: plug it into the phone (USB-OTG) or link the Flipper on the Device tab.")
    var status by statusState
    val lastSelectedApIndexState = mutableStateOf(-1)
    var lastSelectedApIndex by lastSelectedApIndexState

    // one shared console
    val lines = mutableStateListOf("Connecting…")
    val savedUpToState = mutableStateOf(0)
    var savedUpTo by savedUpToState

    // targets + capture pipeline, all fed by the single onLine/log below
    val seenAps = mutableStateMapOf<String, Esp32.Ap>()
    val selected = mutableStateMapOf<Int, Boolean>()
    val autoShipState = mutableStateOf(true)
    var autoShip by autoShipState
    val captureBannerState = mutableStateOf<String?>(null)
    var captureBanner by captureBannerState
    var lastCapture = ""
    var capTargetName: String? = null
    var capTargetRssi = 0

    private var wired = false
    private var started = false
    private var resumeOnForeground = false

    val link: Esp32Link get() = if (mode == Esp32Mode.USB) (usbLink ?: bleLink) else bleLink
    val connected: Boolean get() = if (mode == Esp32Mode.USB) usbConnected else bleLink.connected

    fun log(line: String) {
        lines.add(line)
        if (lines.size > 600) { val drop = lines.size - 600; lines.removeRange(0, drop); savedUpTo = (savedUpTo - drop).coerceAtLeast(0) }
        Esp32.parseAp(line)?.let { ap -> seenAps[ap.essid + "|" + ap.channel] = ap }
        Esp32.captureEvent(line)?.let { ev ->
            if (ev != lastCapture) {
                lastCapture = ev
                val tgt = capTargetName?.let { "$it ($capTargetRssi dBm) · " } ?: ""
                captureBanner = "$tgt$ev"
                lines.add("[⚡ CAPTURE] $tgt$ev")
                appCtx?.let { HandshakeNotifier.notifyCapture(it, "⚡ Handshake captured", "$tgt$ev — saved to SD (.pcap)") }
            }
        }
        if (autoShip) {
            Regex("""WPA\*0[12]\*\S+""").find(line)?.value?.let { cand ->
                if (WpaCrack.parsePmkid(cand) != null || WpaCrack.parseEapol(cand) != null) {
                    CrackInbox.offer(cand)
                    lines.add("[→ shipped handshake to cracker]")
                }
            }
        }
    }

    /** Create the USB link (app context) and wire both links' onLine into the shared console exactly once. */
    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
        if (usbLink == null) usbLink = Esp32UsbLink(appCtx!!)
        if (!wired) {
            val onBoardLine: (String) -> Unit = { raw ->
                if (FoxEsp32Dialect.isFoxBanner(raw)) { foxBoard = true; detect = EspDetect.FOX }
                val shown = if (foxBoard) FoxEsp32Dialect.fromFox(raw, lastSelectedApIndex) else raw
                if (shown != null) log(shown)
            }
            usbLink!!.onLine(onBoardLine); bleLink.onLine(onBoardLine)
            wired = true
        }
    }

    suspend fun sendOne(line: String, window: Int? = null) {
        val l = link
        val out = if (foxBoard) FoxEsp32Dialect.toFox(line) else FoxEsp32Dialect.Out(listOf(line))
        out.localReply?.let { log(it) }
        for (boardLine in out.send) {
            if (boardLine.startsWith("WIFISELECT:AP:"))
                boardLine.substringAfterLast(':').toIntOrNull()?.let { lastSelectedApIndex = it }
            if (l is Esp32BleLink) {
                val lc = boardLine.lowercase()
                val dump = lc.startsWith("list") || lc.startsWith("info") || lc.startsWith("settings") ||
                    lc.startsWith("sniff") || lc.startsWith("help") || lc.startsWith("gpsdata") ||
                    lc.startsWith("gps") || lc.startsWith("scan") ||
                    boardLine.startsWith("WIFISCAN") || boardLine.startsWith("WIFILIST") ||
                    boardLine.startsWith("WIFISNIFF") || boardLine.startsWith("WIFIPCAP") ||
                    boardLine.startsWith("SETTINGS") || boardLine.startsWith("GPS") ||
                    boardLine.startsWith("IRRECV") || boardLine.startsWith("RFIDSCAN") || boardLine.startsWith("SUBGHZRX")
                l.sendCapturing(boardLine, window ?: if (dump) 3500 else 1200)
            } else l.send(boardLine)
        }
    }

    // Positive-ID only: a board that never names itself stays ERROR, never NON-FOX (see EspDetect).
    private suspend fun classifyBoard() {
        detect = EspDetect.PENDING
        suspend fun probe(cmd: String, window: Int): String {
            val before = lines.size
            if (mode == Esp32Mode.USB) { sendOne(cmd); delay(window.toLong()) } else sendOne(cmd, window)
            return lines.drop(before.coerceAtMost(lines.size)).joinToString("\n").lowercase()
        }
        var r = probe("info", 1500)
        if ("fox esp32 firmware" in r) { foxBoard = true; detect = EspDetect.FOX; return }
        if ("marauder" in r || "bruce" in r) { detect = EspDetect.NONFOX; return }
        r = probe("stopscan", 1200)
        if (foxBoard) { detect = EspDetect.FOX; return }
        if ("marauder" in r || "stopscan" in r || "sniff" in r || "evilportal" in r || ("scan" in r && "stop" in r)) {
            detect = EspDetect.NONFOX; return
        }
        r = probe("AT", 800)
        if (foxBoard) { detect = EspDetect.FOX; return }
        detect = if ("at version" in r || Regex("(^|\\n)ok\\b").containsMatchIn(r)) EspDetect.NONFOX else EspDetect.ERROR
    }

    private suspend fun autoConnect(ctx: Context) {
        detect = EspDetect.PENDING
        val dev = Esp32UsbLink.find(ctx)
        if (dev != null && Esp32UsbLink.canDrive(dev)) {
            mode = Esp32Mode.USB; status = "Requesting USB permission…"
            val ul = usbLink ?: Esp32UsbLink(ctx.applicationContext).also { usbLink = it }
            if (!ul.ensurePermission(dev)) { status = "USB permission denied."; detect = EspDetect.ERROR; return }
            status = ul.connect(dev); usbConnected = ul.connected
            if (ul.connected) { delay(300); classifyBoard() } else detect = EspDetect.ERROR
        } else if (FlipperTransport.active != null) {
            mode = Esp32Mode.RELAY; status = "Relay over the Flipper (${FlipperTransport.kind}) to the GPIO board."; delay(400); classifyBoard()
        } else status = "No board. Plug the ESP32 into the phone (USB-OTG), or link the Flipper on the Device tab."
    }

    /** Called from every ESP32 screen's LaunchedEffect. Connects the first time only; later entries
     *  (including returning from another tab) reuse the live link. */
    fun enter(ctx: Context) {
        init(ctx)
        if (started) return
        started = true
        hubScope.launch { autoConnect(ctx) }
    }

    private fun disconnectNow() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { usbLink?.disconnect() }; runCatching { bleLink.stopDaemon() }
        }
    }

    /** App went to the background (no visible activity) — tear the board down; remember to reconnect on
     *  return if it had been connected. */
    fun onAppBackground() {
        if (!started && !resumeOnForeground) { /* never connected — nothing to do */ }
        resumeOnForeground = started
        started = false
        detect = EspDetect.PENDING
        usbConnected = false
        disconnectNow()
    }

    /** App returned to the foreground — reconnect only if we had been connected when backgrounded. */
    fun onAppForeground() {
        if (resumeOnForeground) { resumeOnForeground = false; appCtx?.let { enter(it) } }
    }

    /** Header refresh on any ESP32 page: wipe the shared console + board state and drop the link; the
     *  page body is recreated right after (its LaunchedEffect calls [enter] again to reconnect fresh). */
    fun reset() {
        lines.clear(); lines.add("Connecting…"); savedUpTo = 0
        seenAps.clear(); selected.clear(); captureBanner = null; lastCapture = ""
        capTargetName = null; capTargetRssi = 0
        foxBoard = false; detect = EspDetect.PENDING; usbConnected = false; lastSelectedApIndex = -1
        status = "Reconnecting…"
        disconnectNow()
        started = false
    }
}
