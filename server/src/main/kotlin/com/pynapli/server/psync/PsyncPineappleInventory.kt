package com.pynapli.server.psync

import com.pynapli.server.subscriptions.SubscriptionService
import java.util.UUID

/**
 * Pineapple Like inventory as Psync needs it: spend one when a partner sends a Pineapple Like in a
 * session, give it back if that like never reaches the target. A narrow seam over
 * [SubscriptionService] so the Psync service stays testable with a fake.
 */
interface PsyncPineappleInventory {
    /** Spend one Pineapple Like; returns the `premium_inventory` row that paid, or null if none is left. */
    suspend fun consume(userId: UUID): UUID?

    /** Return one Pineapple Like to the row that paid for it. */
    suspend fun refund(inventoryId: UUID)
}

class SubscriptionPsyncPineappleInventory(
    private val subscriptionService: SubscriptionService,
) : PsyncPineappleInventory {
    override suspend fun consume(userId: UUID): UUID? = subscriptionService.consumePineappleLikeInventoryReturningId(userId)

    override suspend fun refund(inventoryId: UUID) {
        subscriptionService.refundPineappleLikeInventory(inventoryId)
    }
}

/**
 * Gives back Pineapple Likes that were spent in a session but never delivered: the session was
 * cancelled or expired, the target was blocked, or the partner already had a decision on the target
 * from the Swipe deck. The repository claims each one exactly once, so calling this repeatedly (it
 * runs after every completion, leave, deny and expiry sweep) never refunds twice.
 */
internal class PsyncPineappleRefunds(
    private val repository: PsyncSessionRepository,
    private val inventory: PsyncPineappleInventory?,
) {
    suspend fun sweep() {
        val inventory = inventory ?: return
        // A failed refund must not fail the request that triggered the sweep.
        runCatching { repository.claimUndeliveredPineappleRefunds().forEach { inventory.refund(it) } }
    }
}
