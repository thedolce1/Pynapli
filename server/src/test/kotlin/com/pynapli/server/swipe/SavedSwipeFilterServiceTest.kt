package com.pynapli.server.swipe

import com.pynapli.server.grid.GridViewerTrustRepository
import com.pynapli.server.subscriptions.LinkedPartnerSubscriptionRecord
import com.pynapli.server.subscriptions.StoredEntitlementRecord
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionRepository
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SavedSwipeFilterServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun createRejectsFreeTierViewer() = runBlocking {
        // FILTERS_SPEC L463-477: presets sit behind Pynapli+. Free-tier callers must
        // see ENTITLEMENT_REQUIRED rather than a silent succeed.
        val viewerId = UUID.randomUUID()
        val service = SavedSwipeFilterService(
            repository = StaticSavedSwipeFilterRepository(),
            subscriptionService = freeTierSubscriptionService()
        )

        val error = assertFailsWith<SavedSwipeFilterException> {
            service.create(
                viewerId,
                CreateSavedSwipeFilterRequestDto(
                    name = "Local",
                    payload = SwipeDeckRequestDto(radiusMeters = 5_000)
                )
            )
        }
        assertEquals("ENTITLEMENT_REQUIRED", error.code)
    }

    @Test
    fun createRejectsUnknownDealbreakerKey() = runBlocking {
        // Mirrors SwipeService.queryDeck validation — unknown dealbreaker keys must
        // never persist because they'd later confuse the relaxation policy.
        val viewerId = UUID.randomUUID()
        val service = SavedSwipeFilterService(
            repository = StaticSavedSwipeFilterRepository(),
            subscriptionService = paidSubscriptionService(viewerId)
        )

        val error = assertFailsWith<SavedSwipeFilterException> {
            service.create(
                viewerId,
                CreateSavedSwipeFilterRequestDto(
                    name = "Bad",
                    payload = SwipeDeckRequestDto(dealbreakers = setOf("not_a_real_key"))
                )
            )
        }
        assertEquals("INVALID_SAVED_FILTER_PAYLOAD", error.code)
    }

    @Test
    fun createRejectsBlankAndOverLongNames() = runBlocking {
        val viewerId = UUID.randomUUID()
        val service = SavedSwipeFilterService(
            repository = StaticSavedSwipeFilterRepository(),
            subscriptionService = paidSubscriptionService(viewerId)
        )

        assertEquals(
            "INVALID_SAVED_FILTER_NAME",
            assertFailsWith<SavedSwipeFilterException> {
                service.create(viewerId, CreateSavedSwipeFilterRequestDto(name = "   ", payload = SwipeDeckRequestDto()))
            }.code
        )
        assertEquals(
            "INVALID_SAVED_FILTER_NAME",
            assertFailsWith<SavedSwipeFilterException> {
                service.create(
                    viewerId,
                    CreateSavedSwipeFilterRequestDto(name = "x".repeat(61), payload = SwipeDeckRequestDto())
                )
            }.code
        )
    }

    @Test
    fun createRejectsLifestylePresetForUnverifiedPaidViewer() = runBlocking {
        // Mirrors SavedGridFilterService L93 — lifestyle filters are age-gated.
        val viewerId = UUID.randomUUID()
        val service = SavedSwipeFilterService(
            repository = StaticSavedSwipeFilterRepository(),
            subscriptionService = paidSubscriptionService(viewerId),
            viewerTrustRepository = StaticGridViewerTrustRepository(ageVerifiedUserIds = emptySet())
        )

        val error = assertFailsWith<SavedSwipeFilterException> {
            service.create(
                viewerId,
                CreateSavedSwipeFilterRequestDto(
                    name = "Lifestyle",
                    payload = SwipeDeckRequestDto(lifestyleTags = listOf("soft_swap"))
                )
            )
        }
        assertEquals("AGE_VERIFICATION_REQUIRED", error.code)
    }

    @Test
    fun createDropsCursorFromPersistedPayload() = runBlocking {
        // The preset must not carry per-request cursor state — that would tie the
        // preset to a specific deck session and break on apply.
        val viewerId = UUID.randomUUID()
        val capture = CapturingSavedSwipeFilterRepository()
        val service = SavedSwipeFilterService(
            repository = capture,
            subscriptionService = paidSubscriptionService(viewerId),
            json = json
        )

        service.create(
            viewerId,
            CreateSavedSwipeFilterRequestDto(
                name = "Travel",
                payload = SwipeDeckRequestDto(
                    radiusMeters = 50_000,
                    cursor = "deck-session-xyz",
                    dealbreakers = setOf(SwipeDealbreakerKey.DISTANCE)
                )
            )
        )

        val persisted = capture.lastCreatedPayload
            ?: error("expected payload to be captured")
        val decoded = json.decodeFromString(SwipeDeckRequestDto.serializer(), persisted)
        assertNull(decoded.cursor)
        assertEquals(50_000, decoded.radiusMeters)
        assertEquals(setOf(SwipeDealbreakerKey.DISTANCE), decoded.dealbreakers)
    }

    @Test
    fun createRejectsWhenPresetLimitReached() = runBlocking {
        val viewerId = UUID.randomUUID()
        val service = SavedSwipeFilterService(
            repository = StaticSavedSwipeFilterRepository(existingCount = SavedSwipeFilterService.MAX_PRESETS_PER_USER),
            subscriptionService = paidSubscriptionService(viewerId)
        )

        val error = assertFailsWith<SavedSwipeFilterException> {
            service.create(
                viewerId,
                CreateSavedSwipeFilterRequestDto(name = "Twelfth", payload = SwipeDeckRequestDto())
            )
        }
        assertEquals("SAVED_FILTER_LIMIT_REACHED", error.code)
    }

    @Test
    fun createMapsDuplicateNameRepositoryException() = runBlocking {
        val viewerId = UUID.randomUUID()
        val service = SavedSwipeFilterService(
            repository = ThrowingDuplicateSavedSwipeFilterRepository(),
            subscriptionService = paidSubscriptionService(viewerId)
        )

        val error = assertFailsWith<SavedSwipeFilterException> {
            service.create(viewerId, CreateSavedSwipeFilterRequestDto(name = "Duplicate", payload = SwipeDeckRequestDto()))
        }
        assertEquals("SAVED_FILTER_NAME_TAKEN", error.code)
    }
}

