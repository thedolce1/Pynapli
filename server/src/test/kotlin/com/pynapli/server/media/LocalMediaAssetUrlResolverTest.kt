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

    @Test
    fun mediaBaseUrlKeepsDirectConnectionHostAndPort() {
        assertEquals(
            "http://10.0.2.2:8080",
            mediaBaseUrl(originScheme = "http", hostHeaderHost = "10.0.2.2", hostHeaderPort = 8080),
        )
    }

    @Test
    fun mediaBaseUrlHonorsForwardedProtoBehindTlsProxy() {
        // cloudflared / Cloud Run terminate TLS and forward plain HTTP: the URL must stay https
        // (Android refuses cleartext) and must not carry the backend port.
        assertEquals(
            "https://demo.trycloudflare.com",
            mediaBaseUrl(
                originScheme = "http",
                hostHeaderHost = "demo.trycloudflare.com",
                hostHeaderPort = 80,
                forwardedProto = "https",
            ),
        )
    }

    @Test
    fun mediaBaseUrlHonorsForwardedHostWithExplicitPort() {
        assertEquals(
            "https://api.example.com:8443",
            mediaBaseUrl(
                originScheme = "http",
                hostHeaderHost = "backend.internal",
                hostHeaderPort = 8080,
                forwardedProto = "https, http",
                forwardedHost = "api.example.com:8443, backend.internal",
            ),
        )
    }

    @Test
    fun mediaBaseUrlOmitsDefaultPortForScheme() {
        assertEquals(
            "http://localhost",
            mediaBaseUrl(originScheme = "http", hostHeaderHost = "localhost", hostHeaderPort = 80),
        )
    }
}
