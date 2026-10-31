package com.foxfw.companion

import java.util.UUID

/**
 * Pure GATT interpretation: SIG assigned-number names, characteristic-property decoding, and
 * human-readable values for the well-known characteristics. No Android types — unit-testable.
 */
object GattSpec {
    private val SIG_BASE = Regex("0000([0-9a-f]{4})-0000-1000-8000-00805f9b34fb")

    /** The 16-bit short form ("180F") if [uuid] sits on the Bluetooth SIG base, else null. */
    fun shortUuid(uuid: UUID): String? =
        SIG_BASE.matchEntire(uuid.toString().lowercase())?.groupValues?.get(1)?.uppercase()

    private val SERVICES = mapOf(
        "1800" to "Generic Access", "1801" to "Generic Attribute", "1802" to "Immediate Alert",
        "1803" to "Link Loss", "1804" to "Tx Power", "1805" to "Current Time", "1809" to "Health Thermometer",
        "180A" to "Device Information", "180D" to "Heart Rate", "180F" to "Battery", "1810" to "Blood Pressure",
        "1811" to "Alert Notification", "1812" to "HID", "1816" to "Cycling Speed", "1818" to "Cycling Power",
        "181A" to "Environmental Sensing", "181C" to "User Data", "1822" to "Pulse Oximeter",
        "FE59" to "Nordic DFU", "FD6F" to "Exposure Notification",
    )
    private val CHARS = mapOf(
        "2A00" to "Device Name", "2A01" to "Appearance", "2A04" to "Conn Parameters", "2A05" to "Service Changed",
        "2A19" to "Battery Level", "2A23" to "System ID", "2A24" to "Model Number", "2A25" to "Serial Number",
        "2A26" to "Firmware Rev", "2A27" to "Hardware Rev", "2A28" to "Software Rev", "2A29" to "Manufacturer Name",
        "2A2A" to "IEEE Cert", "2A37" to "Heart Rate Measurement", "2A38" to "Body Sensor Location",
        "2A6D" to "Pressure", "2A6E" to "Temperature", "2A6F" to "Humidity",
    )
    // Well-known vendor 128-bit UUIDs that have no SIG short form.
    private val CUSTOM = mapOf(
        UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E") to "Nordic UART",
        UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") to "NUS RX (write)",
        UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") to "NUS TX (notify)",
    )

    fun serviceName(uuid: UUID): String {
        val s = shortUuid(uuid)
        return SERVICES[s] ?: CUSTOM[uuid] ?: if (s != null) "Unknown Service 0x$s" else "Custom Service"
    }

    fun charName(uuid: UUID): String {
        val s = shortUuid(uuid)
        return CHARS[s] ?: CUSTOM[uuid] ?: if (s != null) "Unknown 0x$s" else "Custom Characteristic"
    }

    /** Decode a BluetoothGattCharacteristic property bitmask into labels, high-signal first. */
    fun properties(flags: Int): List<String> = buildList {
        if (flags and 0x01 != 0) add("Broadcast")
        if (flags and 0x02 != 0) add("Read")
        if (flags and 0x04 != 0) add("Write No Resp")
        if (flags and 0x08 != 0) add("Write")
        if (flags and 0x10 != 0) add("Notify")
        if (flags and 0x20 != 0) add("Indicate")
        if (flags and 0x40 != 0) add("Signed Write")
        if (flags and 0x80 != 0) add("Ext Props")
    }

    /** A human reading for a well-known characteristic value, or null if we don't decode it. */
    fun interpret(uuid: UUID, v: ByteArray): String? {
        if (v.isEmpty()) return null
        return when (shortUuid(uuid)) {
            "2A19" -> "${v[0].toInt() and 0xFF}%"
            "2A00", "2A24", "2A25", "2A26", "2A27", "2A28", "2A29", "2A2A" ->
                v.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            "2A37" -> { // heart rate: flags byte then uint8 or uint16 LE
                val wide = v[0].toInt() and 0x01 == 1
                val bpm = if (wide && v.size >= 3) (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
                else if (v.size >= 2) v[1].toInt() and 0xFF else return null
                "$bpm bpm"
            }
            "2A6E" -> if (v.size >= 2) { // temperature: sint16 LE, 0.01 °C
                val raw = ((v[1].toInt() shl 8) or (v[0].toInt() and 0xFF)).toShort().toInt()
                "%.2f °C".format(raw / 100.0)
            } else null
            "2A6F" -> if (v.size >= 2) { // humidity: uint16 LE, 0.01 %
                val raw = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
                "%.2f %%".format(raw / 100.0)
            } else null
            else -> null
        }
    }

    /** Hex bytes, then an ASCII rendering in quotes when any byte is printable. */
    fun formatValue(v: ByteArray): String {
        if (v.isEmpty()) return "(empty)"
        val hex = v.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        val printable = v.any { (it.toInt() and 0xFF) in 0x20..0x7E }
        if (!printable) return hex
        val ascii = v.map { val c = it.toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar() else '.' }.joinToString("")
        return "$hex  \"$ascii\""
    }
}
