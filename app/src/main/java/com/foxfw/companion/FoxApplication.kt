package com.foxfw.companion

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** App-scoped singletons so the foreground service and the UI share one BLE link + crack state. */
class FoxApplication : Application() {
    val ble: FlipperBle by lazy { FlipperBle(this) }
    val chameleon: ChameleonBle by lazy { ChameleonBle(this) }

    companion object {
        lateinit var instance: FoxApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        FlipperTransport.ble = ble // register the BLE link; USB registers on attach (C3)
        // Chaquopy Python runtime (arke automotive/gate decode engine)
        if (!Python.isStarted()) {
            try { Python.start(AndroidPlatform(this)) } catch (_: Throwable) {}
        }
        // App foreground/background tracking: count started activities. When the app leaves the
        // foreground (no visible activity) the shared ESP32 connection is torn down; it reconnects on
        // return if it had been connected. Avoids pulling in lifecycle-process just for ON_STOP.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) Esp32Hub.onAppForeground()
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0) { startedActivities = 0; Esp32Hub.onAppBackground() }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

/**
 * Shared, observable state of the Hitag2 recovery. Lives outside any composable/service so the
 * job keeps running (in CrackService) while the user navigates away, locks the screen, or switches
 * apps — and the UI re-binds to it whenever the screen is shown again.
 */
object CrackState {
    var running by mutableStateOf(false)
    var states by mutableStateOf(0L)
    var elapsed by mutableStateOf(0)
    var done by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var result by mutableStateOf<ByteArray?>(null)
    var predicted by mutableStateOf<Long?>(null)

    // how the key was found + what kind of key it is
    var method by mutableStateOf("")          // "dictionary" | "bitslice"
    var absoluteKey by mutableStateOf(false)   // true = result is the absolute 48-bit key (dict hit)
    var epoch by mutableStateOf(0)             // epoch for an absolute-key prediction
    var phaseDict by mutableStateOf(false)     // true while the (fast) dictionary pass is running
    var predictedCodes by mutableStateOf<List<Pair<Int, Long>>?>(null) // next codes: (ctrl, hop)
    var verify by mutableStateOf("")           // e.g. "6/6 frames reproduced exactly"

    // job identity, so the UI shows the capture set that is actually being worked
    var jobUid by mutableStateOf(0L)
    var f0btn = 0
    var f0ctrl = 0

    @Volatile var cancelRequested = false

    fun begin(uid: Long, f0b: Int, f0c: Int) {
        running = true; done = false; failed = false; result = null; predicted = null
        states = 0; elapsed = 0; jobUid = uid; f0btn = f0b; f0ctrl = f0c; cancelRequested = false
        method = ""; absoluteKey = false; epoch = 0; phaseDict = true; predictedCodes = null; verify = ""
    }
}
