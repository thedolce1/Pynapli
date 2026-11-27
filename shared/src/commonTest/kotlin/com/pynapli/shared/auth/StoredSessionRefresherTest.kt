package com.pynapli.shared.auth

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.AuthApi
import com.pynapli.shared.network.ProfileApi
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.EmailSignupStartedDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        runTest {
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
        runTest {
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
        runTest {
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
            // Without settle config (single-process stores) there is no waiting at all.
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun terminalRejectionWaitsForTheWinningTabsSaveBeforeSigningOut() =
        runTest {
            // The losing tab usually hears its rejection before the winning tab has saved: the store
            // still holds the rejected token at first, and the rotated session lands a bit later.
            val store = seededStore(accessToken = "expired", refreshToken = "refresh-1")
            val api = FakeRefreshApi(refreshResult = AppResult.Failure(AppError("INVALID_REFRESH_TOKEN", "already rotated")))
            val bus = SessionInvalidationBus()
            val invalidated = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }
            launch {
                delay(450)
                store.save(session("other-tab-fresh", "refresh-2"))
            }

            val token =
                StoredSessionRefresher(api, store, bus, settleAttempts = 5, settleDelayMillis = 300)
                    .refreshAccessToken("expired")

            assertEquals("other-tab-fresh", token)
            // Picked up on the re-read after the second delay, not after all five.
            assertEquals(600L, testScheduler.currentTime)
            assertEquals("refresh-2", store.currentSession()?.refreshToken)
            assertTrue(invalidated.isActive)
            invalidated.cancel()
        }

    @Test
    fun terminalRejectionSignsOutAfterTheSettleAttemptsWhenNoTabRotated() =
        runTest {
            val store = seededStore(accessToken = "expired", refreshToken = "revoked")
            val api = FakeRefreshApi(refreshResult = AppResult.Failure(AppError("INVALID_REFRESH_TOKEN", "nope")))
            val bus = SessionInvalidationBus()
            val invalidated = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }

            assertNull(
                StoredSessionRefresher(api, store, bus, settleAttempts = 5, settleDelayMillis = 300)
                    .refreshAccessToken("expired"),
            )

            assertEquals(1_500L, testScheduler.currentTime)
            assertNull(store.currentSession())
            invalidated.await()
        }

    @Test
    fun terminalRejectionWithAnEmptiedStoreSignsOutWithoutWaiting() =
        runTest {
            // Another tab signed out while this refresh was in flight.
            val store = seededStore(accessToken = "expired", refreshToken = "refresh-1")
            val api =
                FakeRefreshApi(
                    refreshResult = AppResult.Failure(AppError("INVALID_REFRESH_TOKEN", "nope")),
                    onRefresh = { store.clear() },
                )
            val bus = SessionInvalidationBus()
            val invalidated = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }

            assertNull(
                StoredSessionRefresher(api, store, bus, settleAttempts = 5, settleDelayMillis = 300)
                    .refreshAccessToken("expired"),
            )

            assertEquals(0L, testScheduler.currentTime)
            invalidated.await()
        }

    @Test
    fun terminalRejectionAfterAnotherTabRotatedKeepsTheirSessionAndDoesNotSignOut() =
        runTest {
            // Two browser tabs share one localStorage session but each has its own mutex, so both can
            // present the same refresh token. The server lets exactly one win; the loser gets
            // INVALID_REFRESH_TOKEN *after* the winner has already stored the rotated session.
            val store = seededStore(accessToken = "expired", refreshToken = "refresh-1")
            val api =
                FakeRefreshApi(
                    refreshResult = AppResult.Failure(AppError("INVALID_REFRESH_TOKEN", "already rotated")),
                    // The other tab's write lands while this call is in flight.
                    onRefresh = { store.save(session("other-tab-fresh", "refresh-2")) },
                )
            val bus = SessionInvalidationBus()
            val invalidated = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }

            val token = StoredSessionRefresher(api, store, bus).refreshAccessToken("expired")

            // The rejection was for a token that is no longer ours: adopt the other tab's session...
            assertEquals("other-tab-fresh", token)
            assertEquals("refresh-2", store.currentSession()?.refreshToken)
            // ...and do not sign every tab out.
            assertTrue(invalidated.isActive)
            invalidated.cancel()
        }

    @Test
    fun transientFailureLeavesTheSessionIntactAndDoesNotSignOut() =
        runTest {
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
        runTest {
            val api = FakeRefreshApi(refreshResult = AppResult.Success(session("fresh", "refresh-2")))

            assertNull(StoredSessionRefresher(api, InMemoryRefreshSessionStore()).refreshAccessToken(null))
            assertEquals(0, api.requests.size)
        }

    @Test
    fun aSignOutDuringARefreshIsNotUndoneByIt() =
        runTest {
            val store = seededStore(accessToken = "expired", refreshToken = "refresh-1")
            val gate = CompletableDeferred<Unit>()
            val api = FakeRefreshApi(refreshResult = AppResult.Success(session("fresh", "refresh-2")), onRefresh = { gate.await() })
            val lock = SessionMutationLock()
            val refresher = StoredSessionRefresher(api, store, lock = lock)
            val repository = AuthRepository(authApi = api, profileApi = UnusedProfileApi, sessionStore = store, sessionLock = lock)

            // A request 401'd and its refresh is on the wire when the member taps Log out.
            val refreshing = launch { refresher.refreshAccessToken("expired") }
            runCurrent()
            val loggingOut = launch { repository.logout() }
            runCurrent()
            gate.complete(Unit)
            refreshing.join()
            loggingOut.join()

            assertNull(store.currentSession(), "the refresh's rotated session must not sign the member back in")
        }

    @Test
    fun aRefreshAnswerIsDroppedWhenTheStoreWasEmptiedMeanwhile() =
        runTest {
            // Another tab signed out (shared localStorage; this process's lock cannot see it).
            lateinit var store: SessionStore
            val api = FakeRefreshApi(refreshResult = AppResult.Success(session("fresh", "refresh-2")), onRefresh = { store.clear() })
            store = seededStore(accessToken = "expired", refreshToken = "refresh-1")

            val token = StoredSessionRefresher(api, store).refreshAccessToken("expired")

            assertNull(token)
            assertNull(store.currentSession())
        }

    @Test
    fun aRefreshAnswerNeverOverwritesANewerSession() =
        runTest {
            // Someone else signed in (or another tab rotated it) while this refresh was on the wire.
            lateinit var store: SessionStore
            val api =
                FakeRefreshApi(
                    refreshResult = AppResult.Success(session("fresh", "refresh-2")),
                    onRefresh = { store.save(session("newer", "refresh-newer")) },
                )
            store = seededStore(accessToken = "expired", refreshToken = "refresh-1")

            val token = StoredSessionRefresher(api, store).refreshAccessToken("expired")

            assertEquals("newer", token)
            assertEquals("refresh-newer", store.currentSession()?.refreshToken)
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

/** AuthRepository needs one; the sign-out race never reaches it. */
private object UnusedProfileApi : ProfileApi {
    override suspend fun getMe(): AppResult<MeDto> = unused()

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> = unused()

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> = unused()

    override suspend fun getPublicProfile(userId: String): AppResult<PublicProfileDto> = unused()

    private fun <T> unused(): AppResult<T> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))
}

private class FakeRefreshApi(
    private val refreshResult: AppResult<AuthSessionDto>,
    private val onRefresh: suspend () -> Unit = {},
) : AuthApi {
    val requests = mutableListOf<RefreshSessionRequestDto>()

    override suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto> {
        requests += request
        onRefresh()
        return refreshResult
    }

    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<EmailSignupStartedDto> = unimplemented()

    override suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto> = unimplemented()

    override suspend fun logout(): AppResult<LogoutResponseDto> = unimplemented()

    private fun <T> unimplemented(): AppResult<T> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))
}
