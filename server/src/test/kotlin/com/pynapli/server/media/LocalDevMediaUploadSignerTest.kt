package com.pynapli.server.media

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The upload URL must resolve against the host the *client* connected to, not a fixed configured
 * base — otherwise a tester on a real phone (LAN or demo tunnel) is handed the emulator loopback
 * `10.0.2.2` and every photo upload fails. Mirrors [LocalMediaAssetUrlResolverTest], which covers
 * the download half of the same rule.
 */
class LocalDevMediaUploadSignerTest {
    private val ownerUserId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val assetId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private fun signer(fallbackBaseUrl: String = "http://10.0.2.2:8080") = LocalDevMediaUploadSigner(storageBucket = "pynapli-dev", fallbackBaseUrl = fallbackBaseUrl)

    private fun LocalDevMediaUploadSigner.uploadUrl(): String =
        createUploadDestination(
            ownerUserId = ownerUserId,
            mediaAssetId = assetId,
            albumKind = "profile",
            contentType = "image/jpeg",
        ).uploadUrl

    @Test
    fun fallsBackToStaticBaseWhenNoRequestScope() {
        assertEquals(
            "http://10.0.2.2:8080/v1/media/uploads/local/$assetId",
            signer().uploadUrl(),
        )
    }

    @Test
    fun usesRequestScopedHostWhenPresent() =
        runBlocking {
            withContext(requestMediaBaseUrl.asContextElement(value = "http://192.168.1.149:8080")) {
                assertEquals(
                    "http://192.168.1.149:8080/v1/media/uploads/local/$assetId",
                    signer().uploadUrl(),
                )
            }
        }

    @Test
    fun usesTunnelOriginSoPhysicalDeviceUploadsReachTheServer() =
        runBlocking {
            // The regression: staging APK -> cloudflared tunnel. Before the fix this returned
            // http://10.0.2.2:8080/... and the PUT never left the phone.
            withContext(requestMediaBaseUrl.asContextElement(value = "https://demo.trycloudflare.com")) {
                assertEquals(
                    "https://demo.trycloudflare.com/v1/media/uploads/local/$assetId",
                    signer().uploadUrl(),
                )
            }
        }

    @Test
    fun trimsTrailingSlashFromBase() {
        assertEquals(
            "http://10.0.2.2:8080/v1/media/uploads/local/$assetId",
            signer(fallbackBaseUrl = "http://10.0.2.2:8080/").uploadUrl(),
        )
    }

    @Test
    fun keepsStorageObjectKeyIndependentOfRequestHost() =
        runBlocking {
            val expectedKey = "local/users/$ownerUserId/profile/$assetId.jpg"
            val direct =
                signer().createUploadDestination(ownerUserId, assetId, "profile", "image/jpeg")
            withContext(requestMediaBaseUrl.asContextElement(value = "https://demo.trycloudflare.com")) {
                val tunneled =
                    signer().createUploadDestination(ownerUserId, assetId, "profile", "image/jpeg")
                // Only the reachable host changes; where the bytes land must not.
                assertEquals(expectedKey, direct.storageObjectKey)
                assertEquals(expectedKey, tunneled.storageObjectKey)
            }
        }
}
