package com.foxfw.companion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * FoxFW firmware updater. Reads the GitHub releases list (up to 25, newest first), keeps the LATEST
 * one downloaded in the background so it's ready to flash unprompted, and lets the user manually
 * download + install any older release too.
 *
 * Each release's packages live in their OWN folder — fwDir/<tag>/ — holding the real GitHub asset
 * files (flipper-z-f7-update-foxfw-<tag>.tgz / .tar). Same-named assets from different releases never
 * collide, and the folder is human-browsable so you can see exactly what's downloaded.
 *
 * Two install paths (the screen picks per connection):
 *   • RPC install     — the .tgz: phone unpacks it, stages files to /ext/update over RPC, systemUpdate + reboot.
 *   • Flipper install — the .tar: sent whole to the SD; the companion daemon extracts it on-device → updater_app.
 *
 * Releases: https://github.com/FoxFW/flipperzero-firmware/releases
 */
object FwRepo {
    private const val API_LIST = "https://api.github.com/repos/FoxFW/flipperzero-firmware/releases?per_page=25"
    private const val PREFS = "foxfw_prefs"
    private const val K_TAG = "fw_latest_tag"
    private const val K_FILETAG = "fw_local_tag"
    private const val K_CHECKED = "fw_last_checked"
    private const val K_DISMISS = "fw_dismissed_tag"
    private const val THROTTLE_MS = 30L * 60 * 1000 // auto-checks reuse a result within 30 min (rate-limit friendly)

    data class Release(
        val tag: String,
        val publishedAt: Long,
        val tgzUrl: String?, val tgzSize: Long, val tgzName: String?,
        val tarUrl: String?, val tarSize: Long, val tarName: String?,
    )

    /** Live download progress for the UI. */
    data class DlProgress(val done: Long, val total: Long, val bytesPerSec: Long) {
        val pct: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val finished: Boolean get() = total > 0 && done >= total
    }

