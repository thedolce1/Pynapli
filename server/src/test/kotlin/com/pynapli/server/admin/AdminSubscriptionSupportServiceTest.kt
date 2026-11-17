package com.pynapli.server.admin

import com.pynapli.server.moderation.ModerationException
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AdminSubscriptionSupportServiceTest {
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000801")

    @Test
    fun viewComposesEntitlementSubscriptionsAndBenefitsThenAudits() =
        runBlocking {
            val repository = FakeSubscriptionSupportRepository(storedFacts = facts())
            var lookedUp: UUID? = null
            val service =
                AdminSubscriptionSupportService(
                    repository = repository,
                    entitlementLookup = { id ->
                        lookedUp = id
                        entitlement()
                    },
                )

            val dto = service.view(actorId = "ops-1", userId = userId, reason = "billing question")

            assertEquals(userId.toString(), dto.userId)
            assertEquals("select", dto.entitlement.tier)
            assertEquals(1, dto.subscriptions.size)
            assertEquals("play_store", dto.subscriptions.first().platform)
            assertEquals(1, dto.benefitsGranted.size)
            assertEquals(1, dto.benefitsReceived.size)
            assertEquals(userId, lookedUp)
            assertEquals(Triple("ops-1", userId, "billing question"), repository.audited)
        }

    @Test
    fun viewRejectsMissingUserWithoutAuditingOrEntitlementLookup() =
        runBlocking {
            val repository = FakeSubscriptionSupportRepository(storedFacts = null)
            var lookedUp = false
            val service =
                AdminSubscriptionSupportService(
                    repository = repository,
                    entitlementLookup = {
                        lookedUp = true
                        entitlement()
                    },
                )

            assertFailsWith<ModerationException> {
                service.view(actorId = "ops-1", userId = userId, reason = "billing question")
            }
            assertFalse(lookedUp)
            assertEquals(null, repository.audited)
        }

    private fun facts(): AdminSubscriptionSupportFacts =
        AdminSubscriptionSupportFacts(
            subscriptions =
                listOf(
                    AdminSubscriptionRecordFact(
                        platform = "play_store",
                        tier = "select",
                        status = "active",
                        externalProductId = "pynapli_select_monthly",
                        externalStatus = "SUBSCRIPTION_STATE_ACTIVE",
                        acknowledgementState = "ACKNOWLEDGED",
                        currentPeriodStart = Instant.parse("2026-06-01T00:00:00Z"),
                        currentPeriodEnd = Instant.parse("2026-07-01T00:00:00Z"),
                        lastVerifiedAt = Instant.parse("2026-06-15T00:00:00Z"),
                    ),
                ),
            benefitsGranted =
                listOf(
                    AdminLinkedBenefitFact(
                        otherUserId = UUID.fromString("00000000-0000-0000-0000-000000000802"),
                        tier = "select",
                        status = "active",
                        startsAt = Instant.parse("2026-06-01T00:00:00Z"),
                        endsAt = null,
                    ),
                ),
            benefitsReceived =
                listOf(
                    AdminLinkedBenefitFact(
                        otherUserId = UUID.fromString("00000000-0000-0000-0000-000000000803"),
                        tier = "social",
                        status = "ended",
                        startsAt = Instant.parse("2026-05-01T00:00:00Z"),
                        endsAt = Instant.parse("2026-05-20T00:00:00Z"),
                    ),
                ),
        )

    private fun entitlement(): SubscriptionEntitlementDto =
        SubscriptionEntitlementDto(
            tier = "select",
            source = "own_subscription",
            displayName = "Select",
            linkedPartnerIncluded = true,
        )
}

private class FakeSubscriptionSupportRepository(
    private val storedFacts: AdminSubscriptionSupportFacts?,
) : AdminSubscriptionSupportRepository {
    var audited: Triple<String, UUID, String>? = null

    override suspend fun facts(userId: UUID): AdminSubscriptionSupportFacts? = storedFacts

    override suspend fun recordReviewAudit(
        actorId: String,
        userId: UUID,
        reason: String,
    ) {
        audited = Triple(actorId, userId, reason)
    }
}
