package com.pynapli.shared.core.result

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for suspending work, minus the bug.
 *
 * `kotlin.runCatching` catches [Throwable], and in coroutines that includes [CancellationException].
 * Wrapping a suspend call in it therefore swallows cancellation: the coroutine keeps running past a
 * point where it was told to stop, and — because the cancellation is reported as an ordinary
 * failure — the user is shown a real error for what was just a navigation away.
 *
 * That was live on every network call in the app. `KtorApiSupport` mapped a cancelled request to
 * `AppError("NETWORK_ERROR", "Cannot reach Pynapli server…")`, which `GridStateHolder` then rethrew
 * as a `GridFeedException`, so leaving the Grid mid-load surfaced a backend-outage message.
 *
 * The WebSocket clients (`MessageStreamClient` and friends) already got this right with an explicit
 * `catch (e: CancellationException) { throw e }` ahead of their `catch (_: Throwable)`. This is the
 * same rule, packaged so the HTTP and state-holder paths cannot drift from it again.
 *
 * Use this wherever the block can suspend. Plain [runCatching] stays correct for genuinely
 * synchronous work — JSON parsing, `Instant.parse`, `SingletonImageLoader.get` — because no
 * cancellation can surface there.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }
