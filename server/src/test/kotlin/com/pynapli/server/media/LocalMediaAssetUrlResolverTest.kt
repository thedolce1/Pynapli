package com.pynapli.server.media

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalMediaAssetUrlResolverTest {
    private val assetId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Test
    fun fallsBackToStaticBaseWhenNoRequestScope() {
        val resolver = LocalMediaAssetUrlResolver("http://10.0.2.2:8080")

        assertEquals(
            "http://10.0.2.2:8080/v1/media/uploads/local/$assetId",
            resolver.publicContentUrl(assetId),
        )
    }

    @Test
    fun usesRequestScopedHostWhenPresent() =
        runBlocking {
            val resolver = LocalMediaAssetUrlResolver("http://10.0.2.2:8080")

            withContext(requestMediaBaseUrl.asContextElement(value = "http://192.168.1.149:8080")) {
                assertEquals(
                    "http://192.168.1.149:8080/v1/media/uploads/local/$assetId",
                    resolver.publicContentUrl(assetId),
                )
            }
        }

    @Test
    fun trimsTrailingSlashFromBase() {
        val resolver = LocalMediaAssetUrlResolver("http://10.0.2.2:8080/")

        assertEquals(
            "http://10.0.2.2:8080/v1/media/uploads/local/$assetId",
            resolver.publicContentUrl(assetId),
        )
    }
}
