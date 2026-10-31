package com.foxfw.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A WPA PMKID crack backend. [CpuCrackBackend] is always available (the WpaCrack oracle, parallelised
 * across cores); a GPU (OpenCL) backend plugs in behind the same interface and is validated against
 * the CPU oracle before it's trusted. The UI picks the fastest AVAILABLE backend.
 */
interface CrackBackend {
    val name: String
    val available: Boolean
    /** Try every word; return the matching passphrase or null. Reports total tested via [onProgress]. */
    suspend fun crackPmkid(
        target: WpaCrack.PmkidTarget,
        words: List<String>,
        onProgress: (tested: Int) -> Unit,
    ): String?
}

/**
 * Picks the crack backend. [best] returns the GPU backend only when it loaded AND reproduced the CPU
 * oracle on the known vector (see [GpuCrackBackend.available], which does real GPU work) — otherwise
 * the always-correct [CpuCrackBackend]. Call it off the main thread.
 */
object CrackBackends {
    fun best(): CrackBackend = if (GpuCrackBackend.available) GpuCrackBackend else CpuCrackBackend
}

/** CPU backend: the WpaCrack reference, fanned out across all cores. The dependable fallback. */
object CpuCrackBackend : CrackBackend {
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 16)
    override val name get() = "CPU · $threads threads"
    override val available = true

    override suspend fun crackPmkid(
        target: WpaCrack.PmkidTarget,
        words: List<String>,
        onProgress: (Int) -> Unit,
    ): String? = coroutineScope {
        val found = AtomicReference<String?>(null)
        val tested = AtomicInteger(0)
        (0 until threads).map { lane ->
            async(Dispatchers.Default) {
                var i = lane
                while (i < words.size) {
                    if (found.get() != null) break
                    val w = words[i]
                    i += threads
                    if (!WpaCrack.wpaLenOk(w)) continue
                    val n = tested.incrementAndGet()
                    if (n % 500 == 0) onProgress(n)
                    val p = WpaCrack.pmk(w, target.ssid)
                    if (WpaCrack.pmkid(p, target.apMac, target.staMac).contentEquals(target.pmkid)) {
                        found.compareAndSet(null, w)
                        break
                    }
                }
            }
        }.awaitAll()
        onProgress(tested.get())
        found.get()
    }
}
