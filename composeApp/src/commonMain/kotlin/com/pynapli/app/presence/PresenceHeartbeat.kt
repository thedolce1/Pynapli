package com.pynapli.app.presence

import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.network.PynapliApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The light presence heartbeat (plan G2): `POST /v1/presence/heartbeat` once on becoming eligible
 * and then about every [interval], so the server keeps this member online while the app is open
 * even when they are not scrolling the Grid.
 *
 * Eligible = signed in, not in the sign-up stepper, and the app visible. One shared ticker in common
 * code, launched with the realtime jobs (`PynapliCommonGraph.startRealtime`), so Android and iOS stop
 * it in the background exactly as they stop the Grid's liveness refresh; the web shell reports tab
 * visibility through the same [appVisible] signal. A failed beat is dropped, never retried: the next
 * tick is the retry, and the server's online hold covers a missed one.
 */
class PresenceHeartbeat(
    private val send: suspend () -> Unit,
    private val signedIn: Flow<Boolean>,
    private val inSignupStepper: Flow<Boolean>,
    private val appVisible: Flow<Boolean>,
    private val interval: Duration = DEFAULT_INTERVAL,
) {
    /** Runs until cancelled. */
    suspend fun run() {
        combine(signedIn, inSignupStepper, appVisible) { authed, signingUp, visible -> authed && !signingUp && visible }
            .distinctUntilChanged()
            .collectLatest { eligible ->
                if (!eligible) return@collectLatest
                while (true) {
                    // Fire and forget: no retry, no backoff; a failure waits for the next tick.
                    runCatchingCancellable { send() }
                    delay(interval)
                }
            }
    }

    companion object {
        val DEFAULT_INTERVAL: Duration = 60.seconds

        /** The real beat over the API client (no Ktor outside `:shared`). */
        fun sender(apiClient: PynapliApiClient): suspend () -> Unit = { apiClient.sendPresenceHeartbeat() }
    }
}
