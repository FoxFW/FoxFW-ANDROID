package com.foxfw.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the pure FAP op-status -> TxResult mapping (no Android/BLE). */
class FlipperRfTest {
    @Test
    fun okStatusIsOk() {
        val r = txResultFromStatus(true, "ok")
        assertTrue(r is TxResult.Ok)
        assertTrue(r.ok)
    }

    @Test
    fun radioFailIsNotOk() {
        val r = txResultFromStatus(true, "radio-fail")
        assertTrue(r is TxResult.RadioFail)
        assertFalse(r.ok)
    }

    @Test
    fun noDeviceMaps() {
        assertTrue(txResultFromStatus(true, "no-device") is TxResult.NoDevice)
    }

    @Test
    fun missingStatusIsLaunched() {
        // appStart reported success but no io_status.txt came back. The FAP writes io_status
        // UNCONDITIONALLY whenever it actually runs an op, so a missing status means the FAP never
        // ran (loader false-succeeded / a resident FAP held the loader): the radio was NOT keyed.
        // This must surface as a failure, not a cheerful "launched" — otherwise the app claims it
        // transmitted while nothing left the antenna.
        val r = txResultFromStatus(true, null)
        assertTrue(r is TxResult.NotTransmitted)
        assertFalse(r.ok)
    }

    @Test
    fun notLaunchedIsNotConnected() {
        assertTrue(txResultFromStatus(false, null) is TxResult.NotConnected)
    }

    @Test
    fun statusIsTrimmed() {
        assertTrue(txResultFromStatus(true, "ok\n") is TxResult.Ok)
    }

    @Test
    fun unknownTokenIsBadArgs() {
        assertTrue(txResultFromStatus(true, "bad-args") is TxResult.BadArgs)
        assertTrue(txResultFromStatus(true, "weird") is TxResult.BadArgs)
    }
}
