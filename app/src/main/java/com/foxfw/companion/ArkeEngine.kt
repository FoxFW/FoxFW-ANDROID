package com.foxfw.companion

import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject

/** Kotlin bridge to the arke automotive/gate decode+clone engine (runs in Chaquopy Python). */
object ArkeEngine {
    /** Distinct Sub-GHz protocols in the FoxFW 2.1 firmware registry (_protomap.json = 154 entries;
     *  150 decodable). Shown on the dashboard as "Protocols" — the breadth the Flipper can decode.
     *  decoderCount() below is the on-phone arke RAW-decode classes (a subset), used by the crack tool. */
    const val PROTOCOL_COUNT = 154
    data class Frame(
        val make: String,
        val serialHex: String?,
        val cnt: Long?,
        val btn: Int?,
        val btnName: String?,
        val next: String?,       // next-code (hex string) when the decoder computes it
        val nextCnt: Long?,
        val crcOk: Boolean?,
        val gate: String?,
    )

    val available: Boolean get() = try { Python.isStarted() } catch (_: Throwable) { false }

    private fun mod() = Python.getInstance().getModule("phone_arke")

    fun decoderCount(): Int = try { mod().callAttr("decoder_count").toInt() } catch (_: Throwable) { 0 }

    fun families(): List<String> = try {
        val a = JSONArray(mod().callAttr("families").toString())
        List(a.length()) { a.getString(it) }
    } catch (_: Throwable) { emptyList() }

    /** Barrier / gate fixed-code brute-forcer (CAME/NICE/Holtek/Princeton/Linear/Chamberlain). */
    object Barriers {
        data class Proto(val name: String, val bits: Int, val te: Int, val codes: Int)
        private fun mod() = Python.getInstance().getModule("barriers")
        fun protocols(): List<Proto> = try {
            val a = JSONArray(mod().callAttr("protocols").toString())
            List(a.length()) { val o = a.getJSONObject(it); Proto(o.getString("name"), o.getInt("bits"), o.getInt("te"), o.getInt("codes")) }
        } catch (_: Throwable) { emptyList() }
        fun bruteSub(name: String, freqHz: Int = 433920000): String? = try {
            val s = mod().callAttr("brute_sub", name, 2, freqHz).toString()
            if (s.trimStart().startsWith("{")) null else s
        } catch (_: Throwable) { null }
        fun oneSub(name: String, code: Int, freqHz: Int = 433920000): String? = try {
            val s = mod().callAttr("one_sub", name, code, 8, freqHz).toString()
            if (s.trimStart().startsWith("{")) null else s
        } catch (_: Throwable) { null }
        /** De Bruijn brute: one continuous stream covering every code (far shorter than ascending). */
        fun debruijnSub(name: String, freqHz: Int = 433920000): String? = try {
            val s = mod().callAttr("debruijn_sub", name, 1, freqHz).toString()
            if (s.trimStart().startsWith("{")) null else s
        } catch (_: Throwable) { null }
    }

    /** KeeLoq forge+TX: crack a decoded .sub vs the embedded 204-key store, render the next rolling
     *  code as a transmittable RAW .sub (phone_keeloq.py). */
    object Keeloq {
        private fun mod() = Python.getInstance().getModule("phone_keeloq")
        data class Forge(val ok: Boolean, val mf: String?, val serial: String?, val btn: Int, val cnt: Int, val freqHz: Int, val sub: String?, val err: String?)
        fun forge(subText: String, btn: Int = -1, counter: Int = -1): Forge = try {
            val o = JSONObject(mod().callAttr("forge_sub", subText, btn, counter).toString())
            if (o.getBoolean("ok")) Forge(true, o.getString("mf"), o.getString("serial"), o.getInt("btn"), o.getInt("cnt"), o.optInt("freq", 433920000), o.getString("sub"), null)
            else Forge(false, null, null, -1, -1, 433920000, null, o.optString("error", "forge failed"))
        } catch (e: Throwable) { Forge(false, null, null, -1, -1, 433920000, null, e.message) }
    }

