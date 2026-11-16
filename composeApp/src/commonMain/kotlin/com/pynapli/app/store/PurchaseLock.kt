package com.pynapli.app.store

import kotlinx.coroutines.CompletableDeferred

/**
 * One store checkout (or restore) at a time, for a gateway whose store answers through a callback.
 *
 * Claim synchronously, before the first suspension point, so two quick taps can't both pass the
 * check; then release in `finally` on every exit. [release] and [complete] only clear the lock while
 * the claim is still the current one, so a late or repeated release of an old claim never frees a
 * newer one. Not thread-safe: callers and the store callback share one thread (Android main).
 */
class PurchaseLock<P, R> {
    var current: Claim<P, R>? = null
        private set

    val isHeld: Boolean get() = current != null

    /** The new claim, or null when another one is still held. */
    fun tryClaim(payload: P): Claim<P, R>? {
        if (current != null) return null
        return Claim<P, R>(payload).also { current = it }
    }

    fun release(claim: Claim<P, R>) {
        if (current === claim) current = null
    }

    /** Releases [claim] and hands [result] to whoever awaits it. False when it already had one. */
    fun complete(
        claim: Claim<P, R>,
        result: R,
    ): Boolean {
        release(claim)
        return claim.result.complete(result)
    }

    class Claim<P, R> internal constructor(
        val payload: P,
    ) {
        val result = CompletableDeferred<R>()

        /** Set once the store's own UI is open, so its callback belongs to this claim. */
        var launched: Boolean = false
    }
}
