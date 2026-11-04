package com.foxfw.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process

/**
 * Runs the native Hitag2 recovery in a foreground service so it keeps going while the user
 * navigates away, locks the screen, or uses other apps. Progress + result land in [CrackState];
 * a persistent notification shows status and the final clone secret.
 */
class CrackService : Service() {

    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Hitag2 crack", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            CrackState.cancelRequested = true
            return START_NOT_STICKY
        }
        if (worker != null) return START_STICKY // already running

        val uid = intent?.getLongExtra(EX_UID, 0L) ?: 0L
        val btn = intent?.getIntArrayExtra(EX_BTN) ?: return stopNow()
        val ctrl = intent.getIntArrayExtra(EX_CTRL) ?: return stopNow()
        val hop = intent.getLongArrayExtra(EX_HOP) ?: return stopNow()
        // all three capture arrays must line up — later code indexes btn[i]/ctrl[i] by hop's length
        if (btn.size < 2 || ctrl.size != btn.size || hop.size != btn.size) return stopNow()

        CrackState.begin(uid, btn[0], ctrl[0])
        startForegroundCompat(notif("Cracking on phone…", "0 states"))

        worker = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) // keep the scheduler on us
            val t0 = System.currentTimeMillis()
            val poller = Thread {
                while (CrackState.running && !Thread.currentThread().isInterrupted) {
                    if (NativeSolver.available)
                        CrackState.states = if (CrackState.phaseDict) NativeSolver.dictScanned() else NativeSolver.scanned()
                    CrackState.elapsed = ((System.currentTimeMillis() - t0) / 1000).toInt()
                    notifyProgress()
                    if (CrackState.cancelRequested && NativeSolver.available) NativeSolver.cancel()
                    val ble = FoxApplication.instance.ble
                    if (ble.conn == Conn.CONNECTED) ble.sendProgress(CrackState.states, CrackState.elapsed)
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                }
            }.also { it.start() }

            try {
            val cores = Runtime.getRuntime().availableProcessors()
            // 1) fast dictionary pass -> ABSOLUTE 48-bit key (+epoch). 2) on a miss, the bitslice full search -> clone secret.
            var absKey: ByteArray? = null
            if (NativeSolver.available && !CrackState.cancelRequested) {
                val d = runCatching { NativeSolver.dict(uid.toInt(), btn, ctrl, hop, cores) }.getOrNull()
                if (d != null && d.size >= 10) {
                    absKey = d.copyOfRange(0, 6)
                    CrackState.epoch = ((d[6].toInt() and 0xFF) shl 24) or ((d[7].toInt() and 0xFF) shl 16) or
                        ((d[8].toInt() and 0xFF) shl 8) or (d[9].toInt() and 0xFF)
                }
            }
            var cloneRes: ByteArray? = null
            if (absKey == null && NativeSolver.available && !CrackState.cancelRequested) {
                CrackState.phaseDict = false
                cloneRes = NativeSolver.recover(uid.toInt(), btn, ctrl, hop, cores)
            }
            val res = absKey ?: cloneRes
            val isAbs = absKey != null

            poller.interrupt()
            CrackState.running = false
            CrackState.elapsed = ((System.currentTimeMillis() - t0) / 1000).toInt()

            val ble = FoxApplication.instance.ble
            if (CrackState.cancelRequested) {
                finishNotif("Crack cancelled", "")
            } else if (res != null) {
                // predict a target code relative to frame0 (clone) or from the absolute key+epoch
                fun predictAt(tb: Int, tc: Int): Long = runCatching {
                    if (isAbs) NativeSolver.predictKey(uid.toInt(), res, CrackState.epoch, tb, tc)
                    else NativeSolver.predict(uid.toInt(), res, btn[0], ctrl[0], tb, tc)
                }.getOrDefault(0L)
                // verify: how many captured frames the key reproduces exactly
                val exact = (0 until hop.size).count { predictAt(btn[it], ctrl[it]) == (hop[it] and 0xFFFFFFFFL) }
                CrackState.verify = "$exact/${hop.size} frames reproduced exactly"
                // next codes: step the control counter from the highest captured value, button = frame0's
                val maxCtrl = ctrl.maxOrNull() ?: ctrl[0]
                CrackState.predictedCodes = (1..8).map { (maxCtrl + it) to predictAt(btn[0], maxCtrl + it) }
                CrackState.predicted = CrackState.predictedCodes?.firstOrNull()?.second
                CrackState.result = res
                CrackState.absoluteKey = isAbs
                CrackState.method = if (isAbs) "dictionary" else "bitslice"
                CrackState.done = true
                HistoryStore.log(
                    "crack",
                    "Hitag2 0x%08X".format(CrackState.jobUid),
                    "key ${keyHex(res)} · ${CrackState.method} · ${CrackState.verify}",
                    buildString {
                        appendLine("FoxFW Hitag2 recovery")
                        appendLine("UID: 0x%08X".format(CrackState.jobUid))
                        appendLine("Key: ${keyHex(res)}")
                        appendLine("Method: ${CrackState.method}" + if (CrackState.absoluteKey) " (absolute key, epoch ${CrackState.epoch})" else " (clone secret)")
                        appendLine("Verify: ${CrackState.verify}")
                        CrackState.predictedCodes?.let { codes ->
                            appendLine("Next codes:")
                            codes.forEach { (c, h) -> appendLine("  cnt %d -> %08X".format(c, h)) }
                        }
                    },
                    "txt",
                )
                if (ble.conn == Conn.CONNECTED) ble.sendResult(true, res)
                finishNotif(if (isAbs) "Absolute key recovered" else "Clone secret recovered", keyHex(res))
            } else {
                CrackState.failed = true
                if (ble.conn == Conn.CONNECTED) ble.sendResult(false, null)
                finishNotif("No key found", "Need ≥2 clean same-UID frames")
            }
            } catch (t: Throwable) {
                runCatching { CrackState.failed = true; finishNotif("Crack error", t.message ?: "unexpected error") }
            } finally {
                // ALWAYS release the service, even on an exception, or onStartCommand's `worker != null`
                // guard would block every future crack until the process is killed.
                runCatching { poller.interrupt() }
                CrackState.running = false
                worker = null
                stopForegroundCompat()
                stopSelf()
            }
        }.also { it.start() }

        return START_STICKY
    }

    private fun stopNow(): Int { stopSelf(); return START_NOT_STICKY }

    private fun keyHex(k: ByteArray) =
        "%02X %02X · %02X%02X %02X%02X".format(k[0], k[1], k[2], k[3], k[4], k[5])

    private fun notif(title: String, text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(CrackState.running)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notifyProgress() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, notif("Cracking on phone…", "%,d states · %ds".format(CrackState.states, CrackState.elapsed)))
    }

    private fun finishNotif(title: String, text: String) {
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIF_ID,
            n.setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(false).build()
        )
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(false)
    }

    companion object {
        private const val CHANNEL = "crack"
        private const val NOTIF_ID = 42
        const val ACTION_CANCEL = "com.foxfw.companion.CANCEL_CRACK"
        private const val EX_UID = "uid"
        private const val EX_BTN = "btn"
        private const val EX_CTRL = "ctrl"
        private const val EX_HOP = "hop"

        fun start(ctx: Context, uid: Long, btn: IntArray, ctrl: IntArray, hop: LongArray) {
            val i = Intent(ctx, CrackService::class.java)
                .putExtra(EX_UID, uid).putExtra(EX_BTN, btn).putExtra(EX_CTRL, ctrl).putExtra(EX_HOP, hop)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, CrackService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
