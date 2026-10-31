package com.foxfw.companion

import java.io.File

/**
 * Backup helpers over the existing storage RPC — no firmware changes.
 *  - SD data backup: recursively pull the /ext tree to a local folder (the user's captures live here).
 *  - Device backup: the native backup_create/restore RPC archives /int (pairing, settings, dolphin).
 */
object BackupBackend {

    /** Result of an SD backup walk. */
    data class SdResult(val files: Int, val bytes: Long, val skipped: Int, val failed: Int)

    /**
     * Recursively mirror [remoteRoot] on the Flipper SD into [localRoot] on the phone.
     * Files larger than [maxFileBytes] are skipped (counted) so one huge RAW capture can't stall BLE
     * for many minutes — grab those individually in the File Manager. Honors [stop] between files.
     */
    suspend fun backupSd(
        ble: FlipperBle,
        remoteRoot: String,
        localRoot: File,
        maxFileBytes: Long,
        stop: () -> Boolean,
        onProgress: (files: Int, bytes: Long, current: String) -> Unit,
    ): SdResult {
        localRoot.mkdirs()
        val acc = intArrayOf(0, 0, 0) // files, skipped, failed
        val total = longArrayOf(0L)
        walk(ble, remoteRoot, localRoot, 0, maxFileBytes, stop, acc, total, onProgress)
        return SdResult(acc[0], total[0], acc[1], acc[2])
    }

    private suspend fun walk(
        ble: FlipperBle, remoteDir: String, localDir: File, depth: Int, maxBytes: Long,
        stop: () -> Boolean, acc: IntArray, total: LongArray,
        onProgress: (Int, Long, String) -> Unit,
    ) {
        if (depth > 8 || stop()) return
        val entries = ble.storageList(remoteDir) ?: return
        for (e in entries) {
            if (stop()) return
            val remotePath = if (remoteDir == "/") "/${e.name}" else "$remoteDir/${e.name}"
            if (e.isDir) {
                if (e.name.startsWith(".")) continue
                walk(ble, remotePath, File(localDir, e.name).apply { mkdirs() }, depth + 1, maxBytes, stop, acc, total, onProgress)
            } else {
                if (e.size > maxBytes) { acc[1]++; continue }
                onProgress(acc[0], total[0], e.name)
                val bytes = ble.storageRead(remotePath)
                if (bytes == null) { acc[2]++; continue }
                runCatching { File(localDir, e.name).writeBytes(bytes) }
                    .onSuccess { acc[0]++; total[0] += bytes.size }
                    .onFailure { acc[2]++ }
            }
        }
    }
}