    @Volatile var releases: List<Release> = emptyList(); private set
    @Volatile var latestTag: String? = null; private set
    @Volatile var downloadedTag: String? = null; private set
    @Volatile var lastChecked: Long = 0L; private set
    @Volatile var busy: Boolean = false; private set
    @Volatile var lastError: String? = null; private set
    private val lock = Mutex()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        latestTag = p.getString(K_TAG, null)
        downloadedTag = p.getString(K_FILETAG, null)
        lastChecked = p.getLong(K_CHECKED, 0L)
    }

    // ---- storage layout: one folder per release -------------------------------------------------
    fun fwDir(ctx: Context): File =
        (ctx.getExternalFilesDir("firmware") ?: File(ctx.filesDir, "firmware")).apply { mkdirs() }
    fun storageDir(ctx: Context): String = fwDir(ctx).absolutePath

    private fun safeTag(tag: String) = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
    fun releaseDir(ctx: Context, tag: String): File = File(fwDir(ctx), safeTag(tag)).apply { mkdirs() }

    fun localTgzFor(ctx: Context, tag: String): File? =
        releaseDir(ctx, tag).listFiles()?.firstOrNull { it.name.endsWith(".tgz") && it.length() > 0 }
    fun localTarFor(ctx: Context, tag: String): File? =
        releaseDir(ctx, tag).listFiles()?.firstOrNull { it.name.endsWith(".tar") && it.length() > 0 }
    fun isDownloaded(ctx: Context, tag: String): Boolean =
        localTgzFor(ctx, tag) != null || localTarFor(ctx, tag) != null

    // latest-release convenience (top card + connect-time popup)
    fun localTgz(ctx: Context): File? = latestTag?.let { localTgzFor(ctx, it) }
    fun localTar(ctx: Context): File? = latestTag?.let { localTarFor(ctx, it) }

    // ---- GitHub releases ------------------------------------------------------------------------
    // Only the canonical github-actions "-foxfw-" build (the real FoxFW firmware ~13.6 MB), never the
    // odd 8.3 MB side-releases that have no -foxfw- asset.
    private fun pickAsset(assets: JSONArray, suffix: String): Triple<String, Long, String>? {
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            if (name.startsWith("flipper-z-f7-update-") && name.contains("-foxfw-") && name.endsWith(suffix)) {
                return Triple(a.getString("browser_download_url"), a.optLong("size", 0), name)
            }
        }
        return null
    }

    // Real FoxFW firmware tags look like v2.0.13 / foxfw-v2.1. Excludes non-version tags ("Releases")
    // and the pre-1.x tags ("0.25") the user doesn't want listed.
    private fun isFoxFwVersionTag(tag: String): Boolean {
        val m = Regex("(?i)v?(\\d+)\\.(\\d+)").find(tag) ?: return false
        return (m.groupValues[1].toIntOrNull() ?: 0) >= 1
    }

    private fun parseRelease(o: JSONObject): Release? {
        val tag = o.optString("tag_name", "")
        if (tag.isEmpty() || o.optBoolean("draft", false)) return null
        if (!isFoxFwVersionTag(tag)) return null
        val assets = o.optJSONArray("assets") ?: JSONArray()
        val tgz = pickAsset(assets, ".tgz"); val tar = pickAsset(assets, ".tar")
        if (tgz == null && tar == null) return null
        val published = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(o.optString("published_at"))?.time ?: 0L
        }.getOrDefault(0L)
        return Release(
            tag, published,
            tgz?.first, tgz?.second ?: 0, tgz?.third,
            tar?.first, tar?.second ?: 0, tar?.third,
        )
    }

    /** Fetch the recent releases (newest first, <=25). Throttled unless [force]; reuses a recent
     *  result so automatic checks don't burn the unauthenticated GitHub rate limit (60/hr/IP). */
    suspend fun fetchReleases(ctx: Context, force: Boolean = false): List<Release>? = lock.withLock {
        if (!force && releases.isNotEmpty() && System.currentTimeMillis() - lastChecked < THROTTLE_MS) {
            lastError = null; return@withLock releases
        }
        val list = withContext(Dispatchers.IO) {
            try {
                val c = (URL(API_LIST).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000; readTimeout = 12000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "FoxFW-Companion")
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
                c.disconnect()
                when {
                    code == 403 || code == 429 -> { lastError = "GitHub rate limit reached — the app checks on its own, try again in a little while."; null }
                    code !in 200..299 -> { lastError = "Couldn't reach GitHub (HTTP $code) — try again."; null }
                    else -> {
                        val arr = JSONArray(text)
                        val out = ArrayList<Release>()
                        for (i in 0 until arr.length()) parseRelease(arr.getJSONObject(i))?.let { out.add(it) }
                        if (out.isEmpty()) { lastError = "No f7 update packages found in the releases."; null } else out
                    }
                }
            } catch (e: Exception) {
                lastError = "Couldn't reach GitHub — check your connection and try again."; null
            }
        } ?: return@withLock null
        releases = list
        latestTag = list.first().tag
        lastChecked = System.currentTimeMillis()
        lastError = null
        prefs(ctx).edit().putString(K_TAG, latestTag).putLong(K_CHECKED, lastChecked).apply()
        list
    }

    /** Fast version check (no download). [force] bypasses the throttle. True if a latest tag is known. */
    suspend fun checkOnly(ctx: Context, force: Boolean = false): Boolean {
        load(ctx)
        return fetchReleases(ctx, force) != null
    }

    fun releaseFor(tag: String): Release? = releases.firstOrNull { it.tag == tag }

    // ---- downloads ------------------------------------------------------------------------------
    /** Download one release's packages into its folder, reporting combined progress. No-op parts for
     *  files already present. Returns true if at least one package is available afterwards. */
    suspend fun downloadRelease(ctx: Context, rel: Release, onProgress: (DlProgress) -> Unit): Boolean = lock.withLock {
        busy = true
        try {
            val dir = releaseDir(ctx, rel.tag)
            val total = (rel.tgzSize + rel.tarSize).coerceAtLeast(1)
            if (rel.tgzSize + rel.tarSize > 0 && dir.usableSpace < rel.tgzSize + rel.tarSize + 16L * 1024 * 1024) {
                lastError = "Not enough phone storage for ${rel.tag} (~${(rel.tgzSize + rel.tarSize) / (1024 * 1024)} MB needed)."
                return@withLock false
            }
            var done = 0L
            var lastBytes = 0L; var lastT = System.currentTimeMillis(); var speed = 0L
            onProgress(DlProgress(0, total, 0))
            val sink: (Long) -> Unit = { inc ->
                done += inc
                val now = System.currentTimeMillis()
                if (now - lastT >= 400) { speed = ((done - lastBytes) * 1000) / (now - lastT); lastBytes = done; lastT = now }
                onProgress(DlProgress(done.coerceAtMost(total), total, speed))
            }
            val okTgz = downloadTo(dir, rel.tgzUrl, rel.tgzSize, rel.tgzName, sink)
            val okTar = downloadTo(dir, rel.tarUrl, rel.tarSize, rel.tarName, sink)
            if (!okTgz && !okTar) { lastError = "Download failed — check your connection or free space, then retry."; return@withLock false }
            onProgress(DlProgress(total, total, speed))
            if (rel.tag == latestTag) { downloadedTag = rel.tag; prefs(ctx).edit().putString(K_FILETAG, rel.tag).apply() }
            true
        } finally { busy = false }
    }

    private suspend fun downloadTo(
        dir: File, url: String?, size: Long, name: String?, sink: (Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (url == null || name == null) return@withContext false
        val target = File(dir, name)
        if (target.exists() && size > 0 && target.length() == size) { sink(size); return@withContext true } // already have it
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 30000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "FoxFW-Companion")
            }
            if (c.responseCode !in 200..299) { c.disconnect(); return@withContext false }
            val tmp = File(dir, "$name.part")
            c.inputStream.use { ins ->
                tmp.outputStream().use { outs ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        outs.write(buf, 0, n); sink(n.toLong())
                    }
                }
            }
            c.disconnect()
            if (size > 0 && tmp.length() != size) { tmp.delete(); return@withContext false }
            tmp.renameTo(target)
            true
        } catch (e: Exception) { false }
    }

    /** Ensure the LATEST release is downloaded (background prefetch). No-op if already held. */
    suspend fun ensureDownloaded(ctx: Context): Boolean {
        val tag = latestTag ?: return false
        if (isDownloaded(ctx, tag)) {
            downloadedTag = tag; prefs(ctx).edit().putString(K_FILETAG, tag).apply(); return true
        }
        val rel = releaseFor(tag) ?: return false
        return downloadRelease(ctx, rel) {}
    }

    /** Background sync (app load): throttled check, then prefetch the latest. */
    suspend fun sync(ctx: Context, force: Boolean = false) {
        if (checkOnly(ctx, force)) ensureDownloaded(ctx)
    }

    // ---- update-available logic (top card + connect popup) --------------------------------------
    fun updateAvailable(ctx: Context, installed: String?): Boolean {
        val tag = latestTag ?: prefs(ctx).getString(K_TAG, null) ?: return false
        if (!isDownloaded(ctx, tag)) return false
        return installed != null && isNewer(tag, installed)
    }

    fun dismissedTag(ctx: Context): String? = prefs(ctx).getString(K_DISMISS, null)
    fun dismissPopup(ctx: Context) {
        prefs(ctx).edit().putString(K_DISMISS, latestTag ?: "").apply()
    }

    /** Numeric version compare: latest > installed → true. installed null → false (unknown). */
    fun isNewer(latest: String, installed: String?): Boolean {
        if (installed == null) return false
        val a = nums(latest); val b = nums(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
    private fun nums(v: String) = Regex("""\d+""").findAll(v).map { it.value.toInt() }.toList()
}
