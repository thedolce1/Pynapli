package com.pynapli.server.subscriptions

import java.sql.ResultSet
import java.util.UUID

internal fun ResultSet.toSubscriptionRecord(): SubscriptionRecord =
    SubscriptionRecord(
        subscriptionId = getObject("subscription_id", UUID::class.java),
        userId = getObject("subscription_user_id", UUID::class.java),
        tier = getString("tier"),
        status = getString("status"),
        currentPeriodStart = getTimestamp("current_period_start").toInstant(),
        currentPeriodEnd = getTimestamp("current_period_end").toInstant()
    )

internal fun ResultSet.toPlayStoreSubscriptionVerificationCandidate(): PlayStoreSubscriptionVerificationCandidate =
    PlayStoreSubscriptionVerificationCandidate(
        userId = getObject("subscription_user_id", UUID::class.java),
        productId = getString("external_product_id"),
        purchaseToken = getString("external_subscription_id"),
        tier = getString("tier"),
        status = getString("status"),
        currentPeriodStart = getTimestamp("current_period_start").toInstant(),
        currentPeriodEnd = getTimestamp("current_period_end").toInstant()
    )

internal fun ResultSet.toStoredEntitlementRecord(): StoredEntitlementRecord =
    StoredEntitlementRecord(
        entitlementId = getObject("entitlement_id", UUID::class.java),
        userId = getObject("entitlement_user_id", UUID::class.java),
        tier = getString("tier"),
        source = getString("source"),
        sourceId = getObject("source_id", UUID::class.java),
        startsAt = getTimestamp("starts_at").toInstant(),
        endsAt = getTimestamp("ends_at")?.toInstant(),
        status = getString("status")
    )

internal fun ResultSet.toActiveEntitlementRecord(): ActiveEntitlementRecord =
    ActiveEntitlementRecord(
        tier = getString("tier"),
        source = getString("source"),
        sourceSubscriptionId = getObject("source_subscription_id", UUID::class.java),
        sourceUserId = getObject("source_user_id", UUID::class.java),
        linkedPartnerUserId = getObject("linked_partner_user_id", UUID::class.java),
        startsAt = getTimestamp("starts_at").toInstant(),
        endsAt = getTimestamp("ends_at")?.toInstant()
    )

internal fun ResultSet.toBoostActivationRecord(): BoostActivationRecord =
    BoostActivationRecord(
        activationId = getObject("boost_activation_id", UUID::class.java),
        userId = getObject("boost_user_id", UUID::class.java),
        boostType = getString("boost_type"),
        source = getString("source"),
        startsAt = getTimestamp("starts_at").toInstant(),
        endsAt = getTimestamp("ends_at").toInstant(),
        status = getString("status")
    )

internal fun ResultSet.toPremiumInventoryBalanceRecord(): PremiumInventoryBalanceRecord =
    PremiumInventoryBalanceRecord(
        inventoryType = getString("inventory_type"),
        quantity = getInt("quantity"),
        nextExpiresAt = getTimestamp("next_expires_at")?.toInstant()
    )
