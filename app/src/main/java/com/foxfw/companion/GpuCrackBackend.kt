package com.foxfw.companion

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GPU (OpenCL) WPA PMKID crack backend. `libfoxgpu.so` is NOT linked against libOpenCL — it
 * dlopen()s it at runtime, so this library always loads and [nativeAvailable] reports whether a
 * usable GPU actually exists. On top of that, [available] runs a SELF-VALIDATION: it cracks the known
 * [WpaCrack] test vector on the GPU and only trusts the GPU if the result matches the CPU oracle at
 * the right position. Any failure — no OpenCL, kernel build error, or a wrong/garbled result — leaves
 * the GPU untrusted and the UI falls back to [CpuCrackBackend]. That is the "safely" in "use my GPU".
 */
object GpuCrackBackend : CrackBackend {
    private const val TAG = "FoxGpu"
    private const val STRIDE = 64                       // bytes per candidate slot (WPA pass max 63)
    // Adaptive batch: each NDRange enqueue is sized to run ~TARGET_MS, so it's as big (fast) as the GPU
    // can chew in one go while staying short enough to keep the UI responsive and dodge the GPU
    // watchdog (TDR). Starts small, then scales to the measured device speed.
    private const val START_BATCH = 4096
    private const val MIN_BATCH = 1024
    private const val MAX_BATCH = 262_144
    private const val TARGET_MS = 450L

    @Volatile private var loaded = false
    @Volatile private var validated: Boolean? = null    // null = not checked yet

    init {
        loaded = try {
            System.loadLibrary("foxgpu"); true
        } catch (t: Throwable) {
            Log.w(TAG, "loadLibrary(foxgpu) failed", t); false
        }
    }

    private external fun nativeAvailable(): Int
    private external fun nativeCrackBatch(
        pwData: ByteArray, pwLen: IntArray, stride: Int, ssid: ByteArray, meta: ByteArray,
    ): Int

    override val name get() = "GPU · OpenCL"

    /** True only when OpenCL loaded AND the GPU reproduced the CPU oracle on the known vector. Cached. */
    override val available: Boolean
        get() {
            if (!loaded) return false
            validated?.let { return it }
            val ok = runCatching { nativeAvailable() >= 0 && selfValidate() }.getOrElse {
                Log.w(TAG, "GPU probe threw", it); false
            }
            validated = ok
            Log.i(TAG, "GPU backend available=$ok")
            return ok
        }

    /** Crack the baked-in vector on the GPU; demand the exact right candidate at its true index. */
    private fun selfValidate(): Boolean {
        val target = WpaCrack.parsePmkid(
            "WPA*01*f414df3ad563bd18581c358d506b9448*aabbccddeeff*112233445566*466f784e65743547***",
        ) ?: return false
        val words = listOf("wrongpass1", "letmein123", "wrongpass2")
        val idx = crackBatch(target, words)
        val good = idx == 1
        if (!good) Log.w(TAG, "self-validate failed (idx=$idx, expected 1) — GPU result not trusted")
        return good
    }

    private fun meta(target: WpaCrack.PmkidTarget): ByteArray {
        val m = ByteArray(28)
        System.arraycopy(target.pmkid, 0, m, 0, 16)
        System.arraycopy(target.apMac, 0, m, 16, 6)
        System.arraycopy(target.staMac, 0, m, 22, 6)
        return m
    }

    /** Pack a batch (fixed [STRIDE] slots) and run one kernel; returns the matching index or -1. */
    private fun crackBatch(target: WpaCrack.PmkidTarget, words: List<String>): Int {
        val n = words.size
        val pw = ByteArray(n * STRIDE)
        val len = IntArray(n)
        for (i in 0 until n) {
            val b = words[i].toByteArray(Charsets.UTF_8)
            if (b.size in 8..63) {
                System.arraycopy(b, 0, pw, i * STRIDE, b.size)
                len[i] = b.size
            } else {
                len[i] = 0 // kernel skips len<8||>63
            }
        }
        return nativeCrackBatch(pw, len, STRIDE, target.ssid, meta(target))
    }

    override suspend fun crackPmkid(
        target: WpaCrack.PmkidTarget,
        words: List<String>,
        onProgress: (tested: Int) -> Unit,
    ): String? = withContext(Dispatchers.Default) {
        if (target.ssid.size > 32) return@withContext null // kernel salt buffer is 32B; CPU covers the rest
        var tested = 0
        var hit: String? = null
        var batchSize = START_BATCH
        val batch = ArrayList<String>(MAX_BATCH)

        // run one enqueue, report progress, and resize the next batch toward TARGET_MS. Returns found.
        fun flush(): Boolean {
            if (batch.isEmpty()) return false
            val t0 = System.nanoTime()
            val idx = crackBatch(target, batch)
            val dtMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1)
            tested += batch.size
            onProgress(tested)
            val found = idx in batch.indices
            if (found) hit = batch[idx]
            batchSize = (batchSize.toLong() * TARGET_MS / dtMs).toInt().coerceIn(MIN_BATCH, MAX_BATCH)
            batch.clear()
            return found
        }

        for (w in words) {
            batch.add(w)
            if (batch.size >= batchSize) { if (flush()) break }
        }
        if (hit == null) flush()
        hit
    }
}
