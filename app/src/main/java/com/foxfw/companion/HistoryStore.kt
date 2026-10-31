package com.foxfw.companion

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent, searchable log of decode/crack/capture events. Stored as JSON in the app's external
 * files dir (history.json), kept newest-first, capped at [CAP]. Compose-observable so the History
 * screen updates live. Entirely phone-side — no firmware involvement.
 */
object HistoryStore {
    data class Entry(
        val tMs: Long,
        val kind: String,        // "decode" | "crack" | "capture"
        val title: String,
        val summary: String,
        val payload: String?,    // the .sub text / report to re-share, if any
        val ext: String,         // suggested extension for a shared payload ("sub","txt")
    )

    private const val CAP = 200
    val entries = mutableStateListOf<Entry>()
    private var loaded = false
    private val file: File get() = File(FoxApplication.instance.getExternalFilesDir(null), "history.json")

    @Synchronized fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return@runCatching
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) {
                // parse each record independently — one corrupt entry must not drop the rest
                runCatching {
                    val o = a.getJSONObject(i)
                    entries.add(
                        Entry(o.getLong("t"), o.getString("k"), o.getString("ti"), o.getString("s"),
                            if (o.isNull("p")) null else o.getString("p"), o.optString("e", "txt"))
                    )
                }
            }
        }
    }

    @Synchronized private fun save() {
        runCatching {
            val a = JSONArray()
            for (e in entries) a.put(
                JSONObject().put("t", e.tMs).put("k", e.kind).put("ti", e.title)
                    .put("s", e.summary).put("p", e.payload ?: JSONObject.NULL).put("e", e.ext)
            )
            // atomic write: a kill mid-write can't truncate history.json (next load would then drop
            // everything after the truncation point). Write a temp file, then rename over the target.
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(file)) { file.writeText(a.toString()); tmp.delete() }
        }
    }

    @Synchronized fun log(kind: String, title: String, summary: String, payload: String? = null, ext: String = "txt") {
        ensureLoaded()
        entries.add(0, Entry(System.currentTimeMillis(), kind, title, summary, payload?.ifBlank { null }, ext))
        while (entries.size > CAP) entries.removeAt(entries.size - 1)
        save()
    }

    @Synchronized fun clear() {
        ensureLoaded(); entries.clear(); save()
    }

    /** A plain-text dump of the whole history, for "Export all". */
    fun exportText(): String = buildString {
        appendLine("FoxFW companion — history (${entries.size} entries)")
        appendLine()
        for (e in entries) {
            appendLine("[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.tMs))}] ${e.kind.uppercase()} — ${e.title}")
            appendLine("  ${e.summary}")
        }
    }
}
