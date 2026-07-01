package com.pynapli.server.admin

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminTotpTest {
    // RFC 6238 test-vector secret: ASCII "12345678901234567890" encoded as base32.
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun generatesRfc6238Codes() {
        // The RFC 8-digit vectors truncated to 6 digits: T=59 -> 94287082, T=1111111109 -> 07081804.
        assertEquals("287082", AdminTotp.generate(secret, 59))
        assertEquals("081804", AdminTotp.generate(secret, 1111111109))
    }

    @Test
    fun verifyAcceptsCurrentCodeWithinWindowAndRejectsOthers() {
        val now = 1_700_000_000L
        val code = AdminTotp.generate(secret, now)
        assertTrue(AdminTotp.verify(secret, code, now))
        assertTrue(AdminTotp.verify(secret, code, now + 25)) // +/-1 step clock-skew tolerance
        assertFalse(AdminTotp.verify(secret, code, now + 120)) // outside the window
        assertFalse(AdminTotp.verify(secret, "000000", now)) // wrong code
        assertFalse(AdminTotp.verify(secret, "12345", now)) // wrong length
    }
}
