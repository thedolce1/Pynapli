package com.pynapli.server.account

import com.google.auth.oauth2.ServiceAccountCredentials
import com.pynapli.server.media.GcsV4SignedUrlFactory
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GcsAccountExportStorageTest {
    private val now = Instant.parse("2026-05-31T12:00:00Z")
    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val requestId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val archive = ExportArchive("export.json", "application/json", """{"userId":"x"}""".encodeToByteArray())

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val signer =
        GcsV4SignedUrlFactory(
            credentials =
                ServiceAccountCredentials
                    .newBuilder()
                    .setClientEmail("export-signer@pynapli-test.iam.gserviceaccount.com")
                    .setPrivateKey(keyPair.private)
                    .setPrivateKeyId("test-key-id")
                    .setProjectId("pynapli-test")
                    .build(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    @Test
    fun storeUploadsToTheUserScopedKeyAndReturnsASignedDownloadUrl() =
        runBlocking {
            val uploader = RecordingUploader()
            val storage = GcsAccountExportStorage(signer, bucket = "pynapli-private", uploader = uploader)

            val stored = storage.store(userId, requestId, archive)

            val objectPath = "/pynapli-private/account-exports/$userId/$requestId.json"
            val uploadUrl = uploader.url!!
            assertTrue(uploadUrl.startsWith("https://storage.googleapis.com$objectPath?"), uploadUrl)
            assertTrue("X-Goog-Expires=900" in uploadUrl, "upload URL should be short-lived: $uploadUrl")
            assertContentEquals(archive.bytes, uploader.bytes)
            assertEquals("application/json", uploader.contentType)

            assertTrue(stored.downloadUrl.startsWith("https://storage.googleapis.com$objectPath?"), stored.downloadUrl)
            assertTrue("X-Goog-Signature=" in stored.downloadUrl)
            assertTrue(stored.downloadUrl != uploadUrl, "GET and PUT need separate signatures")
            assertEquals(now.plus(Duration.ofDays(7)), stored.expiresAt)
        }

    @Test
    fun rejectedUploadFailsTheStoreSoNoDownloadUrlIsHandedOut() {
        val storage =
            GcsAccountExportStorage(
                signer,
                bucket = "pynapli-private",
                uploader = { _, _, _ -> error("Export upload rejected with HTTP 403") },
            )

        assertFailsWith<IllegalStateException> {
            runBlocking { storage.store(userId, requestId, archive) }
        }
    }

    private class RecordingUploader : ExportObjectUploader {
        var url: String? = null
        var bytes: ByteArray? = null
        var contentType: String? = null

        override suspend fun put(
            signedUrl: String,
            bytes: ByteArray,
            contentType: String,
        ) {
            url = signedUrl
            this.bytes = bytes
            this.contentType = contentType
        }
    }
}
