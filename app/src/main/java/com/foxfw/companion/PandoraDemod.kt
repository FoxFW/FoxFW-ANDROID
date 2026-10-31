package com.foxfw.companion

import com.chaquo.python.Python
import org.json.JSONArray

/**
 * Demodulates a Flipper RAW .sub (captured by FlipperRf.rx over the Flipper CC1101)
 * into the 9 on-air Pandora data bytes, via the pandora_demod Python module (Chaquopy).
 * The recovered bytes feed PandoraNative.decodeFrame() to get serial + counter + command.
 * Round-trip proven bit-exact (clean + jittered) against the forge→pulses path.
 */
object PandoraDemod {
    val available: Boolean get() = try { Python.isStarted() } catch (_: Throwable) { false }

    private fun mod() = Python.getInstance().getModule("pandora_demod")

    /** model: 0 = D-605, 1 = MAX. Returns candidate frames as 18-char hex strings. */
    fun candidates(subText: String, model: Int): List<String> = try {
        val a = JSONArray(mod().callAttr("demod_json", subText, model).toString())
        List(a.length()) { a.getString(it) }
    } catch (_: Throwable) { emptyList() }
}
