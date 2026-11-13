package com.pynapli.server.db

import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Two replicas share one Postgres: while one runs a job tick, the other skips it. */
class PostgresJobLockIntegrationTest {
    private val replicaA = PostgresJobLock(PynapliTestDatabase.dataSource)
    private val replicaB = PostgresJobLock(PynapliTestDatabase.dataSource)

    @Test
    fun aJobRunsOnOneReplicaAtATimeAndIsFreeAgainAfterward() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val running =
                async {
                    replicaA.runExclusive("lock-test-job") {
                        started.complete(Unit)
                        finish.await()
                        "ran on A"
                    }
                }
            withTimeout(5_000) { started.await() }

            assertNull(replicaB.runExclusive("lock-test-job") { "ran on B" }, "B must skip while A holds the job")
            // A different job is not blocked.
            assertEquals("other", replicaB.runExclusive("lock-test-other-job") { "other" })

            finish.complete(Unit)
            assertEquals("ran on A", running.await())
            assertEquals("ran on B", replicaB.runExclusive("lock-test-job") { "ran on B" })
        }
}
