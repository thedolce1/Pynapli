package com.pynapli.shared.network

import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.SessionStore
import com.pynapli.shared.auth.SocialAgeGate
import com.pynapli.shared.auth.SocialAuthErrorCodes
import com.pynapli.shared.auth.SocialIdentityRepository
import com.pynapli.shared.auth.SocialProvider
import com.pynapli.shared.auth.StoredSession
import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.config.PynapliEnvironment
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AuthSessionDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorSocialAuthApiTest {
    @Test
    fun configIsAnUnauthenticatedGetAndMissingFlagsMeanHidden() =
        runTest {
            var method: HttpMethod? = null
            var path: String? = null
            var authorization: String? = "unset"
            val api =
                client { request ->
                    method = request.method
                    path = request.url.encodedPath
                    authorization = request.headers[HttpHeaders.Authorization]
                    respondJson("""{"google":true,"apple":false}""")
                }
            val config = assertIs<AppResult.Success<*>>(api.socialSignInConfig()).value as com.pynapli.shared.network.dto.SocialSignInConfigDto
            assertEquals(HttpMethod.Get, method)
            assertEquals("/v1/auth/social/config", path)
            assertNull(authorization)
            assertTrue(config.google)
            assertFalse(config.apple)
        }

    @Test
    fun socialSignInPostsTheEmailSignupFieldNamesAndStoresTheSession() =
        runTest {
            var path: String? = null
            var body: JsonObject? = null
            val api =
                client { request ->
                    path = request.url.encodedPath
                    body = request.body.jsonBody()
                    respondJson("""{"accessToken":"a","refreshToken":"r","user":{"userId":"u-1","status":"active"}}""")
                }
            val store = MemoryStore()
            val repository = AuthRepository(authApi = api, profileApi = api, sessionStore = store)
            val result =
                repository.signInSocial(
                    provider = SocialProvider.Apple,
                    idToken = "id-token",
                    nonce = "nonce-1",
                    authorizationCode = "code-1",
                    ageGate = SocialAgeGate(birthdate = "2000-06-01", confirmedAdult = true, acceptedTerms = true, acceptedPrivacy = true),
                    platform = "web",
                    appVersion = "0.1.0",
                    deviceId = "device-1",
                )
            assertIs<AppResult.Success<*>>(result)
            assertEquals("/v1/auth/social/apple", path)
            val sent = body!!
            assertEquals("id-token", sent["idToken"]!!.jsonPrimitive.content)
            assertEquals("nonce-1", sent["nonce"]!!.jsonPrimitive.content)
            assertEquals("code-1", sent["authorizationCode"]!!.jsonPrimitive.content)
            assertEquals("2000-06-01", sent["birthdate"]!!.jsonPrimitive.content)
            assertTrue(sent["confirmedAdult"]!!.jsonPrimitive.boolean)
            assertTrue(sent["acceptedTerms"]!!.jsonPrimitive.boolean)
            assertTrue(sent["acceptedPrivacy"]!!.jsonPrimitive.boolean)
            assertEquals("web", sent["platform"]!!.jsonPrimitive.content)
            assertEquals("device-1", sent["deviceId"]!!.jsonPrimitive.content)
            assertEquals("u-1", store.currentSession()?.userId)
        }

    @Test
    fun needsAgeGateComesBackAsItsCodeAndStoresNothing() =
        runTest {
            val api =
                client {
                    respond(
                        content = """{"code":"${SocialAuthErrorCodes.NEEDS_AGE_GATE}","message":"Tell us your birthday first.","requestId":"req-1"}""",
                        status = HttpStatusCode.UnprocessableEntity,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val store = MemoryStore()
            val result =
                AuthRepository(authApi = api, profileApi = api, sessionStore = store)
                    .signInSocial(SocialProvider.Google, "t", "n", null, null, "android", "1")
            val failure = assertIs<AppResult.Failure>(result)
            assertEquals(SocialAuthErrorCodes.NEEDS_AGE_GATE, failure.error.code)
            assertNull(store.currentSession())
        }

    @Test
    fun identitiesUseTheAuthenticatedRoutes() =
        runTest {
            val calls = mutableListOf<Pair<HttpMethod, String>>()
            var authorization: String? = null
            val api =
                client { request ->
                    calls += request.method to request.url.encodedPath
                    authorization = request.headers[HttpHeaders.Authorization]
                    respondJson(
                        """{"identities":[{"provider":"google","linkedAt":"2026-11-26T00:00:00Z","emailHint":"j•••@gmail.com"}],"hasPassword":false}""",
                    )
                }
            val repository = SocialIdentityRepository(api)
            val methods = assertIs<AppResult.Success<*>>(repository.signInMethods()).value as com.pynapli.shared.network.dto.LinkedIdentitiesDto
            repository.link(SocialProvider.Google, "t", "n", null)
            repository.unlink(SocialProvider.Apple)
            assertEquals("Bearer token-1", authorization)
            assertEquals(
                listOf(
                    HttpMethod.Get to "/v1/auth/identities",
                    HttpMethod.Post to "/v1/auth/identities/google",
                    HttpMethod.Delete to "/v1/auth/identities/apple",
                ),
                calls,
            )
            assertEquals("google", methods.identities.single().provider)
            assertEquals("j•••@gmail.com", methods.identities.single().emailHint)
            assertEquals(false, methods.hasPassword)
        }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorPynapliApiClient =
        KtorPynapliApiClient(
            config = PynapliConfig(environment = PynapliEnvironment.Local, apiBaseUrl = "http://localhost:8080", websocketBaseUrl = "ws://localhost:8080"),
            accessTokenProvider = StaticAccessTokenProvider("token-1"),
            httpClient =
                HttpClient(MockEngine(handler)) {
                    expectSuccess = false
                    install(ContentNegotiation) { json(PynapliJson) }
                },
        )

    private fun MockRequestHandleScope.respondJson(content: String): HttpResponseData = respond(content = content, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private fun OutgoingContent.jsonBody(): JsonObject = Json.parseToJsonElement((this as TextContent).text).jsonObject

    private class MemoryStore : SessionStore {
        private var session: StoredSession? = null

        override suspend fun currentSession(): StoredSession? = session

        override suspend fun save(session: AuthSessionDto) {
            this.session = StoredSession(session.accessToken, session.refreshToken, session.user.userId, session.user.status)
        }

        override suspend fun clear() {
            session = null
        }
    }
}
