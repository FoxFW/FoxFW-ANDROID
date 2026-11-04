package com.foxfw.companion

import org.json.JSONObject

/** A fob pad button: the protocol's button code + a display label. */
data class FobButton(val code: Int, val label: String)

/** What the fob UI needs for a decoded variant (from fob_engine.fob_info). */
data class FobInfo(
    val hasCipher: Boolean,
    val route: String, // "A" (phone RAW) | "B" (Flipper protocol .sub) | "replay"
    val buttons: List<FobButton>,
    val freqHz: Int,
    val preset: String,
    val customRegs: String? = null,
    val gate: Boolean = false,
    val fwProtocol: String? = null,
)

/** Result of fob_engine.forge: a RAW .sub (Route A), a keyed protocol .sub (Route B), or an error. */
sealed class ForgeResult {
    data class Raw(val sub: String) : ForgeResult()
    data class Protocol(val sub: String, val protocol: String) : ForgeResult()
    data class Err(val msg: String) : ForgeResult()
}

/** Pure parse of fob_info JSON -> FobInfo (null on malformed). No Chaquopy/Android deps. */
fun parseFobInfo(json: String): FobInfo? = try {
    val o = JSONObject(json)
    val arr = o.optJSONArray("buttons")
    val btns = ArrayList<FobButton>()
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            btns.add(FobButton(b.optInt("code"), b.optString("label")))
        }
    }
    FobInfo(
        hasCipher = o.optBoolean("has_cipher", false),
        route = o.optString("route", "replay"),
        buttons = btns,
        freqHz = o.optInt("freq_hz", 433920000),
        preset = o.optString("preset", "AM650"),
        customRegs = if (o.isNull("custom_regs")) null else o.optString("custom_regs", ""),
        gate = o.optBoolean("gate", false),
        fwProtocol = if (o.isNull("fw_protocol")) null else o.optString("fw_protocol", ""),
    )
} catch (t: Throwable) {
    null
}

/** The counter the fob should send first/next: captured + 1 (the next rolling code the receiver
 *  will accept — the captured counter itself has already been consumed). */
fun fobNextCounter(captured: Long?): Long = (captured ?: 0L) + 1

/** Pure parse of forge JSON -> ForgeResult. */
fun parseForge(json: String): ForgeResult = try {
    val o = JSONObject(json)
    when {
        o.has("error") -> ForgeResult.Err(o.optString("error", "forge error"))
        o.optString("route") == "B" -> ForgeResult.Protocol(o.optString("sub"), o.optString("protocol"))
        o.has("sub") -> ForgeResult.Raw(o.optString("sub"))
        else -> ForgeResult.Err("no sub in forge result")
    }
} catch (t: Throwable) {
    ForgeResult.Err(t.message ?: "parse error")
}
