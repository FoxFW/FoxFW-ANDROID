package com.foxfw.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Reachability + command-banking for the Fox companion daemon (fox_android_companion.fap).
 *
 * A running FAP holds the Flipper's loader lock, so only one app can run at a time. If the user
 * leaves the companion and opens another app on the Flipper, the daemon can't launch and any
 * change we try to push can't be applied. Rather than freeze or silently drop it, we:
 *   - flip [accessDenied] true  -> the DEVICE/TOOLS screens show a persistent banner above the nav,
 *   - [bank] the change so it can be replayed when the Flipper is back on its desktop,
 *   - [flush] the banked changes (in order) once access returns; anything still unsent is kept
 *     queued until the connection comes back, and is lost only if it never does.
 *
 * Read-only polls (RXSTATUS etc.) don't bank — they just [report] whether the daemon answered, so
 * the banner reflects live reachability.
 */
object CompanionLink {
    var accessDenied by mutableStateOf(false)
        private set
    var pendingCount by mutableIntStateOf(0)
        private set

    private data class Banked(val label: String, val action: suspend () -> Boolean)
    private val queue = ArrayDeque<Banked>()
    private val lock = Any()

    /** Outcome of a companion op. ok=true means the daemon answered -> access is back. */
    fun report(ok: Boolean) {
        accessDenied = if (ok) false else true
    }

    /** A change couldn't reach the daemon: remember it to replay when the Flipper is back. */
    fun bank(label: String, action: suspend () -> Boolean) {
        synchronized(lock) {
            // one entry per label — the latest intent for a given change supersedes an older one
            queue.removeAll { it.label == label }
            queue.addLast(Banked(label, action))
            pendingCount = queue.size
        }
        accessDenied = true
    }

    val hasPending: Boolean get() = synchronized(lock) { queue.isNotEmpty() }

    /** Replay banked changes in order; stop at the first that still fails. Clears denial when empty. */
    suspend fun flush(): Boolean {
        while (true) {
            val next = synchronized(lock) { queue.firstOrNull() } ?: break
            val ok = runCatching { next.action() }.getOrDefault(false)
            if (!ok) { accessDenied = true; return false }
            synchronized(lock) {
                if (queue.firstOrNull() === next) queue.removeFirst()
                pendingCount = queue.size
            }
        }
        accessDenied = false
        return true
    }
}
