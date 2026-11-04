package com.foxfw.companion

/**
 * Hands a captured hc22000 handshake line from the Esp32 relay to the Handshake Crack screen.
 * When "auto-ship" is on, the Esp32 console forwards any WPA*01/WPA*02 line the board emits here;
 * the cracker pre-fills from it on open. A plain holder — read once on screen entry via [consume].
 */
object CrackInbox {
    @Volatile var pending: String? = null
        private set

    /** Total handshakes shipped this session (for the Esp32 UI counter). */
    @Volatile var count: Int = 0
        private set

    fun offer(line: String) {
        pending = line
        count++
    }

    /** Take the pending line (and clear it), or null if none. */
    fun consume(): String? {
        val p = pending
        pending = null
        return p
    }
}
