package com.foxfw.companion

/** One swept channel: centre frequency (Hz) and its RSSI (dBm). */
data class SpecPoint(val freqHz: Int, val rssi: Int)

/** Parse the FAP SWEEP output ("<freqHz> <rssi>\n" per line); skips malformed lines. */
fun parseSpectrum(text: String): List<SpecPoint> = text.lineSequence().mapNotNull { ln ->
    val t = ln.trim().split(Regex("\\s+"))
    if (t.size < 2) return@mapNotNull null
    val f = t[0].toIntOrNull() ?: return@mapNotNull null
    val r = t[1].toIntOrNull() ?: return@mapNotNull null
    SpecPoint(f, r)
}.toList()

/** Frequency Finder: the strongest channel in a spectrum (null if empty). */
fun spectrumPeak(points: List<SpecPoint>): SpecPoint? = points.maxByOrNull { it.rssi }


/** A frequency handed from the Spectrum Analyzer to the Decode tab (tap a peak -> tune Decode). */
object SpecHandoff { @Volatile var tuneHz: Int? = null }

/** Top N signal peaks in a sweep: local maxima at least 6 dB over the median noise floor, strongest
 *  first, suppressing peaks within 150 kHz of a stronger one. */
fun topPeaks(points: List<SpecPoint>, n: Int = 5): List<SpecPoint> {
    if (points.size < 3) return emptyList()
    val sortedR = points.map { it.rssi }.sorted()
    val floor = sortedR[sortedR.size / 2] + 6
    val locals = ArrayList<SpecPoint>()
    for (i in 1 until points.size - 1) {
        val r = points[i].rssi
        if (r >= points[i - 1].rssi && r > points[i + 1].rssi && r >= floor) locals.add(points[i])
    }
    val out = ArrayList<SpecPoint>()
    for (p in locals.sortedByDescending { it.rssi }) {
        if (out.size >= n) break
        if (out.none { kotlin.math.abs(it.freqHz - p.freqHz) < 150000 }) out.add(p)
    }
    return out
}
