package com.pynapli.server.media

import com.google.auth.oauth2.ServiceAccountCredentials
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TreeMap

/**
 * Produces GCS V4 (`GOOG4-RSA-SHA256`) signed URLs for a single object operation (PUT for uploads,
 * GET for downloads). The RSA-SHA256 signing step uses the local private key carried by a
 * [ServiceAccountCredentials] (`google-auth`, already on the classpath via firebase-admin) — no
 * extra SDK and no network call. The canonical-request / string-to-sign construction follows the
 * documented GCS V4 algorithm: https://cloud.google.com/storage/docs/access-control/signed-urls
 *
 * Signed headers are `host` plus any `extraSignedHeaders` (uploads sign the create-only
 * precondition); the payload is `UNSIGNED-PAYLOAD`. Live end-to-end validation against a real bucket requires a
 * provisioned GCS bucket + service account (founder/ops step).
 */
class GcsV4SignedUrlFactory(
    private val credentials: ServiceAccountCredentials,
    private val region: String = "auto",
    private val host: String = "storage.googleapis.com",
    private val clock: Clock = Clock.systemUTC(),
) {
    data class SignedUrl(
        val url: String,
        val expiresAtEpochSeconds: Long,
    )

    /**
     * [extraSignedHeaders] are signed alongside `host` and the client must send them verbatim (GCS:
     * "Headers prefixed with `x-goog-`" must be canonical headers when used). The upload signer uses
     * this for `x-goog-if-generation-match: 0`, so the URL can only create the object, never overwrite
     * it after it was screened.
     */
    fun sign(
        method: String,
        bucket: String,
        objectKey: String,
        ttl: Duration,
        extraSignedHeaders: Map<String, String> = emptyMap(),
    ): SignedUrl {
        require(bucket.isNotBlank()) { "bucket must not be blank" }
        require(objectKey.isNotBlank()) { "objectKey must not be blank" }

        val now = clock.instant().atZone(ZoneOffset.UTC)
        val timestamp = LONG_DATE.format(now)
        val datestamp = SHORT_DATE.format(now)
        val expiresSeconds = ttl.seconds.coerceIn(MIN_EXPIRY_SECONDS, MAX_EXPIRY_SECONDS)

        val credentialScope = "$datestamp/$region/storage/goog4_request"
        val canonicalUri = "/${encodePathSegment(bucket)}/${encodeObjectKey(objectKey)}"

        // Canonical headers: lowercase names, sorted by code point, trimmed values, "name:value\n".
        val headers =
            TreeMap<String, String>().apply {
                put("host", host)
                extraSignedHeaders.forEach { (name, value) -> put(name.trim().lowercase(Locale.US), value.trim()) }
            }
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }

        // Query params must be sorted by key and percent-encoded (RFC 3986) for the canonical query.
        val queryParams =
            TreeMap<String, String>().apply {
                put("X-Goog-Algorithm", ALGORITHM)
                put("X-Goog-Credential", "${credentials.clientEmail}/$credentialScope")
                put("X-Goog-Date", timestamp)
                put("X-Goog-Expires", expiresSeconds.toString())
                put("X-Goog-SignedHeaders", signedHeaders)
            }
        val canonicalQuery =
            queryParams.entries.joinToString("&") { "${rfc3986(it.key)}=${rfc3986(it.value)}" }

        val canonicalRequest =
            listOf(
                method.uppercase(Locale.US),
                canonicalUri,
                canonicalQuery,
                canonicalHeaders,
                signedHeaders,
                "UNSIGNED-PAYLOAD",
            ).joinToString("\n")

        val stringToSign =
            listOf(
                ALGORITHM,
                timestamp,
                credentialScope,
                hex(sha256(canonicalRequest.toByteArray(StandardCharsets.UTF_8))),
            ).joinToString("\n")

        val signature = hex(credentials.sign(stringToSign.toByteArray(StandardCharsets.UTF_8)))
        val url = "https://$host$canonicalUri?$canonicalQuery&X-Goog-Signature=$signature"
        return SignedUrl(url = url, expiresAtEpochSeconds = now.toEpochSecond() + expiresSeconds)
    }

    private fun encodeObjectKey(objectKey: String): String =
        objectKey.split("/").joinToString("/") {
            encodePathSegment(it)
        }

    companion object {
        const val ALGORITHM = "GOOG4-RSA-SHA256"
        private const val MIN_EXPIRY_SECONDS = 1L
        private const val MAX_EXPIRY_SECONDS = 604_800L // GCS caps V4 signed URLs at 7 days.

        private val LONG_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        private val SHORT_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                out.append(HEX[(b.toInt() ushr 4) and 0xF])
                out.append(HEX[b.toInt() and 0xF])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"

        // A path segment encodes everything except RFC 3986 unreserved chars; "/" is added by the
        // caller between segments so it is not encoded here.
        private fun encodePathSegment(segment: String): String = rfc3986(segment)

        // RFC 3986 percent-encoding: unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~".
        private fun rfc3986(value: String): String {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            val out = StringBuilder(bytes.size * 3)
            for (byte in bytes) {
                val ch = byte.toInt() and 0xFF
                if (ch.isUnreserved()) {
                    out.append(ch.toChar())
                } else {
                    out.append('%')
                    out.append(HEX_UPPER[(ch ushr 4) and 0xF])
                    out.append(HEX_UPPER[ch and 0xF])
                }
            }
            return out.toString()
        }

        private const val HEX_UPPER = "0123456789ABCDEF"

        private fun Int.isUnreserved(): Boolean =
            (this in 'A'.code..'Z'.code) ||
                (this in 'a'.code..'z'.code) ||
                (this in '0'.code..'9'.code) ||
                this == '-'.code ||
                this == '.'.code ||
                this == '_'.code ||
                this == '~'.code
    }
}
