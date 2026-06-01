package com.pynapli.server.media

import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Produces Google **Cloud CDN** signed URLs (a different scheme from [GcsV4SignedUrlFactory]):
 * HMAC-SHA1 over the URL, with `Expires`/`KeyName`/`Signature` query params in that exact order.
 * Used to serve private media through a Cloud CDN-backed bucket so delivery is cached at the edge
 * and cheap at scale, while the signature (not the path) remains the access gate.
 *
 * Algorithm (https://cloud.google.com/cdn/docs/using-signed-urls):
 *  - String to sign = the full URL including scheme/host/path plus `?Expires=…&KeyName=…`.
 *  - Key = the raw bytes of the base64url-decoded CDN signing key (NOT the encoded form).
 *  - Signature = base64url(HMAC-SHA1(stringToSign)), trailing `=` left intact.
 *  - `Expires` = unix epoch seconds.
 *
 * The CDN key + key name are provisioned on the backend bucket by ops (founder/GCP step); this
 * factory only does the signing, so it needs no network call and no extra dependency.
 */
class CdnSignedUrlFactory(
    private val keyName: String,
    base64UrlKey: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val keyBytes: ByteArray = Base64.getUrlDecoder().decode(base64UrlKey)

    init {
        require(keyName.isNotBlank()) { "CDN keyName must not be blank" }
        require(keyBytes.isNotEmpty()) { "CDN signing key must not be empty" }
    }

    data class SignedUrl(
        val url: String,
        val expiresAtEpochSeconds: Long,
    )

    /**
     * Signs a GET URL for [baseUrl] (scheme + host + path, e.g. `https://cdn.pynapli.com/media/<id>`),
     * valid for [ttl]. [baseUrl] must not already carry a query string.
     */
    fun sign(
        baseUrl: String,
        ttl: Duration,
    ): SignedUrl {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "CDN baseUrl must be absolute"
        }
        require(!baseUrl.contains('?')) { "CDN baseUrl must not contain a query string" }
        val expiresSeconds =
            clock.instant().epochSecond + ttl.seconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)

        // Per the spec, the signature covers the URL up to and including Expires + KeyName.
        val separator = "?"
        val unsigned = "$baseUrl${separator}Expires=$expiresSeconds&KeyName=$keyName"
        val signature = base64UrlEncode(hmacSha1(unsigned.toByteArray(StandardCharsets.UTF_8)))
        return SignedUrl(
            url = "$unsigned&Signature=$signature",
            expiresAtEpochSeconds = expiresSeconds,
        )
    }

    private fun hmacSha1(data: ByteArray): ByteArray =
        Mac.getInstance(HMAC_SHA1).run {
            init(SecretKeySpec(keyBytes, HMAC_SHA1))
            doFinal(data)
        }

    private companion object {
        const val HMAC_SHA1 = "HmacSHA1"
        const val MIN_TTL_SECONDS = 1L
        const val MAX_TTL_SECONDS = 604_800L // cap at 7 days, matching the GCS V4 ceiling

        // base64url, padding retained (the spec keeps trailing '=' un-escaped in the URL).
        fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().encodeToString(bytes)
    }
}
