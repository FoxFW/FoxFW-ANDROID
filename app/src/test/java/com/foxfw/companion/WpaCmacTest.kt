package com.foxfw.companion

import org.junit.Assert.assertEquals
import org.junit.Test

/** AES-128-CMAC verified against the RFC 4493 / NIST SP 800-38B test vectors. */
class WpaCmacTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hx(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val K = hex("2b7e151628aed2a6abf7158809cf4f3c")

    @Test fun cmacEmptyMessage() { // RFC 4493 Example 1 (len 0 → K2 + 0x80 pad)
        assertEquals("bb1d6929e95937287fa37d129b756746", hx(WpaCrack.aesCmac(K, ByteArray(0))))
    }

    @Test fun cmacOneFullBlock() { // Example 2 (len 16 → K1 path)
        assertEquals("070a16b46b4d4144f79bdd9dd04a287c",
            hx(WpaCrack.aesCmac(K, hex("6bc1bee22e409f96e93d7e117393172a"))))
    }

    @Test fun cmacPartialLastBlock() { // Example 3 (len 40 → K2 + pad, multi-block)
        assertEquals("dfa66747de9ae63030ca32611497c827",
            hx(WpaCrack.aesCmac(K, hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411"))))
    }

    @Test fun cmacFourBlocks() { // Example 4 (len 64 → K1 path, 4 blocks)
        assertEquals("51f0bebf7e3b9d92fc49741779363cfe",
            hx(WpaCrack.aesCmac(K, hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
                "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"))))
    }
}
