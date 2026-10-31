package com.foxfw.companion

/**
 * Live Sub-GHz RX/TX through the Flipper's CC1101 (internal or external) via the
 * Fox SubGHz Bridge FAP, over the BLE custom-data channel (fe65/fe66).
 *
 * Phone->FAP frames: [len u16 LE][opcode + payload]
 *   'D' detect | 'R'+freq+dev start RX | 'T'+freq+dev+count begin TX | 'P'+pulses | 'X' stop
 * FAP->phone: 'd'+ext+dev | 'r'+int32 edges | 'k' tx done | 'x' stopped | 'e'+code
 * dev: 0=internal, 1=external, 2=auto.
 */
object FlipperSubghz {
    const val DEV_INT = 0
    const val DEV_EXT = 1
    const val DEV_AUTO = 2

    private val ble get() = FoxApplication.instance.ble

    @Volatile var onEdges: ((IntArray) -> Unit)? = null
    @Volatile var onTxDone: (() -> Unit)? = null
    @Volatile var onDetect: ((Boolean) -> Unit)? = null

    val connected: Boolean get() = ble.customConnected()

    /** Claim the custom channel for the bridge (call when a Sub-GHz screen opens). */
    fun start() { ble.customListener = { d -> handle(d) } }
    fun stop() { stopRx(); ble.customListener = null; onEdges = null; onTxDone = null; onDetect = null }

    private fun handle(d: ByteArray) {
        if (d.isEmpty()) return
        when (d[0].toInt() and 0xFF) {
            'd'.code -> onDetect?.invoke(d.size >= 2 && d[1].toInt() != 0)
            'r'.code -> {
                val n = (d.size - 1) / 4
                if (n <= 0) return
                val e = IntArray(n)
                for (i in 0 until n) {
                    var v = 0
                    for (k in 0 until 4) v = v or ((d[1 + i * 4 + k].toInt() and 0xFF) shl (k * 8))
                    e[i] = v
                }
                onEdges?.invoke(e)
            }
            'k'.code -> onTxDone?.invoke()
        }
    }

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
    )
    private fun frame(payload: ByteArray): ByteArray {
        val b = ByteArray(2 + payload.size)
        b[0] = (payload.size and 0xFF).toByte(); b[1] = ((payload.size ushr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, b, 2, payload.size)
        return b
    }

    fun detect() { ble.sendCustom(frame(byteArrayOf('D'.code.toByte()))) }

    fun startRx(freqHz: Int, dev: Int) {
        ble.sendCustom(frame(byteArrayOf('R'.code.toByte()) + le32(freqHz) + byteArrayOf(dev.toByte())))
    }
    fun stopRx() { ble.sendCustom(frame(byteArrayOf('X'.code.toByte()))) }

    /** Transmit a raw OOK pulse list (+high / -low µs) live via the Flipper CC1101. */
    fun tx(freqHz: Int, dev: Int, pulses: IntArray) {
        ble.sendCustom(frame(byteArrayOf('T'.code.toByte()) + le32(freqHz) + byteArrayOf(dev.toByte()) + le32(pulses.size)))
        var i = 0
        while (i < pulses.size) {
            val n = minOf(48, pulses.size - i)
            val payload = ByteArray(1 + n * 4)
            payload[0] = 'P'.code.toByte()
            for (j in 0 until n) {
                val v = pulses[i + j]
                payload[1 + j * 4] = (v and 0xFF).toByte()
                payload[1 + j * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
                payload[1 + j * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
                payload[1 + j * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
            }
            ble.sendCustom(frame(payload))
            i += n
        }
    }
}
