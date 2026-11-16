package com.pynapli.server.testsupport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Starts [first] and [second] on separate IO threads and releases them together, so two requests
 * that race in production overlap in the test as tightly as the JVM allows. Each side's outcome is
 * captured rather than thrown: a racer that legitimately loses (an already-actioned card, say) must
 * not hide what the database ended up holding, which is what these tests assert on.
 */
suspend fun <A, B> raceTogether(
    first: suspend () -> A,
    second: suspend () -> B,
): Pair<Result<A>, Result<B>> =
    coroutineScope {
        val gate = CompletableDeferred<Unit>()
        val a =
            async(Dispatchers.IO) {
                gate.await()
                runCatching { first() }
            }
        val b =
            async(Dispatchers.IO) {
                gate.await()
                runCatching { second() }
            }
        gate.complete(Unit)
        a.await() to b.await()
    }
