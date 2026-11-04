package com.foxfw.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Session-scoped cache of daemon reads, so navigating between screens doesn't re-launch the
 * companion FAP every time (each launch flashes the Flipper's loader wheel). Filled once per
 * connection; screens read from here instead of fetching on every composition. A manual refresh
 * (the RECEIVER card's refresh icon) and an apply still update it.
 *
 * Lives for the process lifetime: values survive tab navigation and reconnects within a session,
 * and reset only when the app is closed and reopened (new process).
 */
object CompanionCache {
    var rx by mutableStateOf<ReceiverStatus?>(null)              // cc1101 / esp32 / apps / SD, per connection
    var protocols by mutableStateOf<Int?>(null)                 // firmware protocol count — sticky all session
    var statsAnimated by mutableStateOf(false)                 // the stat-row count-up plays once per session
    var settings by mutableStateOf<FoxSettings.Cfg?>(null)      // last read/applied device settings, per connection
    var hw by mutableStateOf<String?>(null)                     // hardware id (e.g. "15.F7B9C6") — static, session-sticky
    var radioFw by mutableStateOf<String?>(null)               // radio/BLE stack FW (e.g. "1.20.0") — static, session-sticky
    var fwVersion by mutableStateOf<String?>(null)             // installed FoxFW version (e.g. "2.0.13") — static, session-sticky
    var fwLatest by mutableStateOf<String?>(null)              // latest released FoxFW tag (e.g. "v2.1") from FwRepo
    var wallpapers by mutableStateOf<Int?>(null)              // count of .xbm in /ext/wallpapers — session-sticky
    var fwInstallDate by mutableStateOf<String?>(null)         // firmware build/install date from device_info — static, session-sticky
    var connectedAtMs by mutableStateOf<Long?>(null)           // wall-clock ms when this connection came up — drives the live uptime tile
    internal var espDetect by mutableStateOf<EspDetect?>(null)  // DEVICE-page ESP32 identity (full tri-state), per connection; EspDetect is internal
    var espFwName by mutableStateOf<String?>(null)             // detected ESP32 firmware label: FoxFW / Marauder / Bruce / Unsupported

    /** Connection dropped: drop per-connection reads. The protocol count stays (firmware is fixed). */
    fun onDisconnected() {
        rx = null
        settings = null
        espDetect = null
        espFwName = null
        connectedAtMs = null
    }
}
