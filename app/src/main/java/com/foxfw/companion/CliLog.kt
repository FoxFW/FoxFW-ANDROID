package com.foxfw.companion

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends CLI output to a single running per-day log file on external app storage
 * (…/Android/data/com.foxfw.companion/files/cli_logs/Flipper_Cli_Log_YYYYMMDD.txt) — one file per
 * day, accumulating across sessions, not per-session files.
 */
object CliLog {
    fun file(ctx: Context): File {
        val dir = (ctx.getExternalFilesDir("cli_logs") ?: File(ctx.filesDir, "cli_logs")).apply { mkdirs() }
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return File(dir, "Flipper_Cli_Log_$date.txt")
    }

    /** Append the given lines to today's log; returns the file path, or null if nothing/failed. */
    fun append(ctx: Context, lines: List<String>): String? {
        if (lines.isEmpty()) return null
        return runCatching {
            val f = file(ctx)
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText(lines.joinToString("\n", prefix = "----- $ts -----\n", postfix = "\n\n"))
            f.absolutePath
        }.getOrNull()
    }
}
