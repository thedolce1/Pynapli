package com.pynapli.server.subscriptions

import com.pynapli.server.moderation.insertAdminAuditLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcSubscriptionEntitlementActions(
    private val dataSource: DataSource,
) {
    suspend fun findBestActiveEntitlement(
        userId: UUID,
        now: Instant,
    ): ActiveEntitlementRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.loadBestActiveEntitlement(userId, now) }
        }

    suspend fun findBestActiveOwnSubscription(
        userId: UUID,
        now: Instant,
    ): SubscriptionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.loadBestActiveOwnSubscription(userId, now) }
        }

    suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant,
    ): LinkedPartnerSubscriptionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.loadBestActiveLinkedPartnerSubscription(userId, now) }
        }

    suspend fun findBestActiveStoredEntitlement(
        userId: UUID,
        now: Instant,
    ): StoredEntitlementRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.loadBestActiveStoredEntitlement(userId, now) }
        }

    suspend fun listStoredEntitlementsForUser(
        userId: UUID,
        limit: Int,
    ): List<StoredEntitlementRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.listStoredEntitlementRecordsForUser(userId, limit) }
        }

    suspend fun grantStoredEntitlement(
        userId: UUID,
        tier: String,
        source: String,
        startsAt: Instant,
        endsAt: Instant?,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.runSubscriptionTransaction {
                    val record =
                        grantStoredEntitlementRecord(
                            userId = userId,
                            tier = tier,
                            source = source,
                            startsAt = startsAt,
                            endsAt = endsAt,
                        ) ?: return@runSubscriptionTransaction null
                    insertAdminAuditLog(
                        actorId = actorId,
                        action = "subscription_entitlement.grant",
                        targetType = "entitlement",
                        targetId = record.entitlementId,
                        reason = reason,
                        metadataJson =
                            buildJsonObject {
                                put("userId", userId.toString())
                                put("tier", tier)
                                put("source", source)
                                put("startsAt", startsAt.toString())
                                put("endsAt", endsAt?.toString())
                            }.toString(),
                    )
                    record
                }
            }
        }

    suspend fun revokeStoredEntitlement(
        entitlementId: UUID,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.runSubscriptionTransaction {
                    val record = revokeStoredEntitlementRecord(entitlementId) ?: return@runSubscriptionTransaction null
                    insertAdminAuditLog(
                        actorId = actorId,
                        action = "subscription_entitlement.revoke",
                        targetType = "entitlement",
                        targetId = record.entitlementId,
                        reason = reason,
                        metadataJson =
                            buildJsonObject {
                                put("userId", record.userId.toString())
                                put("tier", record.tier)
                                put("source", record.source)
                            }.toString(),
                    )
                    record
                }
            }
        }
}
