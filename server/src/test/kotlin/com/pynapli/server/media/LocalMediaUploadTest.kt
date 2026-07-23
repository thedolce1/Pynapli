package com.pynapli.server.media

import org.junit.Test
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalMediaUploadTest {
    @Test
    fun localDevSignerReturnsEmulatorReachableUploadUrl() {
        val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val mediaAssetId = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val signer =
            LocalDevMediaUploadSigner(
                storageBucket = "pynapli-local-media",
                fallbackBaseUrl = "http://10.0.2.2:8080",
                clock = fixedClock,
            )

        val destination =
            signer.createUploadDestination(
                ownerUserId = ownerId,
                mediaAssetId = mediaAssetId,
                albumKind = "private",
                contentType = "image/webp",
            )

        assertEquals("local", destination.storageProvider)
        assertEquals("pynapli-local-media", destination.storageBucket)
        assertEquals("local/users/$ownerId/private/$mediaAssetId.webp", destination.storageObjectKey)
        assertEquals("http://10.0.2.2:8080/v1/media/uploads/local/$mediaAssetId", destination.uploadUrl)
        assertEquals("PUT", destination.uploadMethod)
        assertEquals("image/webp", destination.uploadHeaders["Content-Type"])
        assertEquals(Instant.parse("2026-04-24T18:10:00Z"), destination.expiresAt)
    }

    @Test
    fun localStoragePersistsBytesAndMetadata() {
        val root = Files.createTempDirectory("pynapli-local-media-test")
        val mediaAssetId = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val bytes = byteArrayOf(1, 2, 3, 4)

        try {
            val storage = LocalMediaUploadStorage(root, fixedClock)

            val record =
                storage.saveUpload(
                    mediaAssetIdText = mediaAssetId.toString(),
                    contentType = "image/png",
                    bytes = bytes,
                )

            assertEquals(mediaAssetId, record.mediaAssetId)
            assertEquals(bytes.size, record.byteSize)
            assertContentEquals(bytes, Files.readAllBytes(record.dataPath))
            assertTrue(Files.readString(record.metadataPath).contains("contentType=image/png"))
            assertTrue(Files.readString(record.metadataPath).contains("uploadedAt=2026-04-24T18:00:00Z"))

            val content = storage.loadUpload(mediaAssetId.toString())

            assertEquals(mediaAssetId, content.mediaAssetId)
            assertEquals("image/png", content.contentType)
            assertContentEquals(bytes, content.bytes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        private val fixedClock = Clock.fixed(Instant.parse("2026-04-24T18:00:00Z"), ZoneOffset.UTC)
    }
}
