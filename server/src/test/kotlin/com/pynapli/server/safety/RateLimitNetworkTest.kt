package com.pynapli.server.safety

import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimitNetworkTest {
    @Test
    fun ipv4StaysAsItIs() {
        assertEquals("203.0.113.9", rateLimitNetwork("203.0.113.9"))
        assertEquals("203.0.113.9", rateLimitNetwork(" 203.0.113.9 "))
    }

    @Test
    fun ipv6IsBucketedToItsSlash64InFullAndCompressedForms() {
        val bucket = "2001:db8:1234:5678::/64"
        assertEquals(bucket, rateLimitNetwork("2001:0db8:1234:5678:aaaa:bbbb:cccc:dddd"))
        assertEquals(bucket, rateLimitNetwork("2001:DB8:1234:5678::1"))
        assertEquals(bucket, rateLimitNetwork("2001:db8:1234:5678:ffff::"))
        assertEquals(bucket, rateLimitNetwork("[2001:db8:1234:5678::42]"))
        assertEquals("2001:db8:0:0::/64", rateLimitNetwork("2001:db8::1"))
        assertEquals("0:0:0:0::/64", rateLimitNetwork("::1"))
    }

    @Test
    fun anIpv4MappedAddressIsItsIpv4Address() {
        assertEquals("203.0.113.9", rateLimitNetwork("::ffff:203.0.113.9"))
        assertEquals("203.0.113.9", rateLimitNetwork("0:0:0:0:0:ffff:cb00:7109"))
    }

    @Test
    fun aZoneSuffixIsDropped() {
        assertEquals("fe80:0:0:0::/64", rateLimitNetwork("fe80::1%eth0"))
        assertEquals("fe80:0:0:0::/64", rateLimitNetwork("fe80::2%3"))
    }

    @Test
    fun anythingThatDoesNotParseIsLeftAlone() {
        assertEquals("unknown-address", rateLimitNetwork("unknown-address"))
        assertEquals("1:2:3", rateLimitNetwork("1:2:3"))
        assertEquals("1::2::3", rateLimitNetwork("1::2::3"))
    }
}
