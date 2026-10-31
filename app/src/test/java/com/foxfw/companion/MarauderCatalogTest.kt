package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Marauder command catalog is what lets the app "drive the whole of Marauder": every category of
 * the ESP32-Marauder C5 CLI, structured so the UI can render it and [Marauder.build] can turn a chosen
 * command (+ optional argument) into the exact serial line. Command set verified against the live
 * `help` dump from a BFFB v2 (ESP32-C5) running Marauder v1.20.0.
 */
class MarauderCatalogTest {

    private val allCmds get() = Marauder.catalog.flatMap { it.cmds }

    @Test fun catalogCoversTheVerifiedCli() {
        val ids = allCmds.map { it.id }.toSet()
        // a slice across every category — must match the real board CLI, not guesses
        listOf(
            "scanap", "scansta", "scanall", "stopscan",
            "sniffpwn", "sniffpmkid", "sniffsae", "sniffbt",
            "deauth", "deauth_targeted", "beacon_list", "beacon_random", "probe", "rickroll",
            "attack_badmsg", "karma", "evilportal", "spoofat",
            "blespam", "sourapple",
            "channel_set", "list_ap", "select_ap", "select_ap_all", "clearlist_ap",
            "wardrive", "mactrack", "gpsdata", "upload",
            "reboot", "info", "settings", "update_sd", "update_web",
        ).forEach { assertTrue("catalog missing '$it'", it in ids) }
    }

    @Test fun commandIdsAreUnique() {
        val ids = allCmds.map { it.id }
        assertEquals("duplicate command id", ids.size, ids.toSet().size)
    }

    @Test fun buildWithNoArgReturnsTemplate() {
        val scanap = allCmds.first { it.id == "scanap" }
        assertEquals("scanap", Marauder.build(scanap, null))
        assertEquals("scanap", Marauder.build(scanap, "ignored"))
    }

    @Test fun buildSubstitutesArgument() {
        val sel = allCmds.first { it.id == "select_ap" }
        assertEquals(Marauder.ArgKind.INDEX, sel.arg)
        assertEquals("select -a 4", Marauder.build(sel, "4"))
        val chan = allCmds.first { it.id == "channel_set" }
        assertEquals("channel -s 36", Marauder.build(chan, "36"))
    }

    @Test fun buildTrimsArgumentAndRejectsBlankWhenRequired() {
        val sel = allCmds.first { it.id == "select_ap" }
        assertEquals("select -a 7", Marauder.build(sel, "  7 "))
        assertNull(Marauder.build(sel, "  "))
        assertNull(Marauder.build(sel, null))
    }

    @Test fun attacksAreFlaggedAuthorizedOnlyAndReconIsNot() {
        val danger = allCmds.filter { it.danger }.map { it.id }.toSet()
        // every offensive command the UI must gate behind an authorized-use confirm
        listOf("deauth", "deauth_targeted", "beacon_random", "probe", "rickroll",
            "attack_badmsg", "karma", "evilportal", "spoofat", "blespam", "sourapple")
            .forEach { assertTrue("attack '$it' not flagged danger", it in danger) }
        // passive recon must NOT be gated
        listOf("scanap", "sniffpwn", "list_ap", "gpsdata", "info", "wardrive").forEach {
            assertFalse("passive '$it' wrongly flagged danger", it in danger)
        }
    }

    @Test fun choiceCommandsCarryTheirChoices() {
        val spam = allCmds.first { it.id == "blespam" }
        assertEquals(Marauder.ArgKind.CHOICE, spam.arg)
        assertTrue("sourapple" in spam.choices && "google" in spam.choices && "all" in spam.choices)
        assertEquals("blespam -t google", Marauder.build(spam, "google"))
        val up = allCmds.first { it.id == "upload" }
        assertEquals(Marauder.ArgKind.CHOICE, up.arg)
        assertTrue("wdg" in up.choices && "wigle" in up.choices && "both" in up.choices)
        assertEquals("upload -d wdg", Marauder.build(up, "wdg"))
    }

    @Test fun captureEventFiresOnlyOnRealCaptures() {
        // start/progress lines must NOT fire
        assertNull(Marauder.captureEvent("Starting PMKID sniff on channel 6. Stop with stopscan"))
        assertNull(Marauder.captureEvent("Received EAPOL: 2"))
        assertNull(Marauder.captureEvent("Complete EAPOL: 0"))
        assertNull(Marauder.captureEvent("#sniffpwn"))
        // real captures (non-zero count) do fire
        assertNotNull(Marauder.captureEvent("Complete EAPOL: 1"))
        assertNotNull(Marauder.captureEvent("Pwnd #: 3"))
    }

    @Test fun parsesStationLine() {
        val s = Marauder.parseSta("[2] AA:BB:CC:11:22:33")!!
        assertEquals(2, s.index)
        assertEquals("AA:BB:CC:11:22:33", s.mac)
    }

    @Test fun rejectsNonStationLines() {
        assertNull(Marauder.parseSta(""))
        assertNull(Marauder.parseSta("Scanning"))
        assertNull(Marauder.parseSta("[0][CH:6] anAp -40")) // an AP line, not a station
    }
}
