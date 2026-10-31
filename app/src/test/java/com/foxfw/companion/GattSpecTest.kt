package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GattSpecTest {
    private fun sig(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805F9B34FB")
    private val nus = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    @Test fun shortUuidExtractsSigBase() {
        assertEquals("180F", GattSpec.shortUuid(sig("180F")))
        assertEquals("2A19", GattSpec.shortUuid(sig("2A19")))
    }

    @Test fun shortUuidNullForCustom() {
        assertNull(GattSpec.shortUuid(nus))
    }

    @Test fun serviceNamesKnownAndFallback() {
        assertEquals("Battery", GattSpec.serviceName(sig("180F")))
        assertEquals("Device Information", GattSpec.serviceName(sig("180A")))
        assertEquals("Nordic UART", GattSpec.serviceName(nus))
        assertEquals("Unknown Service 0x1234", GattSpec.serviceName(sig("1234")))
        assertEquals("Custom Service", GattSpec.serviceName(UUID.fromString("abcdef01-0000-0000-0000-000000000000")))
    }

    @Test fun charNamesKnownAndFallback() {
        assertEquals("Battery Level", GattSpec.charName(sig("2A19")))
        assertEquals("Device Name", GattSpec.charName(sig("2A00")))
        assertEquals("Manufacturer Name", GattSpec.charName(sig("2A29")))
        assertEquals("Unknown 0x2ABC", GattSpec.charName(sig("2ABC")))
    }

    @Test fun propertiesDecodeFlags() {
        assertEquals(listOf("Read"), GattSpec.properties(0x02))
        assertEquals(listOf("Read", "Notify"), GattSpec.properties(0x12))
        assertEquals(listOf("Write No Resp", "Write"), GattSpec.properties(0x0C))
        assertTrue(GattSpec.properties(0x00).isEmpty())
    }

    @Test fun interpretBatteryAndText() {
        assertEquals("85%", GattSpec.interpret(sig("2A19"), byteArrayOf(0x55)))
        assertEquals("Pixel", GattSpec.interpret(sig("2A00"), "Pixel".toByteArray()))
        assertNull(GattSpec.interpret(sig("2A19"), byteArrayOf())) // empty → nothing
        assertNull(GattSpec.interpret(UUID.fromString("abcdef01-0000-0000-0000-000000000000"), byteArrayOf(1, 2)))
    }

    @Test fun formatValueHexThenAscii() {
        assertEquals("48 69  \"Hi\"", GattSpec.formatValue(byteArrayOf(0x48, 0x69)))
        assertEquals("00 FF", GattSpec.formatValue(byteArrayOf(0x00, 0xFF.toByte())))
        assertEquals("(empty)", GattSpec.formatValue(byteArrayOf()))
    }
}
