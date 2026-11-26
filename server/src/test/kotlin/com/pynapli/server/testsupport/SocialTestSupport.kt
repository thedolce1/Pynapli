package com.pynapli.server.testsupport

import com.pynapli.server.auth.social.AppleTokenClient
import com.pynapli.server.auth.social.SocialIdentityVerifier
import com.pynapli.server.auth.social.SocialProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A fake identity provider for tests: generated RSA keys (never real ones), a JWKS served over HTTP on
 * loopback (so the real TokenVerifier fetches and parses it), and signed ID tokens. Optional fake Apple
 * `/auth/token` and `/auth/revoke` endpoints record what they receive. Close it after the test.
 */
internal class FakeIdentityProvider : AutoCloseable {
    private val keys = ConcurrentHashMap<String, KeyPair>()

    @Volatile var publishedKids: List<String> = emptyList()

    /** How many times the JWKS was fetched. */
    val jwksFetches = java.util.concurrent.atomic.AtomicInteger()

    /** Form bodies the fake Apple endpoints received, as decoded maps. */
    val tokenRequests: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
    val revokeRequests: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())

    /** What `/auth/token` answers (status, JSON body). */
    @Volatile var tokenResponse: Pair<Int, String> = 500 to "{}"

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/jwks") { exchange ->
                jwksFetches.incrementAndGet()
                val body = jwksJson().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/auth/token") { exchange ->
                tokenRequests += decodeForm(exchange.requestBody.readBytes().decodeToString())
                val (status, json) = tokenResponse
                val body = json.toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/auth/revoke") { exchange ->
                revokeRequests += decodeForm(exchange.requestBody.readBytes().decodeToString())
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
    val jwksUrl: String get() = "$baseUrl/jwks"

    init {
        addKey("kid-1", publish = true)
    }

    fun addKey(
        kid: String,
        publish: Boolean,
    ) {
        keys[kid] = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        if (publish) publishedKids = publishedKids + kid
    }

    fun verifier(
        provider: SocialProvider,
        audiences: List<String> = listOf(audienceFor(provider)),
        clock: Clock = Clock.systemUTC(),
    ) = SocialIdentityVerifier(provider = provider, acceptedAudiences = audiences, jwksUrl = jwksUrl, clock = clock)

    /** A signed ID token; a null claim argument leaves that claim out. */
    fun idToken(
        provider: SocialProvider,
        subject: String,
        nonce: String?,
        email: String? = null,
        emailVerified: JsonElement? = null,
        extra: Map<String, JsonElement> = emptyMap(),
        issuer: String = SocialIdentityVerifier.allowedIssuers(provider).first(),
        audience: String = audienceFor(provider),
        issuedAt: Instant = Instant.now().minusSeconds(5),
        expiresAt: Instant? = Instant.now().plusSeconds(600),
        kid: String = "kid-1",
        algorithm: String = "RS256",
        signWith: PrivateKey = keys.getValue(kid).private,
    ): String {
        val header =
            buildJsonObject {
                put("alg", algorithm)
                put("kid", kid)
                put("typ", "JWT")
            }
        val payload =
            buildJsonObject {
                put("iss", issuer)
                put("aud", audience)
                put("sub", subject)
                put("iat", issuedAt.epochSecond)
                expiresAt?.let { put("exp", it.epochSecond) }
                nonce?.let { put("nonce", it) }
                email?.let { put("email", it) }
                emailVerified?.let { put("email_verified", it) }
                extra.forEach { (name, value) -> put(name, value) }
            }
        val signingInput = "${b64(header.toString().toByteArray())}.${b64(payload.toString().toByteArray())}"
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(signWith)
        signer.update(signingInput.toByteArray())
        return "$signingInput.${b64(signer.sign())}"
    }

    fun privateKey(kid: String): PrivateKey = keys.getValue(kid).private

    fun publicKey(kid: String): java.security.PublicKey = keys.getValue(kid).public

    private fun jwksJson(): String {
        val entries =
            publishedKids.joinToString(",") { kid ->
                val public = keys.getValue(kid).public as RSAPublicKey
                """{"kty":"RSA","alg":"RS256","use":"sig","kid":"$kid","n":"${b64(public.modulus.unsigned())}","e":"${b64(public.publicExponent.unsigned())}"}"""
            }
        return """{"keys":[$entries]}"""
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        const val GOOGLE_CLIENT_ID = "test-web.apps.googleusercontent.com"
        const val APPLE_CLIENT_ID = "com.pynapli.app"

        fun audienceFor(provider: SocialProvider): String =
            when (provider) {
                SocialProvider.GOOGLE -> GOOGLE_CLIENT_ID
                SocialProvider.APPLE -> APPLE_CLIENT_ID
            }

        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun BigInteger.unsigned(): ByteArray {
            val bytes = toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }

        private fun decodeForm(body: String): Map<String, String> =
            body
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val name = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    java.net.URLDecoder.decode(name, Charsets.UTF_8) to java.net.URLDecoder.decode(value, Charsets.UTF_8)
                }

        fun bool(value: Boolean): JsonElement = JsonPrimitive(value)

        fun str(value: String): JsonElement = JsonPrimitive(value)
    }
}

/** Records exchanges and revocations instead of calling Apple. */
internal class RecordingAppleTokenClient(
    var refreshTokenToIssue: String? = "apple-refresh-token-1",
) : AppleTokenClient {
    val exchanges: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())
    val revocations: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    override suspend fun exchangeCode(
        clientId: String,
        code: String,
        expectedSubject: String,
    ): String? {
        exchanges += Triple(clientId, code, expectedSubject)
        return refreshTokenToIssue
    }

    override suspend fun revoke(
        clientId: String,
        refreshToken: String,
    ): Boolean {
        revocations += clientId to refreshToken
        return true
    }
}
