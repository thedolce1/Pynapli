package com.pynapli.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class AppResumeRefresherTest {
    @Test
    fun refetchesOnForegroundButCoalescesBurstsAndSkipsWhenSignedOut() =
        runTest {
            val clock = TestTimeSource()
            var refreshes = 0
            val refresher = AppResumeRefresher(refreshAll = { refreshes += 1 }, timeSource = clock, minInterval = 10.seconds)

            refresher.onForeground(signedIn = false)
            assertEquals(0, refreshes, "signed out: nothing to refetch")

            refresher.onForeground(signedIn = true)
            assertEquals(1, refreshes)

            // A permission dialog or tab flip seconds later is the same return, not a new one.
            clock += 3.seconds
            refresher.onForeground(signedIn = true)
            assertEquals(1, refreshes)

            clock += 10.seconds
            refresher.onForeground(signedIn = true)
            assertEquals(2, refreshes)
        }
}
