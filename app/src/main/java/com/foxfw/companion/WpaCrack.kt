package com.foxfw.companion

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * WPA/WPA2 personal crack — CPU reference. This is the correctness ORACLE the OpenCL GPU kernel is
 * validated against (and the safe fallback when no GPU is available). Dictionary attack only; no
 * passphrase is recovered that isn't in the wordlist.
 *
 * PMKID path (modern, one PBKDF2 per candidate, no client needed):
 *   PMK   = PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 32)
 *   PMKID = HMAC-SHA1(PMK, "PMK Name" || AP_MAC || STA_MAC)[0:16]
 * Input is the hashcat 22000 "WPA*01*..." line (what hcxpcapngtool / JARVIS emits from a capture).
 */
object WpaCrack {

    /** WPA passphrase length rule shared by EVERY backend: 8..63 UTF-8 bytes (matches the GPU packer,
     *  so the CPU and GPU test the exact same candidate set). */
    fun wpaLenOk(w: String): Boolean = w.toByteArray(Charsets.UTF_8).size in 8..63

    /** PMK = PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 32). Hand-rolled so it matches hashcat/hashlib
     *  exactly (JCE's PBKDF2WithHmacSHA1 char[] handling is encoding-ambiguous for some inputs). */
    fun pmk(passphrase: String, ssid: ByteArray): ByteArray =
        pbkdf2HmacSha1(passphrase.toByteArray(Charsets.UTF_8), ssid, 4096, 32)