private open class StaticSavedSwipeFilterRepository(
    private val existingCount: Int = 0
) : SavedSwipeFilterRepository {
    override suspend fun list(userId: UUID): List<SavedSwipeFilterRecord> = emptyList()

    override suspend fun create(
        id: UUID,
        userId: UUID,
        name: String,
        payloadJson: String
    ): SavedSwipeFilterRecord = SavedSwipeFilterRecord(
        id = id,
        userId = userId,
        name = name,
        payloadJson = payloadJson,
        createdAt = Instant.parse("2026-05-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-01T00:00:00Z")
    )

    override suspend fun delete(userId: UUID, id: UUID): Boolean = false

    override suspend fun countForUser(userId: UUID): Int = existingCount
}

private class CapturingSavedSwipeFilterRepository : StaticSavedSwipeFilterRepository() {
    var lastCreatedPayload: String? = null
        private set

    override suspend fun create(
        id: UUID,
        userId: UUID,
        name: String,
        payloadJson: String
    ): SavedSwipeFilterRecord {
        lastCreatedPayload = payloadJson
        return super.create(id, userId, name, payloadJson)
    }
}

private class ThrowingDuplicateSavedSwipeFilterRepository : SavedSwipeFilterRepository {
    override suspend fun list(userId: UUID): List<SavedSwipeFilterRecord> = emptyList()
    override suspend fun create(
        id: UUID,
        userId: UUID,
        name: String,
        payloadJson: String
    ): SavedSwipeFilterRecord = throw SavedSwipeFilterDuplicateNameException()
    override suspend fun delete(userId: UUID, id: UUID): Boolean = false
    override suspend fun countForUser(userId: UUID): Int = 0
}

private class StaticGridViewerTrustRepository(
    private val ageVerifiedUserIds: Set<UUID>
) : GridViewerTrustRepository {
    override suspend fun isAgeVerified(userId: UUID): Boolean = userId in ageVerifiedUserIds
}

private fun paidSubscriptionService(userId: UUID): SubscriptionService =
    SubscriptionService(
        StubSubscriptionRepository(
            own = mapOf(
                userId to SubscriptionRecord(
                    subscriptionId = UUID.randomUUID(),
                    userId = userId,
                    tier = SubscriptionTier.SOCIAL,
                    status = "active",
                    currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                    currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z")
                )
            )
        )
    )

private fun freeTierSubscriptionService(): SubscriptionService =
    SubscriptionService(StubSubscriptionRepository())

private class StubSubscriptionRepository(
    private val own: Map<UUID, SubscriptionRecord> = emptyMap()
) : SubscriptionRepository {
    override suspend fun findBestActiveOwnSubscription(userId: UUID, now: Instant): SubscriptionRecord? = own[userId]

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant
    ): LinkedPartnerSubscriptionRecord? = null

    override suspend fun findBestActiveStoredEntitlement(userId: UUID, now: Instant): StoredEntitlementRecord? = null

    override suspend fun consumeIntroNoteInventory(userId: UUID, now: Instant): Boolean = false
}
