package com.pynapli.shared.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class SocialProviderNonceTest {
    @Test
    fun sha256MatchesTheStandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        // 56 bytes: the length no longer fits the first block, so the padding spills into a second.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun googleGetsTheRawNonceAndAppleItsLowercaseHexSha256() {
        assertEquals("abc", SocialProviderNonce.forProviderRequest(SocialProvider.Google, "abc"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SocialProviderNonce.forProviderRequest(SocialProvider.Apple, "abc"),
        )
    }
}
