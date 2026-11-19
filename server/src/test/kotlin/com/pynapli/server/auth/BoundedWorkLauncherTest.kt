package com.pynapli.server.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class BoundedWorkLauncherTest {
    @Test
    fun pastTheCeilingJobsAreDroppedWithoutBlockingTheCaller() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val launcher = BoundedWorkLauncher(scope, maxConcurrent = 1, maxPending = 2)
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val ran = AtomicInteger(0)

            repeat(5) {
                launcher.launch {
                    release.await()
                    if (ran.incrementAndGet() == 2) finished.complete(Unit)
                }
            }

            // launch returned at once for all five; two were taken, three dropped.
            assertEquals(3L, launcher.dropped)
            release.complete(Unit)
            withTimeout(10_000) { finished.await() }
            assertEquals(2, ran.get())
            scope.cancel()
        }

    @Test
    fun atMostMaxConcurrentJobsRunAtOnce() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val launcher = BoundedWorkLauncher(scope, maxConcurrent = 2, maxPending = 10)
            val running = AtomicInteger(0)
            val peak = AtomicInteger(0)
            val done = AtomicInteger(0)
            val allDone = CompletableDeferred<Unit>()

            repeat(6) {
                launcher.launch {
                    val now = running.incrementAndGet()
                    peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    delay(20)
                    running.decrementAndGet()
                    if (done.incrementAndGet() == 6) allDone.complete(Unit)
                }
            }

            withTimeout(10_000) { allDone.await() }
            assertEquals(0L, launcher.dropped)
            assertEquals(true, peak.get() <= 2)
            scope.cancel()
        }
}
