package com.foxfw.companion

/**
 * "What's on my SD" — recursively scans /ext/subghz and classifies every .sub by its flipper_format
 * `Protocol:` header (no demod needed for decoded captures). Small files are read+parsed; large ones
 * are almost always RAW captures and are bucketed by size without a slow full read. An optional
 * deep-decode pass runs the on-phone arke engine over the RAW files to name them. Pure RPC + on-phone.
 */
object SdInventory {
    private const val READ_CAP = 8192          // files <= this are read+parsed; larger => "RAW capture"
    private const val DEEP_CAP = 256 * 1024    // deep-decode only RAW files up to this size (BLE speed)

    data class Report(
        val scanned: Int,
        val byProtocol: List<Pair<String, Int>>, // label -> count, sorted desc
        val rawPaths: List<String>,              // RAW .sub paths (candidates for deep decode)
        val totalSubs: Int,
    )

    private fun field(text: String, key: String): String? =
        text.lineSequence().firstOrNull { it.trimStart().startsWith("$key:") }?.substringAfter(":")?.trim()

    private fun freqLabel(text: String): String {
        val hz = field(text, "Frequency")?.toLongOrNull() ?: return "RAW capture"
        return "RAW ${"%.2f".format(hz / 1_000_000.0)} MHz"
    }

    suspend fun scan(ble: FlipperBle, onProgress: (scanned: Int) -> Unit): Report? {
        val root = "/ext/subghz"
        val top = ble.storageList(root) ?: return null
        val buckets = HashMap<String, Int>()
        val raw = ArrayList<String>()
        val scanned = intArrayOf(0)
        walk(ble, root, top, 0, buckets, raw, scanned, onProgress)
        val sorted = buckets.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
        val total = buckets.values.sum()
        return Report(scanned[0], sorted, raw, total)
    }

    private suspend fun walk(
        ble: FlipperBle, dir: String, entries: List<Proto.FileEntry>, depth: Int,
        buckets: HashMap<String, Int>, raw: ArrayList<String>, scanned: IntArray,
        onProgress: (Int) -> Unit,
    ) {
        for (e in entries) {
            val path = if (dir == "/") "/${e.name}" else "$dir/${e.name}"
            if (e.isDir) {
                if (depth >= 8 || e.name.startsWith(".")) continue
                val sub = ble.storageList(path) ?: continue
                walk(ble, path, sub, depth + 1, buckets, raw, scanned, onProgress)
            } else if (e.name.endsWith(".sub", true)) {
                scanned[0]++
                if (scanned[0] % 5 == 0) onProgress(scanned[0])
                if (e.size > READ_CAP) {
                    buckets["RAW capture (large)"] = (buckets["RAW capture (large)"] ?: 0) + 1
                    if (e.size <= DEEP_CAP) raw.add(path)
                    continue
                }
                val data = ble.storageRead(path) ?: continue
                val text = String(data)
                val proto = field(text, "Protocol")
                val label = when {
                    proto == null -> "unknown"
                    proto.equals("RAW", true) -> { raw.add(path); freqLabel(text) }
                    else -> proto
                }
                buckets[label] = (buckets[label] ?: 0) + 1
            }
        }
    }

    /** Deep-decode the RAW captures with the arke engine; returns make/protocol -> count of what it named. */
    suspend fun deepDecode(ble: FlipperBle, rawPaths: List<String>, onProgress: (done: Int, total: Int) -> Unit): List<Pair<String, Int>> {
        val found = HashMap<String, Int>()
        rawPaths.forEachIndexed { i, p ->
            onProgress(i, rawPaths.size)
            val data = ble.storageRead(p) ?: return@forEachIndexed
            val (frames, _) = ArkeEngine.decode(String(data))
            if (frames.isEmpty()) found["(undecoded)"] = (found["(undecoded)"] ?: 0) + 1
            else for (f in frames) found[f.make] = (found[f.make] ?: 0) + 1
        }
        onProgress(rawPaths.size, rawPaths.size)
        return found.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
