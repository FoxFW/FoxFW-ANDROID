package com.foxfw.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Auto-serves FoxFW's on-device PSA (Peugeot/Citroen/Opel/Vauxhall/DS) TEA
 * brute over BLE. When the Flipper's "PSA decrypt" scene offloads a job
 * (PSA_REQUEST on the custom channel), this cracks it on the phone via
 * [PsaCrack] and writes the plaintext back (PSA_RESULT), streaming PROGRESS
 * so the Flipper's on-screen % advances. Mirrors psa_ble_companion.py.
 */
object PsaOffload {
    enum class Phase { IDLE, CRACKING, FOUND, NOT_FOUND, CANCELLED }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var tested by mutableStateOf(0L)
        private set
    var kps by mutableStateOf(0L)
        private set
    var w0 by mutableStateOf(0)
        private set
    var w1 by mutableStateOf(0)
        private set
    var counter by mutableStateOf(0)
        private set
    var dv0 by mutableStateOf(0)
        private set
    var dv1 by mutableStateOf(0)
        private set
    /** Total jobs served this session (handy to confirm the Flipper reached us). */
    var served by mutableStateOf(0)
        private set
    /** Bumped only when a job arrives from the Flipper (not the self-test), so the UI can jump to it. */
    var requestNonce by mutableStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var cancelFlag = AtomicBoolean(false)

    private val ble get() = FoxApplication.instance.ble

    /** Called from the BLE thread when a PSA_REQUEST arrives (or by the manual test). */
    fun onRequest(reqW0: Int, reqW1: Int, sendOverBle: Boolean = true) {
        cancelFlag.set(true)              // signal any in-flight crack to stop (it polls this flag) before we replace it
        job?.cancel()
        val myFlag = AtomicBoolean(false) // per-job flag, captured below — a stale job checks ITS OWN flag, never the new one
        cancelFlag = myFlag
        w0 = reqW0; w1 = reqW1
        tested = 0L; kps = 0L; counter = 0; dv0 = 0; dv1 = 0
        phase = Phase.CRACKING
        if (sendOverBle) requestNonce++
        job = scope.launch {
            val res = PsaCrack.crack(
                reqW0, reqW1, cancel = myFlag,
                onProgress = { n, rate ->
                    if (!myFlag.get()) {  // a superseded job stops touching shared state / BLE
                        tested = n; kps = rate
                        if (sendOverBle && ble.customConnected()) {
                            try { ble.sendPsaProgress(n, rate) } catch (_: Throwable) {}
                        }
                    }
                },
            )
            if (myFlag.get()) {
                phase = Phase.CANCELLED
            } else if (res.ok) {
                counter = res.counter; dv0 = res.dv0; dv1 = res.dv1
                phase = Phase.FOUND; served++
                if (sendOverBle && ble.customConnected()) {
                    try { ble.sendPsaResult(true, res.counter, res.dv0, res.dv1) } catch (_: Throwable) {}
                }
            } else {
                phase = Phase.NOT_FOUND; served++
                if (sendOverBle && ble.customConnected()) {
                    try { ble.sendPsaResult(false, 0, 0, 0) } catch (_: Throwable) {}
                }
            }
        }
    }

    fun onCancel() {
        cancelFlag.set(true)
        job?.cancel()
        if (phase == Phase.CRACKING) phase = Phase.CANCELLED
    }
}
