package com.foxfw.companion

/**
 * Pure WiFi/BLE band-plan math for the phone Band Analyzer — no Android deps, unit-testable.
 *
 * A phone cannot read raw RF spectrum (Android doesn't expose spectral scan to apps), so the
 * "spectrum" here is ACTIVITY the phone's own radios can see: WiFi APs mapped onto their channel,
 * and BLE advertisers binned across 2.4 GHz. This object turns raw scan observations into the
 * per-channel grid the UI draws.
 */
object BandScan {
    enum class Band(val label: String) { GHZ24("2.4 GHz"), GHZ5("5 GHz") }

    /** One AP seen in a WiFi scan. */
    data class Ap(val ssid: String, val bssid: String, val freqMhz: Int, val rssi: Int)

    /** Aggregated load on one channel of the grid. */
    data class ChannelLoad(
        val channel: Int,
        val centerMhz: Int,
        val band: Band,
        val apCount: Int,
        val maxRssi: Int,
        val topSsid: String,
    )

    /** 2.4 GHz WiFi channels 1..14. */
    val channels24: List<Int> = (1..14).toList()

    /** 5 GHz WiFi 20 MHz channel centers (UNII-1/2/2e/3). */
    val channels5: List<Int> = listOf(
        36, 40, 44, 48, 52, 56, 60, 64,
        100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
        149, 153, 157, 161, 165,
    )

    /** WiFi frequency (MHz) -> channel number, or null if it isn't a 2.4/5 GHz WiFi channel center. */
    fun freqToChannel(mhz: Int): Int? = when {
        mhz == 2484 -> 14 // ch14 is the one 2.4 GHz channel not on the 5 MHz grid
        mhz in 2412..2472 && (mhz - 2412) % 5 == 0 -> (mhz - 2407) / 5
        mhz in 5000..5895 && (mhz - 5000) % 5 == 0 -> (mhz - 5000) / 5
        else -> null
    }

    /** Channel number -> its center frequency (MHz). */
    fun channelToCenter(ch: Int, band: Band): Int = when (band) {
        Band.GHZ24 -> if (ch == 14) 2484 else 2407 + ch * 5
        Band.GHZ5 -> 5000 + ch * 5
    }

    /** Which band a frequency (MHz) is in, or null (e.g. 6 GHz, out of scope). */
    fun bandOf(mhz: Int): Band? = when (mhz) {
        in 2400..2500 -> Band.GHZ24
        in 5000..5900 -> Band.GHZ5
        else -> null
    }

    /** RSSI (dBm) -> 0..1 bar fraction. -90 dBm floor .. -30 dBm full-scale. */
    fun rssiToFraction(dbm: Int): Float = ((dbm + 90).toFloat() / 60f).coerceIn(0f, 1f)

    /**
     * Fold the observed APs onto the full channel grid for [band]. Every channel in the grid is
     * returned (count 0 / floor RSSI when nothing is on it), so the UI draws a complete band.
     * APs in the other band are ignored.
     */
    fun aggregate(aps: List<Ap>, band: Band): List<ChannelLoad> {
        val chans = if (band == Band.GHZ24) channels24 else channels5
        val byCh = HashMap<Int, MutableList<Ap>>()
        for (ap in aps) {
            if (bandOf(ap.freqMhz) != band) continue
            val ch = freqToChannel(ap.freqMhz) ?: continue
            byCh.getOrPut(ch) { ArrayList() }.add(ap)
        }
        return chans.map { ch ->
            val list = byCh[ch].orEmpty()
            val top = list.maxByOrNull { it.rssi }
            ChannelLoad(
                channel = ch,
                centerMhz = channelToCenter(ch, band),
                band = band,
                apCount = list.size,
                maxRssi = top?.rssi ?: -127,
                topSsid = top?.let { if (it.ssid.isBlank()) "(hidden)" else it.ssid } ?: "",
            )
        }
    }
}
