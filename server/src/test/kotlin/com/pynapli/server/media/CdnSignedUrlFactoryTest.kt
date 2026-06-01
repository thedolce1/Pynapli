package com.pynapli.server.media

import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies the Cloud CDN signed-URL construction against the documented algorithm
 * (https://cloud.google.com/cdn/docs/using-signed-urls): HMAC-SHA1 over the URL up to and including
 * `Expires` + `KeyName`, key base64url-decoded to raw bytes, signature base64url-encoded, params in
 * the exact order Expires, KeyName, Signature.
 */
class CdnSignedUrlFactoryTest {
    private val keyName = "pynapli-media-key"

    // 16 raw bytes, base64url-encoded (as a Cloud CDN key would be provided).
    private val rawKey = ByteArray(16) { (it + 1).toByte() }
    private val base64UrlKey = Base64.getUrlEncoder().encodeToString(rawKey)

    private val fixedClock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

    private fun factory() = CdnSignedUrlFactory(keyName = keyName, base64UrlKey = base64UrlKey, clock = fixedClock)

    @Test
    fun signsWithExpectedParamOrderAndExpiry() {
        val signed = factory().sign("https://cdn.pynapli.com/media/asset-1", Duration.ofMinutes(15))
        val expectedExpires = fixedClock.instant().epochSecond + 900

        assertEquals(expectedExpires, signed.expiresAtEpochSeconds)
        // Params must appear in the documented order.
        val query = signed.url.substringAfter('?')
        assertTrue(query.startsWith("Expires=$expectedExpires&KeyName=$keyName&Signature="), "unexpected query: $query")
    }

    @Test
    fun signatureMatchesIndependentHmacSha1OverUrlThroughKeyName() {
        val signed = factory().sign("https://cdn.pynapli.com/media/asset-1", Duration.ofMinutes(15))
        val expires = signed.expiresAtEpochSeconds
        val unsigned = "https://cdn.pynapli.com/media/asset-1?Expires=$expires&KeyName=$keyName"

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(rawKey, "HmacSHA1"))
        val expectedSignature =
            Base64.getUrlEncoder().encodeToString(mac.doFinal(unsigned.toByteArray(StandardCharsets.UTF_8)))

        val actualSignature = signed.url.substringAfter("&Signature=")
        assertEquals(expectedSignature, actualSignature)
        // The full URL is exactly the signed prefix plus the signature.
        assertEquals("$unsigned&Signature=$expectedSignature", signed.url)
    }

    @Test
    fun rejectsBaseUrlWithExistingQueryString() {
        assertFailsWith<IllegalArgumentException> {
            factory().sign("https://cdn.pynapli.com/media/asset-1?foo=bar", Duration.ofMinutes(15))
        }
    }

    @Test
    fun rejectsRelativeBaseUrl() {
        assertFailsWith<IllegalArgumentException> {
            factory().sign("cdn.pynapli.com/media/asset-1", Duration.ofMinutes(15))
        }
    }

    @Test
    fun rejectsBlankKeyName() {
        assertFailsWith<IllegalArgumentException> {
            CdnSignedUrlFactory(keyName = "  ", base64UrlKey = base64UrlKey)
        }
    }

    @Test
    fun clampsTtlToSevenDayCeiling() {
        val signed = factory().sign("https://cdn.pynapli.com/media/asset-1", Duration.ofDays(30))
        val maxExpires = fixedClock.instant().epochSecond + 604_800
        assertEquals(maxExpires, signed.expiresAtEpochSeconds)
    }
}
