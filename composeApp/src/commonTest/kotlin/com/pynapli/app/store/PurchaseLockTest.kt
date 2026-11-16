package com.pynapli.app.store

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PurchaseLockTest {
    @Test
    fun aSecondClaimWhileOneIsHeldIsRejectedAndTheFirstStillCompletes() =
        runTest {
            val lock = PurchaseLock<String, String>()
            val first = assertNotNull(lock.tryClaim("boost"))

            assertNull(lock.tryClaim("plus"))
            assertSame(first, lock.current)

            val awaited = async { first.result.await() }
            assertTrue(lock.complete(first, "Added to your balance."))
            assertEquals("Added to your balance.", awaited.await())
            assertFalse(lock.isHeld)
        }

    /** The gateway's real shape: claim before suspending, release in finally. */
    @Test
    fun concurrentPurchasesThatSuspendBeforeLaunchingCannotBothClaim() =
        runTest {
            val lock = PurchaseLock<String, String>()
            val connected = CompletableDeferred<Unit>()

            suspend fun purchase(product: String): String {
                val claim = lock.tryClaim(product) ?: return "in progress"
                try {
                    connected.await() // ensureConnected() / queryProductDetails()
                    claim.launched = true
                    return claim.result.await()
                } finally {
                    lock.release(claim)
                }
            }

            val tapOne = async { purchase("boost") }
            val tapTwo = async { purchase("boost") }
            runCurrent()
            connected.complete(Unit)
            runCurrent()

            assertEquals("in progress", tapTwo.await())
            val claim = assertNotNull(lock.current)
            lock.complete(claim, "done")
            assertEquals("done", tapOne.await())
            assertFalse(lock.isHeld)
        }

    @Test
    fun aClaimWhoseCallerWasCancelledIsReleased() =
        runTest {
            val lock = PurchaseLock<String, String>()

            val purchase =
                async {
                    val claim = lock.tryClaim("plus")!!
                    try {
                        claim.launched = true
                        claim.result.await() // Play never calls back
                    } finally {
                        lock.release(claim)
                    }
                }
            runCurrent()
            assertTrue(lock.isHeld)

            purchase.cancel()
            runCurrent()

            assertFalse(lock.isHeld)
            assertNotNull(lock.tryClaim("plus"), "a later purchase is locked out by a cancelled one")
        }

    @Test
    fun releasingAnOldClaimDoesNotFreeANewerOne() =
        runTest {
            val lock = PurchaseLock<String, String>()
            val old = lock.tryClaim("boost")!!
            assertTrue(lock.complete(old, "first"))
            val newer = lock.tryClaim("plus")!!

            // The old claim's finally runs (or Play calls back for it again) after the new claim.
            lock.release(old)
            assertFalse(lock.complete(old, "again"))

            assertSame(newer, lock.current)
            assertEquals("first", old.result.await())
        }
}
