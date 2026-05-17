package com.pynapli.app.swipe

import com.pynapli.shared.storage.IosPynapliDatabaseFactory
import com.pynapli.shared.storage.PynapliDatabase

/**
 * Single entrypoint the iOS Swift shell calls to build a queue-store-wired
 * `SwipeStateHolder` on iOS. The store itself lives in `commonMain`
 * (`SqlDelightSwipeActionQueueStore`) — only the database construction is
 * platform-specific (`NativeSqliteDriver`). This factory closes the iOS half
 * of `SWIPE_IMPLEMENTATION_SPEC.md` L726 (bounded offline action queue with
 * persistence on both mobile platforms).
 *
 * Process-death persistence QA on a real iPhone is still pending and tracked
 * separately — Windows/Linux CI can't drive an iOS simulator.
 */
object IosSwipeFactory {

    /**
     * Builds a `SwipeActionQueueStore` backed by the shared `PynapliDatabase`.
     * Pass an explicit database when the host app already owns the instance
     * (so SQLDelight isn't initialized twice); pass null to construct a fresh
     * one via `IosPynapliDatabaseFactory`.
     */
    fun createSwipeActionQueueStore(database: PynapliDatabase? = null): SwipeActionQueueStore {
        val db = database ?: IosPynapliDatabaseFactory().createDatabase()
        return SqlDelightSwipeActionQueueStore(db)
    }

    /**
     * Convenience: builds a `SwipeStateHolder` with the SQLDelight-backed queue
     * store already wired. Pass the network-backed `SwipeRepository` the shell
     * has set up (`ApiSwipeRepository` with the iOS Ktor client).
     */
    fun createSwipeStateHolder(
        repository: SwipeRepository,
        database: PynapliDatabase? = null
    ): SwipeStateHolder = SwipeStateHolder(
        repository = repository,
        queueStore = createSwipeActionQueueStore(database)
    )
}
