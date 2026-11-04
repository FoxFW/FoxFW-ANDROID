package com.foxfw.companion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-lifetime painter state, so leaving the Painter tool (to another tab/tool) and coming back
 * keeps the artwork — the bitmap and name live here, not in the composable.
 */
object PainterState {
    const val PSIZE = 1024          // 128x64 @ 1bpp
    val user = ByteArray(PSIZE)     // XBM bit order: bit = (user[y*16 + x/8] >> (x&7)) & 1
    var name: String = "MyWallpaper"
}

/**
 * Live mirror of the painter whiteboard onto the Flipper screen. start() launches the companion FAP
 * into resident PAINT mode (which disregards the hang watchdog); push() streams the current 1024-byte
 * frame; stopAsync() tells the FAP to leave paint mode and frees the loader.
 */
object PainterLink {
    private const val FAP = "/ext/apps/System/fox_android_companion.fap"
    private const val DIR = "/ext/apps_data/fox_companion"
    private const val BIN = "$DIR/paint.bin"
    private const val STOP = "$DIR/paint.stop"
    private const val GONE = "$DIR/paint.gone"   // FAP touches this on exit (Back) -> we know it closed
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Launch the FAP into paint mode and push the first frame. Returns true if it launched.
     *  appStartSafe (not plain appStart) so a loader busy with a menu/app is backed out + retried.
     *  Clears the stop + gone markers first so the fresh FAP starts clean. */
    suspend fun start(fle: FlipperLink, frame: ByteArray): Boolean {
        fle.storageMkdir(DIR)
        fle.storageDelete(STOP)
        fle.storageDelete(GONE)
        val ok = fle.appStartSafe(FAP, "PAINT")
        if (ok) push(fle, frame)
        return ok
    }

    /** True once the FAP has left paint mode (the user pressed Back on the Flipper). Lets the mirror
     *  tell "running, just push" apart from "closed, reopen only when the user draws again" — so we
     *  never relaunch (and never flash the loading wheel) while it's happily resident. */
    suspend fun isGone(fle: FlipperLink): Boolean =
        runCatching { fle.storageStat(GONE) }.getOrDefault(false)

    suspend fun push(fle: FlipperLink, frame: ByteArray): Boolean =
        runCatching { fle.storageWrite(BIN, frame, null) }.getOrDefault(false)

    /** Best-effort: leave paint mode (called when the Painter screen is disposed). Retries so a single
     *  dropped write doesn't leave the FAP stuck resident with the Flipper's buttons captured. */
    fun stopAsync(fle: FlipperLink) {
        scope.launch {
            repeat(4) {
                val ok = runCatching { fle.storageWrite(STOP, byteArrayOf('x'.code.toByte()), null) }.getOrDefault(false)
                if (ok) return@launch
                kotlinx.coroutines.delay(200)
            }
        }
    }
}
