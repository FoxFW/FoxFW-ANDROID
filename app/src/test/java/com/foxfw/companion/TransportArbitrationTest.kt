package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Pure arbitration logic: prefer USB when present, fall back to BLE, else none. */
class TransportArbitrationTest {
    private class Fake(override val kind: LinkKind, override var connected: Boolean) : FlipperLink

    @Test
    fun usbPreferredWhenBothConnected() {
        val ble = Fake(LinkKind.BLE, true)
        val usb = Fake(LinkKind.USB, true)
        FlipperTransport.ble = ble
        FlipperTransport.usb = usb
        assertSame(usb, FlipperTransport.active)
        assertEquals(LinkKind.USB, FlipperTransport.kind)
    }

    @Test
    fun fallsBackToBleWhenUsbDisconnects() {
        val ble = Fake(LinkKind.BLE, true)
        val usb = Fake(LinkKind.USB, true)
        FlipperTransport.ble = ble
        FlipperTransport.usb = usb
        assertEquals(LinkKind.USB, FlipperTransport.kind)
        usb.connected = false // cable pulled mid-session
        assertSame(ble, FlipperTransport.active)
        assertEquals(LinkKind.BLE, FlipperTransport.kind)
    }

    @Test
    fun noneWhenNeitherConnected() {
        FlipperTransport.ble = Fake(LinkKind.BLE, false)
        FlipperTransport.usb = null
        assertNull(FlipperTransport.active)
        assertEquals(LinkKind.NONE, FlipperTransport.kind)
    }
}
