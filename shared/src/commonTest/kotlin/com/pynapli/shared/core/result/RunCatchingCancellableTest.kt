package com.pynapli.shared.core.result

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RunCatchingCancellableTest {
    @Test
    fun returnsSuccessLikeRunCatching() {
        assertEquals("ok", runCatchingCancellable { "ok" }.getOrNull())
    }

    @Test
    fun stillCapturesOrdinaryFailures() {
        val result = runCatchingCancellable { error("boom") }

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun rethrowsCancellationInsteadOfCapturingIt() {
        assertFailsWith<CancellationException> {
            runCatchingCancellable { throw CancellationException("cancelled") }
        }
    }

    /**
     * The regression this exists for: plain `runCatching` turns a cancelled suspend call into an
     * ordinary failure, so the coroutine keeps running past the point it was told to stop. On the
     * real call path that surfaced as `AppError("NETWORK_ERROR", "Cannot reach Pynapli server…")`
     * for what was only a navigation away.
     */
    @Test
    fun cancellingAScopeStopsTheCallerRatherThanYieldingAFailureResult() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var observedFailureResult = false
            var reachedCodeAfterTheCall = false

            val job =
                launch {
                    val result =
                        runCatchingCancellable {
                            started.complete(Unit)
                            // Suspends until the surrounding job is cancelled.
                            CompletableDeferred<String>().await()
                        }
                    // Neither line may run: cancellation must unwind, not fall through.
                    observedFailureResult = result.isFailure
                    reachedCodeAfterTheCall = true
                }

            started.await()
            job.cancel()
            job.join()

            assertFalse(observedFailureResult, "cancellation must not be reported as a failure Result")
            assertFalse(reachedCodeAfterTheCall, "cancellation must unwind the coroutine, not fall through")
            assertTrue(job.isCancelled)
        }

    /** Documents the exact stdlib behaviour being avoided, so the contrast cannot silently drift. */
    @Test
    fun plainRunCatchingSwallowsCancellationWhichIsWhyThisHelperExists() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var swallowed = false

            val job =
                launch {
                    @Suppress("TooGenericExceptionCaught")
                    val result =
                        runCatching {
                            started.complete(Unit)
                            CompletableDeferred<String>().await()
                        }
                    swallowed = result.isFailure && result.exceptionOrNull() is CancellationException
                    yield()
                }

            started.await()
            job.cancel()
            job.join()

            assertTrue(swallowed, "stdlib runCatching captures CancellationException as a failure")
        }
}