    /** Renault V1 (Hitag2) render bridge: a forged 11-byte frame -> transmittable RAW .sub
     *  (phone_renault.py; Manchester TE=125us, firmware-exact structure). */
    object Renault {
        private fun mod() = Python.getInstance().getModule("phone_renault")
        fun renderSub(frameHex: String, freqHz: Int = 433920000, preset: String = "FuriHalSubGhzPreset2FSKDev476Async"): String? = try {
            val o = JSONObject(mod().callAttr("render_sub", frameHex, freqHz, preset).toString())
            if (o.getBoolean("ok")) o.getString("sub") else null
        } catch (_: Throwable) { null }
    }

    /** Fiat V1 (Hitag2 RKE) render bridge: forge+render the next wire frame to a RAW .sub
     *  (phone_fiat.py; firmware-exact 13-byte build_raw + Manchester, TE variant A/B). */
    object Fiat {
        private fun mod() = Python.getInstance().getModule("phone_fiat")
        fun renderSub(uid: Int, button: Int, control: Int, auth: Long, variant: String = "B", freqHz: Int = 433920000, preset: String = "FuriHalSubGhzPresetOok650Async"): String? = try {
            val o = JSONObject(mod().callAttr("render_sub", uid, button, control, auth, variant, freqHz, preset).toString())
            if (o.getBoolean("ok")) o.getString("sub") else null
        } catch (_: Throwable) { null }
    }

    /** Fob info for a decoded variant (make string) — route/buttons/freq/preset, or null. */
    fun fobInfo(make: String): FobInfo? = try {
        parseFobInfo(mod().callAttr("fob_info", make).toString())
    } catch (_: Throwable) { null }

    /** Forge a frame for (make, serial, counter, button) → RAW/Protocol .sub or an error. */
    fun forge(make: String, serialHex: String, counter: Long, button: Int): ForgeResult = try {
        parseForge(mod().callAttr("forge", make, serialHex, counter, button).toString())
    } catch (t: Throwable) { ForgeResult.Err(t.message ?: "python error") }

    /** Decode a bundled VAG capture — on-device self-test of the full pipeline. */
    fun decodeSample(): Pair<List<Frame>, String?> {
        val js = try { mod().callAttr("decode_sample").toString() }
        catch (t: Throwable) { return emptyList<Frame>() to (t.message ?: "python error") }
        return parse(js)
    }

    /** Decode a Flipper RAW .sub (text) through all makes+gates. Returns frames or empty. */
    fun decode(subText: String): Pair<List<Frame>, String?> {
        val js = try { mod().callAttr("decode_text", subText).toString() }
        catch (t: Throwable) { return emptyList<Frame>() to (t.message ?: "python error") }
        return parse(js)
    }

    private fun parse(js: String): Pair<List<Frame>, String?> {
        return try {
            val trimmed = js.trimStart()
            if (trimmed.startsWith("{")) {
                val o = JSONObject(js)
                emptyList<Frame>() to (o.optString("error", "no frames"))
            } else {
                val arr = JSONArray(js)
                val out = ArrayList<Frame>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        Frame(
                            make = o.optString("make", "?"),
                            serialHex = o.optString("serial_hex", o.opt("serial")?.toString()),
                            cnt = if (o.isNull("cnt")) null else o.optLong("cnt"),
                            btn = if (o.isNull("btn")) null else o.optInt("btn"),
                            btnName = if (o.isNull("btn_name")) null else o.optString("btn_name"),
                            next = if (o.isNull("next")) null else o.get("next").let { n ->
                                if (n is Number) "%X".format(n.toLong()) else n.toString()
                            },
                            nextCnt = if (o.isNull("next_cnt")) null else o.optLong("next_cnt"),
                            crcOk = if (o.isNull("crc_ok")) null else o.optBoolean("crc_ok"),
                            gate = if (o.isNull("gate")) null else o.optString("gate"),
                        )
                    )
                }
                out to null
            }
        } catch (t: Throwable) {
            emptyList<Frame>() to (t.message ?: "parse error")
        }
    }
}
