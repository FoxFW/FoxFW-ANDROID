package com.foxfw.companion

/**
 * Outcome of a Flipper radio op, derived from the FAP's io_status.txt (Task F2) plus
 * whether the FAP launched at all. `ok` = the op actually succeeded. `label` is a short UI string.
 */
sealed class TxResult(val ok: Boolean, val label: String) {
    object Ok : TxResult(true, "sent")
    object RadioFail : TxResult(false, "radio op failed")
    object NoDevice : TxResult(false, "no CC1101 found")
    object BadArgs : TxResult(false, "bad args")
    object NotConnected : TxResult(false, "Flipper not connected")
    object Launched : TxResult(true, "launched (no status)")
    // appStart reported OK but the FAP never wrote io_status -> it did not actually run (loader
    // false-succeeded, or a resident FAP — e.g. a leaked live sweep — was holding the loader).
    // The radio was NOT keyed, so this is a FAILURE: say so instead of claiming a transmit.
    object NotTransmitted : TxResult(false, "didn't transmit — close any app on the Flipper (hold Back) & retry")
    object SavedForProtoPirate : TxResult(true, "forged — transmit via ProtoPirate")
}

/** Pure map of (did appStart launch?, io_status.txt token) -> TxResult. */
fun txResultFromStatus(launchedOk: Boolean, status: String?): TxResult = when {
    !launchedOk -> TxResult.NotConnected
    status == null -> TxResult.NotTransmitted
    else -> when (status.trim()) {
        "ok" -> TxResult.Ok
        "radio-fail" -> TxResult.RadioFail
        "no-device" -> TxResult.NoDevice
        else -> TxResult.BadArgs
    }
}
