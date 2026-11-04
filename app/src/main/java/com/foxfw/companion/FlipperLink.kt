package com.foxfw.companion

enum class LinkKind { NONE, BLE, USB }

/**
 * The transport-agnostic RPC surface the file-based RF path (FlipperRf) needs. Implemented by
 * [FlipperBle] (GATT) and [FlipperUsb] (CDC-ACM). BLE-only features (screen mirror, remote input,
 * the custom-data offload channel) stay on FlipperBle concretely — they are not part of this link.
 * Default no-op bodies let a USB impl / test fake override only what it supports.
 */
interface FlipperLink {
    val kind: LinkKind
    val connected: Boolean
    suspend fun storageMkdir(path: String): Boolean = false
    suspend fun storageDelete(path: String, recursive: Boolean = true): Boolean = false
    suspend fun storageWrite(path: String, data: ByteArray, onProgress: ((Long, Long) -> Unit)? = null): Boolean = false
    suspend fun storageRead(path: String): ByteArray? = null
    suspend fun storageList(path: String): List<Proto.FileEntry>? = null
    suspend fun storageRename(oldPath: String, newPath: String): Boolean = false
    suspend fun storageBackupCreate(archivePath: String): Boolean = false
    suspend fun storageBackupRestore(archivePath: String): Boolean = false
    suspend fun appStart(name: String, args: String): Boolean = false
    /** True if [path] exists on the Flipper SD. */
    suspend fun storageStat(path: String): Boolean = false
    /** Device-info / power-info key-value pairs, or null if unsupported on this link. */
    suspend fun deviceInfo(): List<Pair<String, String>>? = null
    suspend fun powerInfo(): List<Pair<String, String>>? = null
    suspend fun getDateTime(): Proto.DateTime? = null
    /** Arm an SD-staged firmware update; returns UpdatePrepareResult (0 = OK). -1 = no response. */
    suspend fun systemUpdate(manifestPath: String): Int = -1
    /** Reboot the Flipper. mode 0=OS, 1=DFU, 2=UPDATE (applies a staged update). */
    suspend fun systemReboot(mode: Int) {}

    /** Best-effort: back any open app/menu out to the desktop so the loader is free. No-op by default. */
    suspend fun returnToDesktop() {}

    /**
     * Launch a FAP, but if the loader is busy (a menu/app is open → appStart fails), back out to the
     * desktop and retry once. Fixes the recurring "Flipper busy — hold Back to its desktop" failure
     * across every FAP feature (SpecAn / Decode / Pandora / crackers / settings).
     */
    suspend fun appStartSafe(name: String, args: String): Boolean {
        if (appStart(name, args)) return true
        returnToDesktop()
        kotlinx.coroutines.delay(500)
        return appStart(name, args)
    }
}

/**
 * Owns the active Flipper link. Prefers USB when present (faster/more reliable), falls back to BLE
 * on unplug, else none. FlipperRf routes every op through [active].
 */
object FlipperTransport {
    @Volatile var ble: FlipperLink? = null
    @Volatile var usb: FlipperLink? = null

    val active: FlipperLink?
        get() = when {
            usb?.connected == true -> usb
            ble?.connected == true -> ble
            else -> null
        }

    val kind: LinkKind get() = active?.kind ?: LinkKind.NONE
}
