package com.foxfw.companion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The parallel CPU backend must agree with the single-threaded WpaCrack oracle. */
class CrackBackendTest {
    private val target = WpaCrack.parsePmkid(
        "WPA*01*f414df3ad563bd18581c358d506b9448*aabbccddeeff*112233445566*466f784e65743547***",
    )!!

    @Test fun parallelCpuFindsPassphrase() = runBlocking {
        val words = buildList {
            repeat(50) { add("filler%08d".format(it)) } // all wrong, valid length
            add("letmein123")
            repeat(50) { add("tail%08d".format(it)) }
        }
        val hit = CpuCrackBackend.crackPmkid(target, words) {}
        assertEquals("letmein123", hit)
    }

    @Test fun parallelCpuReturnsNullWhenAbsent() = runBlocking {
        val words = (0 until 100).map { "nomatch%06d".format(it) }
        assertNull(CpuCrackBackend.crackPmkid(target, words) {})
    }
}
