package com.pynapli.server.auth.social

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64

/**
 * Apple's token endpoints (Sign in with Apple REST API): exchanges an authorization code for the
 * refresh token, and revokes it. Apple requires apps that offer account deletion to revoke the user's
 * tokens; the refresh token is kept (sealed, [SocialTokenSeal]) only for that.
 */
interface AppleTokenClient {
    /**
     * The refresh token for [code], issued to [clientId], or null on any failure (best-effort: a sign-in
     * never fails because of it). The id_token in the response must name [expectedSubject] and
     * [clientId], so a code from someone else's Apple account is never stored against this identity.
     */
    suspend fun exchangeCode(
        clientId: String,
        code: String,
        expectedSubject: String,
    ): String?

    /** True when Apple answered 200 (revoked, or the token was already invalid). */
    suspend fun revoke(
        clientId: String,
        refreshToken: String,
    ): Boolean
}

/**
 * `POST https://appleid.apple.com/auth/token` (grant_type=authorization_code) and
 * `POST https://appleid.apple.com/auth/revoke` (token_type_hint=refresh_token), form-encoded, each with
 * a client secret: an ES256 JWT (header `kid`; claims `iss` team id, `iat`, `exp` within six months,
 * `aud` https://appleid.apple.com, `sub` the client id) signed with the .p8 key. A fresh 5-minute secret
 * per call: there are few calls and nothing to cache. Neither the code, the tokens nor the secret are
 * ever logged; failures log the HTTP status or the exception class only.
 */
class HttpAppleTokenClient(
    private val teamId: String,
    private val keyId: String,
    privateKeyPem: String,
    private val redirectUris: Map<String, String> = emptyMap(),
    private val tokenUrl: String = APPLE_TOKEN_URL,
    private val revokeUrl: String = APPLE_REVOKE_URL,
    private val clock: Clock = Clock.systemUTC(),
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : AppleTokenClient {
    private val privateKey: PrivateKey = parseEcPrivateKey(privateKeyPem)

    override suspend fun exchangeCode(
        clientId: String,
        code: String,
        expectedSubject: String,
    ): String? {
        val trimmed = code.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CODE_LENGTH) return null
        val form =
            buildMap<String, String> {
                put("client_id", clientId)
                put("client_secret", clientSecret(clientId))
                put("code", trimmed)
                put("grant_type", "authorization_code")
                redirectUris[clientId]?.let { put("redirect_uri", it) }
            }
        val response = post(tokenUrl, form, "token exchange") ?: return null
        if (response.statusCode() != 200) {
            logger.warn("Apple token exchange answered HTTP {}", response.statusCode())
            return null
        }
        return runCatching {
            val body = Json.parseToJsonElement(response.body()).jsonObject
            val refreshToken = body.string("refresh_token") ?: return null
            val idToken = body.string("id_token") ?: return null
            // Straight from Apple over TLS, so its claims are read without a second signature check
            // (OpenID Connect Core 3.1.3.7); it only has to be about the identity just verified.
            val claims = unverifiedClaims(idToken)
            if (claims.string("sub") != expectedSubject || claims.string("aud") != clientId) {
                logger.warn("Apple token exchange returned a token for another identity; not stored")
                return null
            }
            refreshToken
        }.getOrElse { error ->
            logger.warn("Apple token exchange response unreadable ({})", error.javaClass.simpleName)
            null
        }
    }

    override suspend fun revoke(
        clientId: String,
        refreshToken: String,
    ): Boolean {
        val form =
            mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret(clientId),
                "token" to refreshToken,
                "token_type_hint" to "refresh_token",
            )
        val response = post(revokeUrl, form, "revocation") ?: return false
        if (response.statusCode() != 200) logger.warn("Apple token revocation answered HTTP {}", response.statusCode())
        return response.statusCode() == 200
    }

    internal fun clientSecret(clientId: String): String {
        val now = clock.instant().epochSecond
        val header = """{"alg":"ES256","kid":${jsonString(keyId)}}"""
        val payload =
            """{"iss":${jsonString(teamId)},"iat":$now,"exp":${now + CLIENT_SECRET_LIFETIME_SECONDS},"aud":"https://appleid.apple.com","sub":${jsonString(clientId)}}"""
        val signingInput = "${base64Url(header.toByteArray())}.${base64Url(payload.toByteArray())}"
        // JWS ES256 needs the raw R||S signature (64 bytes), not the JDK's default DER encoding.
        val signer = Signature.getInstance("SHA256withECDSAinP1363Format")
        signer.initSign(privateKey)
        signer.update(signingInput.toByteArray(Charsets.US_ASCII))
        return "$signingInput.${base64Url(signer.sign())}"
    }

    private suspend fun post(
        url: String,
        form: Map<String, String>,
        what: String,
    ): HttpResponse<String>? {
        val body = form.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return try {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn("Apple {} request failed ({})", what, error.javaClass.simpleName)
            null
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(HttpAppleTokenClient::class.java)
        const val APPLE_TOKEN_URL = "https://appleid.apple.com/auth/token"
        const val APPLE_REVOKE_URL = "https://appleid.apple.com/auth/revoke"
        private const val CLIENT_SECRET_LIFETIME_SECONDS = 300L
        private const val MAX_CODE_LENGTH = 2_048

        /** The .p8 key's PEM text (PKCS#8). Accepts literal "\n" sequences, as env vars often carry them. */
        fun parseEcPrivateKey(pem: String): PrivateKey {
            val base64 =
                pem
                    .replace("\\n", "\n")
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("-----") }
                    .joinToString("")
            val der = Base64.getMimeDecoder().decode(base64)
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
        }

        internal fun unverifiedClaims(jwt: String): JsonObject {
            val parts = jwt.split('.')
            require(parts.size == 3) { "not a JWT" }
            return Json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8)).jsonObject
        }

        private fun JsonObject.string(name: String): String? = runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

        private fun jsonString(value: String): String =
            Json.encodeToString(
                kotlinx.serialization.json.JsonPrimitive
                    .serializer(),
                kotlinx.serialization.json.JsonPrimitive(value),
            )
    }
}
