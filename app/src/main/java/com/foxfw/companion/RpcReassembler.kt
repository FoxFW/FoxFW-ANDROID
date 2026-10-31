package com.foxfw.companion

/**
 * Reassembles the Flipper RPC stream: a sequence of varint-length-delimited PB_Main messages,
 * arriving in arbitrary chunks (BLE notifications or USB bulk reads). Calls [onMessage] with each
 * complete message's bytes. Mirrors the proven framing in FlipperBle.onRpcData (same desync/size
 * guards), extracted so BLE and USB share one tested implementation.
 */
class RpcReassembler(private val onMessage: (ByteArray) -> Unit) {
    private var buf = ByteArray(0)

    fun reset() { buf = ByteArray(0) }

    fun feed(data: ByteArray) {
        buf = if (buf.isEmpty()) data else buf + data
        var off = 0
        while (true) {
            // varint length prefix starting at off
            var p = off
            var shift = 0
            var len = 0L
            var haveLen = false
            while (p < buf.size) {
                val c = buf[p].toInt() and 0xFF; p++
                len = len or ((c.toLong() and 0x7F) shl shift)
                if (c and 0x80 == 0) { haveLen = true; break }
                shift += 7
                if (shift > 35) { haveLen = false; break }
            }
            if (!haveLen) break // length prefix not fully arrived
            val msgLen = len.toInt()
            if (msgLen < 0 || msgLen > 8192) { off = p; continue } // desync guard
            if (p + msgLen > buf.size) break // body not fully arrived
            onMessage(buf.copyOfRange(p, p + msgLen))
            off = p + msgLen
        }
        buf = if (off >= buf.size) ByteArray(0) else buf.copyOfRange(off, buf.size)
        if (buf.size > 16384) buf = ByteArray(0) // keep leftover bounded on persistent desync
    }
}
