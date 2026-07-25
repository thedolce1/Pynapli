package com.pynapli.shared.auth

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server rotates the refresh token on every call, so the risky part of this class is not the
 * happy path but what happens when several requests 401 at once.
 */
class StoredSessionRefresherTest {
    @Test
    fun swapsTheStoredSessionForAFreshOne() =
        runBlocking {
            val store = seededStore(accessToken = "expired", refreshToken = "refresh-1")
            val api = FakeRefreshApi(refreshResult = AppResult.Success(session("fresh", "refresh-2")))

            val newToken = StoredSessionRefresher(api, store).refreshAccessToken("expired")

            assertEquals("fresh", newToken)
            assertEquals("fresh", store.currentSession()?.accessToken)
            // The rotated refresh token must be persisted, or the *next* refresh presents a dead one.
            assertEquals("refresh-2", store.currentSession()?.refreshToken)
            assertEquals("refresh-1", api.requests.single().refreshToken)
        }

    @Test
    fun concurrentCallersRefreshOnlyOnce() =
        runBlocking {
            val store = seededStore(accessToken = "expired", refreshToken = "refresh-1")
            val api = FakeRefreshApi(refreshResult = AppResult.Success(session("fresh", "refresh-2")))
            val refresher = StoredSessionRefresher(api, store)

            val tokens =
                coroutineScope {
                    List(8) { async { refresher.refreshAccessToken("expired") } }.awaitAll()
                }

            // One network refresh, and every caller gets the same usable token. A second call would
            // present the already-rotated refresh-1 and sign the user out.
            assertEquals(1, api.requests.size)
            assertEquals(List(8) { "fresh" }, tokens)
        }

    @Test
    fun terminalRejectionClearsTheSessionAndSignalsInvalidation() =
        runBlocking {
            val store = seededStore(accessToken = "expired", refreshToken = "revoked")
            val api = FakeRefreshApi(refreshResult = AppResult.Failure(AppError("INVALID_REFRESH_TOKEN", "nope")))
            val bus = SessionInvalidationBus()
            // UNDISPATCHED subscribes synchronously before the refresh emits, so the collector is
            // guaranteed live when notifyInvalidated fires — no timing race.
            val invalidated = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }

            assertNull(StoredSessionRefresher(api, store, bus).refreshAccessToken("expired"))
            // A dead session is cleared so the HTTP layer stops sending the revoked token...
            assertNull(store.currentSession())
            // ...and the app is signalled to return to sign-in.
            invalidated.await()
        }

    @Test
    fun transientFailureLeavesTheSessionIntactAndDoesNotSignOut() =
        runBlocking {
            val store = seededStore(accessToken = "expired", refreshToken = "refresh-1")
            val api = FakeRefreshApi(refreshResult = AppResult.Failure(AppError("NETWORK_ERROR", "offline")))
            val bus = SessionInvalidationBus()
            val invalidated = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }

            assertNull(StoredSessionRefresher(api, store, bus).refreshAccessToken("expired"))
            // A blip must not sign the user out: the session survives for a later retry...
            assertEquals("expired", store.currentSession()?.accessToken)
            // ...and no invalidation is emitted (the collector is still waiting).
            assertTrue(invalidated.isActive)
            invalidated.cancel()
        }

    @Test
    fun returnsNullWithNoStoredSession() =
        runBlocking {
            val api = FakeRefreshApi(refreshResult = AppResult.Success(session("fresh", "refresh-2")))

            assertNull(StoredSessionRefresher(api, InMemoryRefreshSessionStore()).refreshAccessToken(null))
            assertEquals(0, api.requests.size)
        }

    private suspend fun seededStore(
        accessToken: String,
        refreshToken: String,
    ): SessionStore = InMemoryRefreshSessionStore().apply { save(session(accessToken, refreshToken)) }

    private fun session(
        accessToken: String,
        refreshToken: String,
    ): AuthSessionDto =
        AuthSessionDto(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = AuthUserDto(userId = "user-1", status = "active"),
        )
}

private class InMemoryRefreshSessionStore : SessionStore {
    private var session: StoredSession? = null

    override suspend fun currentSession(): StoredSession? = session

    override suspend fun save(session: AuthSessionDto) {
        this.session =
            StoredSession(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                userId = session.user.userId,
                userStatus = session.user.status,
            )
    }

    override suspend fun clear() {
        session = null
    }
}

private class FakeRefreshApi(
    private val refreshResult: AppResult<AuthSessionDto>,
) : AuthApi {
    val requests = mutableListOf<RefreshSessionRequestDto>()

    override suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto> {
        requests += request
        return refreshResult
    }

    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<AuthSessionDto> = unimplemented()

    override suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto> = unimplemented()

    override suspend fun logout(): AppResult<LogoutResponseDto> = unimplemented()

    private fun <T> unimplemented(): AppResult<T> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))
}