    /** PMKID = HMAC-SHA1(PMK, "PMK Name" || AP_MAC || STA_MAC) truncated to 16 bytes. */
    fun pmkid(pmk: ByteArray, apMac: ByteArray, staMac: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(pmk, "HmacSHA1"))
        mac.update("PMK Name".toByteArray(Charsets.US_ASCII))
        mac.update(apMac)
        mac.update(staMac)
        return mac.doFinal().copyOf(16)
    }

    data class PmkidTarget(
        val pmkid: ByteArray,
        val apMac: ByteArray,
        val staMac: ByteArray,
        val ssid: ByteArray,
    ) {
        val ssidText: String get() = String(ssid, Charsets.UTF_8)
    }

    /** Parse a hashcat-22000 PMKID line: `WPA*01*<pmkid>*<ap>*<sta>*<essid_hex>*...`. Null otherwise. */
    fun parsePmkid(line: String): PmkidTarget? {
        val f = line.trim().split('*')
        if (f.size < 6 || f[0] != "WPA" || f[1] != "01") return null
        return try {
            val pmkid = hexToBytes(f[2]); val ap = hexToBytes(f[3]); val sta = hexToBytes(f[4]); val ssid = hexToBytes(f[5])
            // reject malformed/truncated lines so downstream (incl. the GPU meta[] copy) never over-reads
            if (pmkid.size != 16 || ap.size != 6 || sta.size != 6 || ssid.isEmpty() || ssid.size > 32) return null
            PmkidTarget(pmkid, ap, sta, ssid)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Dictionary-crack a PMKID target on the CPU. Returns the matching passphrase or null.
     * Candidates outside the WPA 8..63 char range are skipped (never hashed).
     */
    fun crackPmkid(
        target: PmkidTarget,
        words: Sequence<String>,
        onProgress: ((tested: Int) -> Unit)? = null,
    ): String? {
        var tested = 0
        for (w in words) {
            if (!wpaLenOk(w)) continue
            tested++
            if (onProgress != null && tested % 500 == 0) onProgress(tested)
            val p = pmk(w, target.ssid)
            val id = pmkid(p, target.apMac, target.staMac)
            if (id.contentEquals(target.pmkid)) return w
        }
        return null
    }

    // ---- WPA/WPA2 4-way EAPOL handshake (hashcat 22000 "WPA*02*" line) ----
    //
    // PMK   = PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 32)   (same expensive step as PMKID)
    // PTK    = PRF-512(PMK, "Pairwise key expansion", min(AA,SA)||max(AA,SA)||min(An,Sn)||max(An,Sn))
    // KCK    = PTK[0:16]
    // MIC    = keyver 1: HMAC-MD5(KCK, eapol)[:16]; keyver 2: HMAC-SHA1(KCK, eapol)[:16]; keyver 3: AES-128-CMAC(KCK, eapol)
    // The hc22000 `eapol` field already has its MIC bytes zeroed (as hcxtools writes it), so it's hashed as-is.

    data class EapolTarget(
        val mic: ByteArray,
        val apMac: ByteArray,
        val staMac: ByteArray,
        val ssid: ByteArray,
        val anonce: ByteArray,
        val snonce: ByteArray,
        val eapol: ByteArray,
        val keyver: Int,
    ) {
        val ssidText: String get() = String(ssid, Charsets.UTF_8)
        /** keyver 1 (MD5), 2 (SHA1), 3 (AES-CMAC / 802.11w PMF) are all supported. Pure WPA3-SAE
         *  (no PSK-derived PMK) can't be dictionary-cracked this way regardless of keyver. */
        val supported: Boolean get() = keyver in 1..3
    }

    /** Parse a hashcat-22000 EAPOL line: `WPA*02*<mic>*<ap>*<sta>*<essid>*<anonce>*<eapol>*<mp>`. */
    fun parseEapol(line: String): EapolTarget? {
        val f = line.trim().split('*')
        if (f.size < 8 || f[0] != "WPA" || f[1] != "02") return null
        return try {
            val eapol = hexToBytes(f[7])
            if (eapol.size < 95) return null
            // Key Information is bytes 5..6 (big-endian) of the EAPOL-Key frame; keyver = low 3 bits.
            val keyinfo = ((eapol[5].toInt() and 0xff) shl 8) or (eapol[6].toInt() and 0xff)
            val keyver = keyinfo and 0x07
            // SNonce = the 32-byte Key Nonce at offset 17 of the EAPOL-Key frame.
            val snonce = eapol.copyOfRange(17, 49)
            val mic = hexToBytes(f[2]); val ap = hexToBytes(f[3]); val sta = hexToBytes(f[4])
            val ssid = hexToBytes(f[5]); val anonce = hexToBytes(f[6])
            if (mic.size != 16 || ap.size != 6 || sta.size != 6 || anonce.size != 32 ||
                ssid.isEmpty() || ssid.size > 32) return null
            EapolTarget(mic, ap, sta, ssid, anonce, snonce, eapol, keyver)
        } catch (e: Exception) {
            null
        }
    }

    /** IEEE 802.11i PRF-n (here 512 bits / 64 bytes) built on HMAC-SHA1. */
    fun prf512(key: ByteArray, label: String, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val a = label.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(64)
        var pos = 0
        var i = 0
        while (pos < 64) {
            mac.reset()
            mac.update(a)
            mac.update(0)
            mac.update(data)
            mac.update(i.toByte())
            val d = mac.doFinal()
            val n = minOf(20, 64 - pos)
            System.arraycopy(d, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }

    private fun lexLess(a: ByteArray, b: ByteArray): Boolean {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x < y
        }
        return a.size < b.size
    }

    /** KCK = PTK[0:16] for this passphrase + handshake. */
    fun kck(passphrase: String, t: EapolTarget): ByteArray {
        val pmk = pmk(passphrase, t.ssid)
        val b = java.io.ByteArrayOutputStream(76)
        if (lexLess(t.apMac, t.staMac)) { b.write(t.apMac); b.write(t.staMac) } else { b.write(t.staMac); b.write(t.apMac) }
        if (lexLess(t.anonce, t.snonce)) { b.write(t.anonce); b.write(t.snonce) } else { b.write(t.snonce); b.write(t.anonce) }
        return prf512(pmk, "Pairwise key expansion", b.toByteArray()).copyOf(16)
    }

    /** MIC over the (MIC-zeroed) EAPOL frame with the given KCK, per key descriptor version. */
    fun eapolMic(kckBytes: ByteArray, eapol: ByteArray, keyver: Int): ByteArray? = when (keyver) {
        1 -> hmacMic("HmacMD5", kckBytes, eapol)
        2 -> hmacMic("HmacSHA1", kckBytes, eapol)
        3 -> aesCmac(kckBytes, eapol) // 802.11w / PMF: AES-128-CMAC, already 16 bytes
        else -> null
    }

    private fun hmacMic(alg: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(alg)
        mac.init(SecretKeySpec(key, alg))
        return mac.doFinal(data).copyOf(16)
    }

    /** AES-128-CMAC (RFC 4493 / NIST SP 800-38B) — the EAPOL MIC for keyver 3 (802.11w PMF). */
    fun aesCmac(key: ByteArray, msg: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        fun enc(b: ByteArray): ByteArray = cipher.doFinal(b)
        fun shl1(input: ByteArray): ByteArray { // <<1 over 128 bits, xor Rb (0x87) if the top bit was set
            val out = ByteArray(16); var carry = 0
            for (i in 15 downTo 0) { val v = ((input[i].toInt() and 0xff) shl 1) or carry; out[i] = v.toByte(); carry = (v ushr 8) and 1 }
            if (input[0].toInt() and 0x80 != 0) out[15] = (out[15].toInt() xor 0x87).toByte()
            return out
        }
        fun xor16(a: ByteArray, b: ByteArray): ByteArray { val r = ByteArray(16); for (i in 0 until 16) r[i] = (a[i].toInt() xor b[i].toInt()).toByte(); return r }
        val k1 = shl1(enc(ByteArray(16)))
        val k2 = shl1(k1)
        val complete = msg.isNotEmpty() && msg.size % 16 == 0
        val n = if (msg.isEmpty()) 1 else (msg.size + 15) / 16
        var x = ByteArray(16)
        for (i in 0 until n - 1) x = enc(xor16(x, msg.copyOfRange(i * 16, i * 16 + 16)))
        val lastStart = (n - 1) * 16
        val mLast = if (complete) {
            xor16(msg.copyOfRange(lastStart, lastStart + 16), k1)
        } else {
            val rem = msg.size - lastStart
            val padded = ByteArray(16)
            if (rem > 0) System.arraycopy(msg, lastStart, padded, 0, rem)
            padded[rem] = 0x80.toByte()
            xor16(padded, k2)
        }
        return enc(xor16(x, mLast))
    }

    /** Dictionary-crack a 4-way EAPOL handshake. Returns the passphrase or null. */
    fun crackEapol(
        target: EapolTarget,
        words: Sequence<String>,
        onProgress: ((tested: Int) -> Unit)? = null,
    ): String? {
        if (!target.supported) return null
        var tested = 0
        for (w in words) {
            if (!wpaLenOk(w)) continue
            tested++
            if (onProgress != null && tested % 200 == 0) onProgress(tested)
            val mic = eapolMic(kck(w, target), target.eapol, target.keyver) ?: return null
            if (mic.contentEquals(target.mic)) return w
        }
        return null
    }

    // ---- primitives ----

    /** RFC 2898 PBKDF2 with HMAC-SHA1. */
    fun pbkdf2HmacSha1(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(password, "HmacSHA1"))
        val hLen = 20
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            mac.reset()
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (j in 2..iterations) {
                u = mac.doFinal(u) // HMAC(password, U_{j-1})
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(dkLen)
    }

    private fun hexToBytes(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex" }
        return ByteArray(s.length / 2) {
            ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
        }
    }
}
