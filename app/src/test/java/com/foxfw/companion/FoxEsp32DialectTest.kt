package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the pure Marauder<->Fox ESP32 FW dialect bridge (no USB/BLE). */
class FoxEsp32DialectTest {

    private fun one(cmd: String): String {
        val o = FoxEsp32Dialect.toFox(cmd)
        assertEquals("expected a single board line for '$cmd'", 1, o.send.size)
        assertNull(o.localReply)
        return o.send[0]
    }

    // ---- outbound: command translation ----
    @Test fun scanAndListMapToRescan() {
        assertEquals("WIFISCANAP", one("scanap"))
        assertEquals("WIFISCANAP", one("list -a"))   // live poll => fresh rescan on sync firmware
        assertEquals("WIFISCANSTA", one("scansta"))
        assertEquals("WIFISCANSTA", one("list -s"))
    }

    @Test fun scanallSendsBoth() {
        assertEquals(listOf("WIFISCANAP", "WIFISCANSTA"), FoxEsp32Dialect.toFox("scanall").send)
    }

    @Test fun stopscanIsSwallowedWithAck() {
        val o = FoxEsp32Dialect.toFox("stopscan")
        assertTrue(o.send.isEmpty())
        assertEquals("OK", o.localReply)
    }

    @Test fun selectMaps() {
        assertEquals("WIFISELECT:AP:3", one("select -a 3"))
        assertEquals("WIFISELECT:AP:3", one("select -a 3,5,7")) // Fox takes one index
        assertEquals("WIFISELECT:STA:2", one("select -s 2"))
    }

    @Test fun selectAllIsUnsupported() {
        val o = FoxEsp32Dialect.toFox("select -a all")
        assertTrue(o.send.isEmpty())
        assertTrue(o.localReply!!.startsWith("[fox] not supported"))
    }

    @Test fun attacksMap() {
        assertEquals("WIFIATTACK:DEAUTH", one("attack -t deauth"))
        assertEquals(listOf("WIFIATTACK:DEAUTH", "WIFIPCAP"), FoxEsp32Dialect.toFox("attack -t deauth -c").send)
        assertEquals("WIFIATTACK:PROBE", one("attack -t probe"))
        assertEquals("WIFIATTACK:RICKROLL", one("attack -t rickroll"))
        assertEquals("WIFIATTACK:CSA", one("attack -t csa"))
        assertEquals("WIFIATTACK:SAE", one("attack -t sae"))
        assertEquals("WIFIATTACK:QUIET", one("attack -t quiet"))
        assertEquals("WIFIATTACK:BADPACKET", one("attack -t badmsg -c"))
        assertEquals("WIFIATTACK:KARMA", one("karma -p 2"))
    }

    @Test fun beaconApCloneUnsupported() {
        assertTrue(FoxEsp32Dialect.toFox("attack -t beacon -a").send.isEmpty())
        assertEquals("WIFIATTACK:BEACON:RANDOM:5", one("attack -t beacon -r"))
    }

    @Test fun channelMaps() {
        assertEquals("WIFICHANNEL", one("channel"))
        assertEquals("WIFICHANNEL:6", one("channel -s 6"))
    }

    @Test fun sniffFamilyMaps() {
        assertEquals("WIFIPCAP", one("sniffpwn"))
        assertEquals("WIFISNIFF:PMKID", one("sniffpmkid"))
        assertEquals("WIFISNIFF:SAE", one("sniffsae"))
        assertEquals("WIFISNIFF:BEACON", one("sniffbeacon"))
        assertEquals("WIFISNIFF:RAW", one("sniffraw"))
    }

    @Test fun bleSpamClassMaps() {
        assertEquals("BLESPAM:IOS", one("blespam -t sourapple"))
        assertEquals("BLESPAM:IOS", one("blespam -t applejuice"))
        assertEquals("BLESPAM:GOOGLE", one("blespam -t google"))
        assertEquals("BLESPAM:SAMSUNG", one("blespam -t samsung"))
        assertEquals("BLESPAM:ALL", one("blespam -t all"))
    }

    @Test fun reconAndMacMap() {
        assertEquals("WIFIPINGSCAN", one("pingscan"))
        assertEquals("WIFIARPSCAN", one("arpscan -f"))
        assertEquals("WIFIMACTRACK", one("mactrack"))
        assertEquals("WIFIMAC:RAND", one("randapmac"))
        assertEquals("WIFIMAC:CLONEAP", one("cloneapmac -a 1"))
    }

    @Test fun portalAndSystemMap() {
        assertEquals("WIFIFOXPORTAL:START", one("evilportal -c start"))
        assertEquals("SETTINGS", one("settings"))
        assertEquals("info", one("info"))
    }

    @Test fun headlessDisplayCommandsUnsupported() {
        for (c in listOf("reboot", "update -s", "brightness -s 5", "led -s FF9A3D", "nmea", "foxhunt -b")) {
            assertTrue("$c should be unsupported", FoxEsp32Dialect.toFox(c).send.isEmpty())
        }
    }

    @Test fun unknownPassesThrough() {
        assertEquals("WIFISIGMON", one("WIFISIGMON")) // a raw Fox command typed in the console
    }

    // ---- inbound: Fox output -> Marauder format ----
    @Test fun apRowRebuildsToMarauder() {
        assertEquals(
            "[0][CH:6] MyNet -45",
            FoxEsp32Dialect.fromFox("""AP:0 ssid:"MyNet" bssid:AA:BB:CC:DD:EE:FF ch:6 rssi:-45 enc:WPA2""", -1),
        )
        // and the rebuilt row must parse back through the app's existing Marauder parser:
        val ap = Marauder.parseAp(
            FoxEsp32Dialect.fromFox("""AP:1 ssid:"Net5" bssid:AA:BB:CC:DD:EE:01 ch:36 rssi:-60 enc:WPA2""", -1)!!,
        )!!
        assertEquals(1, ap.index); assertEquals(36, ap.channel); assertEquals(-60, ap.rssi)
        assertEquals("Net5", ap.essid); assertEquals(BandScan.Band.GHZ5, ap.band)
    }

    @Test fun selectedSuffixWhenIndexMatches() {
        assertTrue(
            FoxEsp32Dialect.fromFox("""AP:2 ssid:"X" bssid:AA:BB:CC:DD:EE:02 ch:1 rssi:-30 enc:OPEN""", 2)!!
                .endsWith("(selected)"),
        )
    }

    @Test fun staRowRebuilds() {
        val s = FoxEsp32Dialect.fromFox("STA:4 mac:11:22:33:44:55:66 rssi:-70", -1)!!
        assertEquals("[4] 11:22:33:44:55:66", s)
        assertEquals(4, Marauder.parseSta(s)!!.index)
    }

    @Test fun terminatorsSwallowed() {
        for (t in listOf("SCANDONE", "DONE", "SNIFFDONE", "ATTACKDONE")) assertNull(FoxEsp32Dialect.fromFox(t, -1))
    }

    @Test fun statusLinesPassThrough() {
        assertEquals("OK", FoxEsp32Dialect.fromFox("OK", -1))
        assertEquals("ERROR:BADMAC", FoxEsp32Dialect.fromFox("ERROR:BADMAC", -1))
        assertEquals("CHANNEL:6", FoxEsp32Dialect.fromFox("CHANNEL:6", -1))
    }

    @Test fun detectsFoxBanner() {
        assertTrue(FoxEsp32Dialect.isFoxBanner("Fox ESP32 Firmware"))
        assertTrue(!FoxEsp32Dialect.isFoxBanner("Marauder v1.20.0"))
    }
}
