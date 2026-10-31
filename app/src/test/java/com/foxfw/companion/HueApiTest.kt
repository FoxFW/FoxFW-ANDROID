package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HueApiTest {
    @Test fun normalizeBaseAddsSchemeAndTrims() {
        assertEquals("http://hue-proxy.local", HueApi.normalizeBase("hue-proxy.local"))
        assertEquals("http://1.2.3.4", HueApi.normalizeBase("http://1.2.3.4/"))
        assertEquals("http://192.168.1.50:80", HueApi.normalizeBase("  192.168.1.50:80/ "))
        assertEquals("https://x", HueApi.normalizeBase("https://x"))
    }

    @Test fun setUrlBuildsQueryFromPresentArgsOnly() {
        assertEquals("http://h/set?target=bedroom&on=1&bri=60&color=warm",
            HueApi.setUrl("http://h", "bedroom", on = true, bri = 60, color = "warm"))
        assertEquals("http://h/set?target=bedroom&on=0",
            HueApi.setUrl("http://h", "bedroom", on = false, bri = null, color = null))
        assertEquals("http://h/set?target=desk&bri=0",
            HueApi.setUrl("http://h", "desk", on = null, bri = 0, color = null))
    }

    @Test fun setUrlAppendsToken() {
        assertEquals("http://h/set?target=desk&on=1&token=sek",
            HueApi.setUrl("http://h", "desk", on = true, bri = null, color = null, token = "sek"))
    }

    @Test fun endpointUrls() {
        assertEquals("http://h/health", HueApi.healthUrl("http://h", null))
        assertEquals("http://h/health?token=t", HueApi.healthUrl("http://h", "t"))
        assertEquals("http://h/state?target=bedroom", HueApi.stateUrl("http://h", "bedroom", null))
        assertEquals("http://h/scan", HueApi.scanUrl("http://h", null))
    }

    @Test fun parseHealthExtractsBulbs() {
        val j = "{\"ok\":true,\"ip\":\"192.168.1.9\",\"bulbs\":[" +
            "{\"name\":\"bedroom\",\"mac\":\"E1:95:4F:93:4C:97\",\"on\":true}," +
            "{\"name\":\"desk\",\"mac\":\"E1:64:B2:68:85:F6\",\"on\":false}]}"
        val b = HueApi.parseHealth(j)
        assertEquals(2, b.size)
        assertEquals(HueApi.Bulb("bedroom", "E1:95:4F:93:4C:97", true), b[0])
        assertEquals(HueApi.Bulb("desk", "E1:64:B2:68:85:F6", false), b[1])
    }

    @Test fun parseStateReadsFields() {
        val s = HueApi.parseState("{\"ok\":true,\"on\":true,\"bri\":60,\"color\":\"warm\",\"rssi\":-72}")
        assertEquals(HueApi.LightState(true, 60, "warm", -72), s)
    }

    @Test fun parseStateNullWhenNotOk() {
        assertNull(HueApi.parseState("{\"ok\":false,\"error\":\"not advertising\"}"))
    }
}
