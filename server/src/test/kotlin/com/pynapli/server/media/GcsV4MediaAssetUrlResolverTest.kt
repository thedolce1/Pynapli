package com.pynapli.server.media

import com.google.auth.oauth2.ServiceAccountCredentials
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GcsV4MediaAssetUrlResolverTest {
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

    private val factory = GcsV4SignedUrlFactory(credentials = credentials, region = "auto", clock = fixedClock)

    @Test
    fun resolverReturnsSignedGetUrlForDerivableKey() {
        val resolver = GcsV4MediaAssetUrlResolver(storageBucket = "pynapli-prod-media", signedUrlFactory = factory)
        val assetId = UUID.fromString("11111111-2222-3333-4444-555555555555")

        val url = resolver.publicContentUrl(assetId)

        assertTrue(
            url.startsWith("https://storage.googleapis.com/pynapli-prod-media/media/$assetId?"),
            "unexpected URL: $url",
        )
        assertTrue(url.contains("X-Goog-SignedHeaders=host"), url)
        assertTrue(Regex("&X-Goog-Signature=[0-9a-f]+$").containsMatchIn(url), url)
    }

    @Test
    fun uploadAndDownloadAgreeOnObjectKey() {
        val assetId = UUID.randomUUID()
        val uploadSigner = GcsV4MediaUploadSigner(storageBucket = "b", signedUrlFactory = factory)

        val uploadKey =
            uploadSigner
                .createUploadDestination(
                    ownerUserId = UUID.randomUUID(),
                    mediaAssetId = assetId,
                    albumKind = "private",
                    contentType = "image/jpeg",
                ).storageObjectKey

        // The download resolver derives the same key from the asset id alone.
        assertEquals("media/$assetId", uploadKey)
    }

    @Test
    fun downloadTtlIsShortLived() {
        val resolver =
            GcsV4MediaAssetUrlResolver(
                storageBucket = "b",
                signedUrlFactory = factory,
                ttl = Duration.ofMinutes(15),
            )
        val url = resolver.publicContentUrl(UUID.randomUUID())
        assertTrue(url.contains("X-Goog-Expires=900"), url)
    }
}
