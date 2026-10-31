package com.foxfw.companion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the CYD Hue BLE proxy. Each /set and /state triggers a live BLE connect on the
 * proxy (seconds), so reads get a generous timeout. Base URL + optional token persist in prefs.
 * A "*.local" host is resolved via [Mdns] first (Android's HTTP stack can't resolve mDNS names).
 */
class HueClient(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("foxhue", Context.MODE_PRIVATE)

    var base: String = prefs.getString("base", "hue-proxy.local") ?: "hue-proxy.local"
    var token: String = prefs.getString("token", "") ?: ""
    private var resolvedIp: String? = null

    fun save(newBase: String, newToken: String) {
        base = newBase.trim(); token = newToken.trim(); resolvedIp = null
        prefs.edit().putString("base", base).putString("token", token).apply()
    }

    private fun tok() = token.ifEmpty { null }

    /** http(s)://host[:port] with a *.local host resolved to its IP via mDNS (cached). */
    private suspend fun effectiveBase(): String {
        val n = HueApi.normalizeBase(base)
        val host = n.substringAfter("://").substringBefore(":").substringBefore("/")
        if (!host.endsWith(".local", ignoreCase = true)) return n
        resolvedIp?.let { return n.replaceFirst(host, it) }
        val ip = withContext(Dispatchers.IO) { Mdns.resolve(app, host) }
        return if (ip != null) { resolvedIp = ip; n.replaceFirst(host, ip) } else n
    }

    private suspend fun get(url: String, readMs: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = readMs
                tok()?.let { setRequestProperty("X-Token", it) }
            }
            try {
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) body else throw RuntimeException("HTTP $code: ${body.take(120)}")
            } finally { c.disconnect() }
        }
    }

    suspend fun health(): Result<List<HueApi.Bulb>> =
        get(HueApi.healthUrl(effectiveBase(), tok()), 6000).map { HueApi.parseHealth(it) }

    suspend fun state(target: String): Result<HueApi.LightState?> =
        get(HueApi.stateUrl(effectiveBase(), target, tok()), 16000).map { HueApi.parseState(it) }

    suspend fun scan(): Result<String> = get(HueApi.scanUrl(effectiveBase(), tok()), 12000)

    /** Drive a bulb; returns the raw JSON reply on success. */
    suspend fun set(target: String, on: Boolean? = null, bri: Int? = null, color: String? = null): Result<String> =
        get(HueApi.setUrl(effectiveBase(), target, on, bri, color, tok()), 16000)
}
