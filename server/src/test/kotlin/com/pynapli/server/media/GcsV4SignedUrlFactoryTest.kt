package com.pynapli.server.media

import com.google.auth.oauth2.ServiceAccountCredentials
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GcsV4SignedUrlFactoryTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-05-31T12:00:00Z"), ZoneOffset.UTC)
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val credentials: ServiceAccountCredentials =
        ServiceAccountCredentials
            .newBuilder()
            .setClientEmail("media-signer@pynapli-prod.iam.gserviceaccount.com")
            .setPrivateKey(keyPair.private)
            .setPrivateKeyId("test-key-id")
            .setProjectId("pynapli-prod")
            .build()

    private fun factory() =
        GcsV4SignedUrlFactory(
            credentials = credentials,
            region = "auto",
            clock = fixedClock,
        )

    @Test
    fun signedUrlHasV4StructureAndDeterministicQueryParams() {
        val signed =
            factory().sign(
                method = "PUT",
                bucket = "pynapli-prod-media",
                objectKey = "users/abc/private/asset-1.jpg",
                ttl = Duration.ofMinutes(10),
            )

        assertTrue(
            signed.url.startsWith("https://storage.googleapis.com/pynapli-prod-media/users/abc/private/asset-1.jpg?"),
            "unexpected base URL: ${signed.url}",
        )
        assertTrue(signed.url.contains("X-Goog-Algorithm=GOOG4-RSA-SHA256"), signed.url)
        assertTrue(signed.url.contains("X-Goog-Date=20260531T120000Z"), signed.url)
        assertTrue(signed.url.contains("X-Goog-Expires=600"), signed.url)
        assertTrue(signed.url.contains("X-Goog-SignedHeaders=host"), signed.url)
        // Credential scope is URL-encoded ("/" -> %2F) and dated.
        assertTrue(
            signed.url.contains(
                "X-Goog-Credential=media-signer%40pynapli-prod.iam.gserviceaccount.com%2F20260531%2Fauto%2Fstorage%2Fgoog4_request",
            ),
            signed.url,
        )
        assertTrue(Regex("&X-Goog-Signature=[0-9a-f]+$").containsMatchIn(signed.url), signed.url)
        assertEquals(fixedClock.instant().epochSecond + 600, signed.expiresAtEpochSeconds)
    }

    @Test
    fun signatureVerifiesAgainstPublicKeyOverReconstructedStringToSign() {
        val signed =
            factory().sign(
                method = "PUT",
                bucket = "pynapli-prod-media",
                objectKey = "users/abc/private/asset-1.jpg",
                ttl = Duration.ofMinutes(10),
            )

        // Split the produced URL back into its parts.
        val (base, fullQuery) = signed.url.removePrefix("https://storage.googleapis.com").split("?", limit = 2)
        val signatureMarker = "&X-Goog-Signature="
        val canonicalQuery = fullQuery.substringBefore(signatureMarker)
        val signatureHex = fullQuery.substringAfter(signatureMarker)

        val timestamp = "20260531T120000Z"
        val credentialScope = "20260531/auto/storage/goog4_request"

        // Reconstruct the canonical request + string-to-sign from the URL output (independent oracle).
        val canonicalRequest =
            listOf(
                "PUT",
                base,
                canonicalQuery,
                "host:storage.googleapis.com\n",
                "host",
                "UNSIGNED-PAYLOAD",
            ).joinToString("\n")
        val canonicalRequestHashHex =
            MessageDigest
                .getInstance("SHA-256")
                .digest(canonicalRequest.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val stringToSign =
            listOf(
                "GOOG4-RSA-SHA256",
                timestamp,
                credentialScope,
                canonicalRequestHashHex,
            ).joinToString("\n")

        val verifier =
            Signature.getInstance("SHA256withRSA").apply {
                initVerify(keyPair.public)
                update(stringToSign.toByteArray(StandardCharsets.UTF_8))
            }
        assertTrue(
            verifier.verify(signatureHex.hexToByteArray()),
            "V4 signature did not verify over the reconstructed string-to-sign",
        )
    }

    @Test
    fun expiryIsClampedToGcsMaximum() {
        val signed =
            factory().sign(
                method = "GET",
                bucket = "b",
                objectKey = "o",
                ttl = Duration.ofDays(30),
            )
        assertTrue(signed.url.contains("X-Goog-Expires=604800"), signed.url)
    }

    private fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
