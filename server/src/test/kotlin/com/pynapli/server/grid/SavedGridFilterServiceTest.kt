package com.pynapli.server.grid

import com.pynapli.server.subscriptions.LinkedPartnerSubscriptionRecord
import com.pynapli.server.subscriptions.StoredEntitlementRecord
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionRepository
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SavedGridFilterServiceTest {
    @Test
    fun createRejectsLifestylePresetForUnverifiedPaidViewer() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val service =
                SavedGridFilterService(
                    repository = StaticSavedGridFilterRepository(),
                    subscriptionService = paidSubscriptionService(viewerId),
                    viewerTrustRepository = StaticSavedGridViewerTrustRepository(ageVerifiedUserIds = emptySet()),
                )

            val error =
                assertFailsWith<SavedGridFilterException> {
                    service.create(
                        viewerId,
                        CreateSavedGridFilterRequestDto(
                            name = "Lifestyle",
                            payload = GridQueryRequestDto(lifestyleTags = listOf("soft_swap")),
                        ),
                    )
                }

            assertEquals("AGE_VERIFICATION_REQUIRED", error.code)
        }
}

private class StaticSavedGridFilterRepository : SavedGridFilterRepository {
    override suspend fun list(userId: UUID): List<SavedGridFilterRecord> = emptyList()

    override suspend fun create(
        id: UUID,
        userId: UUID,
        name: String,
        payloadJson: String,
    ): SavedGridFilterRecord =
        SavedGridFilterRecord(
            id = id,
            userId = userId,
            name = name,
            payloadJson = payloadJson,
            createdAt = Instant.parse("2026-05-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T00:00:00Z"),
        )

    override suspend fun delete(
        userId: UUID,
        id: UUID,
    ): Boolean = false

    override suspend fun countForUser(userId: UUID): Int = 0
}

private class StaticSavedGridViewerTrustRepository(
    private val ageVerifiedUserIds: Set<UUID>,
) : GridViewerTrustRepository {
    override suspend fun isAgeVerified(userId: UUID): Boolean = userId in ageVerifiedUserIds
}

private fun paidSubscriptionService(userId: UUID): SubscriptionService =
    SubscriptionService(
        SavedFilterSubscriptionRepository(
            own =
                mapOf(
                    userId to
                        SubscriptionRecord(
                            subscriptionId = UUID.randomUUID(),
                            userId = userId,
                            tier = SubscriptionTier.SOCIAL,
                            status = "active",
                            currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                            currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z"),
                        ),
                ),
        ),
    )

private class SavedFilterSubscriptionRepository(
    private val own: Map<UUID, SubscriptionRecord> = emptyMap(),
) : SubscriptionRepository {
    override suspend fun findBestActiveOwnSubscription(
        userId: UUID,
        now: Instant,
    ): SubscriptionRecord? = own[userId]

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant,
    ): LinkedPartnerSubscriptionRecord? = null

    override suspend fun findBestActiveStoredEntitlement(
        userId: UUID,
        now: Instant,
    ): StoredEntitlementRecord? = null

    override suspend fun consumeIntroNoteInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = false
}
