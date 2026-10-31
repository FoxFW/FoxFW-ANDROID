package com.foxfw.companion

/**
 * Pure URL + JSON logic for the CYD Hue BLE proxy's HTTP API. No Android types — unit-testable.
 * Endpoints (see hue-proxy-cyd firmware):
 *   /set?target=<name|mac>&on=1&bri=0..100&color=<name>   drive a bulb
 *   /state?target=<name|mac>                              read a bulb's real on/bri/color
 *   /health                                               proxy + bulb list
 *   /scan                                                 active BLE scan for Hue bulbs
 */
object HueApi {
    /** The named color presets the proxy understands (XY mapped firmware-side). */
    val COLORS = listOf("warm", "cool", "white", "red", "orange", "yellow", "green", "cyan", "blue", "purple", "pink")

    data class Bulb(val name: String, val mac: String, val on: Boolean)
    data class LightState(val on: Boolean?, val bri: Int?, val color: String?, val rssi: Int?)

    /** "hue-proxy.local" / "1.2.3.4:80/" → "http://…"; keeps an explicit http(s):// scheme. */
    fun normalizeBase(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        return s
    }

    private fun tokenSuffix(sep: Char, token: String?) = if (token.isNullOrEmpty()) "" else "${sep}token=$token"

    fun setUrl(base: String, target: String, on: Boolean?, bri: Int?, color: String?, token: String? = null): String {
        val sb = StringBuilder("$base/set?target=$target")
        if (on != null) sb.append("&on=").append(if (on) "1" else "0")
        if (bri != null) sb.append("&bri=").append(bri)
        if (!color.isNullOrEmpty()) sb.append("&color=").append(color)
        sb.append(tokenSuffix('&', token))
        return sb.toString()
    }

    fun stateUrl(base: String, target: String, token: String? = null) =
        "$base/state?target=$target" + tokenSuffix('&', token)

    fun healthUrl(base: String, token: String? = null) = "$base/health" + tokenSuffix('?', token)
    fun scanUrl(base: String, token: String? = null) = "$base/scan" + tokenSuffix('?', token)

    private val BULB = Regex(""""name"\s*:\s*"([^"]*)"\s*,\s*"mac"\s*:\s*"([^"]*)"\s*,\s*"on"\s*:\s*(true|false)""")
    fun parseHealth(json: String): List<Bulb> =
        BULB.findAll(json).map { Bulb(it.groupValues[1], it.groupValues[2], it.groupValues[3] == "true") }.toList()

    private val ON = Regex(""""on"\s*:\s*(true|false)""")
    private val BRI = Regex(""""bri"\s*:\s*(\d+)""")
    private val COLOR = Regex(""""color"\s*:\s*"([^"]*)"""")
    private val RSSI = Regex(""""rssi"\s*:\s*(-?\d+)""")

    /** Parse a /state reply; null when the proxy reported ok:false (bulb offline/unreachable). */
    fun parseState(json: String): LightState? {
        if (Regex(""""ok"\s*:\s*false""").containsMatchIn(json)) return null
        return LightState(
            on = ON.find(json)?.groupValues?.get(1)?.let { it == "true" },
            bri = BRI.find(json)?.groupValues?.get(1)?.toIntOrNull(),
            color = COLOR.find(json)?.groupValues?.get(1),
            rssi = RSSI.find(json)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }
}
