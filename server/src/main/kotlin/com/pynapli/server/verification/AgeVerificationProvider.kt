package com.pynapli.server.verification

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Seam for a third-party age-verification provider. The server stays the authority: the provider only
 * hands back an opaque reference + a redirect URL the client opens; the provider later confirms the
 * outcome via [AgeVerificationService.callback]. Real provider integrations (network handshakes,
 * webhooks, signing) are an ops/integration step — this interface keeps that out of the service.
 */
interface AgeVerificationProvider {
    /** Stable provider identifier persisted on the verification record (e.g. "persona"). */
    val providerName: String

    /** Method key advertised in requirements and accepted by [AgeVerificationService.start]. */
    val methodKey: String

    /** Human-readable label shown to the client for the provider method. */
    val label: String

    suspend fun startSession(
        userId: UUID,
        market: String,
    ): AgeVerificationProviderSession
}

data class AgeVerificationProviderSession(
    val providerReference: String,
    val redirectUrl: String,
)

/**
 * Default provider-hosted implementation: no network call. It mints an opaque provider reference and
 * builds a redirect URL pointing at the provider's hosted flow. Real providers swap in a concrete
 * implementation that performs the actual session handshake.
 */
class RedirectAgeVerificationProvider(
    override val providerName: String,
    override val label: String,
    private val redirectBaseUrl: String,
    override val methodKey: String = "provider_hosted",
) : AgeVerificationProvider {
    override suspend fun startSession(
        userId: UUID,
        market: String,
    ): AgeVerificationProviderSession {
        val providerReference = UUID.randomUUID().toString()
        val redirectUrl =
            buildString {
                append(redirectBaseUrl)
                append("?ref=")
                append(providerReference.urlEncode())
                append("&market=")
                append(market.urlEncode())
            }
        return AgeVerificationProviderSession(
            providerReference = providerReference,
            redirectUrl = redirectUrl,
        )
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
}
