package com.foxfw.companion

import androidx.compose.runtime.mutableStateListOf
import com.foxfw.companion.ui.Proto
import com.foxfw.companion.ui.protoFiat
import com.foxfw.companion.ui.protoKeeloq
import com.foxfw.companion.ui.protoKia
import com.foxfw.companion.ui.protoPandora
import com.foxfw.companion.ui.protoPsa
import com.foxfw.companion.ui.protoVag

/** Live decode results captured from the Flipper CC1101, shared across Device + Decode screens. */
object DecodeFeed {
    data class Item(
        val proto: Proto, val title: String, val meta: String, val bars: Int, val tMs: Long,
        val frame: ArkeEngine.Frame, val subText: String,
    )

    val items = mutableStateListOf<Item>()
    var decodedToday = 0; private set

    /** subText = the raw .sub the frames were decoded from (kept for the fob's replay/forge). */
    fun add(frames: List<ArkeEngine.Frame>, subText: String = "") {
        for (f in frames) {
            val item = toItem(f, subText)
            items.add(0, item)
            HistoryStore.log("decode", item.title, item.meta, subText, "sub")
        }
        decodedToday += frames.size
        while (items.size > 30) items.removeAt(items.size - 1)
    }

    /** Build an Item (for the fob screen) from a frame + its .sub — e.g. opening a saved capture. */
    fun makeItem(f: ArkeEngine.Frame, subText: String): Item = toItem(f, subText)

    private fun toItem(f: ArkeEngine.Frame, subText: String): Item {
        val parts = buildList {
            f.serialHex?.let { add("serial 0x$it") }
            f.btnName?.let { add(it) } ?: f.btn?.let { add("btn $it") }
            f.cnt?.let { add("cnt 0x%X".format(it)) }
            f.gate?.let { add(it) }
        }
        val title = f.make + (if (f.gate != null) " · gate" else "")
        return Item(proto(f.make), title, parts.joinToString(" · ").ifEmpty { "decoded" },
            if (f.crcOk == true) 4 else 2, System.currentTimeMillis(), f, subText)
    }

    private fun proto(make: String): Proto {
        val m = make.uppercase()
        return when {
            "VAG" in m || "VW" in m || "AUDI" in m || "SKODA" in m || "SEAT" in m -> protoVag
            "PSA" in m || "PEUGEOT" in m || "CITRO" in m || "OPEL" in m -> protoPsa
            "FIAT" in m || "DUCATO" in m -> protoFiat
            "KIA" in m || "HYUNDAI" in m -> protoKia
            "PANDORA" in m || "DXL" in m || "SCHER" in m || "SHERIFF" in m -> protoPandora
            else -> protoKeeloq
        }
    }
}

/** "now / 12s / 3m" relative label. */
fun agoLabel(tMs: Long): String {
    val s = ((System.currentTimeMillis() - tMs) / 1000).toInt()
    return when {
        s < 3 -> "now"
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h"
    }
}
