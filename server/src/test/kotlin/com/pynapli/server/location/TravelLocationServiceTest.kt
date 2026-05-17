package com.pynapli.server.location

import com.pynapli.server.subscriptions.LinkedPartnerSubscriptionRecord
import com.pynapli.server.subscriptions.StoredEntitlementRecord
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionRepository
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.TravelLocationStatus
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TravelLocationServiceTest {
    private val now = Instant.parse("2026-05-17T14:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun freeTierGetsEntitlementRequiredOnSet() = runBlocking {
        // SWIPE_IMPLEMENTATION_SPEC L339-354 + MONETIZATION_SPEC L152: free tier
        // has no travel mode at all. The error code lets the client render the
        // paywall hint without a separate tier-lookup call.
        val viewer = UUID.randomUUID()
        val service = TravelLocationService(
            repository = InMemoryTravelLocationRepository(),
            subscriptionService = SubscriptionService(StubSubscriptionRepository(), clock = clock),
            clock = clock
        )

        val error = assertFailsWith<TravelLocationException> {
            service.set(viewer, validRequest("Tokyo", 35.6762, 139.6503))
        }
        assertEquals("ENTITLEMENT_REQUIRED", error.code)
    }

    @Test
    fun socialTierGetsEntitlementRequiredInThisSlice() = runBlocking {
        // MONETIZATION_SPEC L186 promises Social tier a "basic travel preview"
        // but this MVP slice gates everything behind Select. The preview path
        // lands in a later slice that only has to widen requireSelectTier().
        val viewer = UUID.randomUUID()
        val service = TravelLocationService(
            repository = InMemoryTravelLocationRepository(),
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SOCIAL),
            clock = clock
        )

        val error = assertFailsWith<TravelLocationException> {
            service.set(viewer, validRequest("Tokyo", 35.6762, 139.6503))
        }
        assertEquals("ENTITLEMENT_REQUIRED", error.code)
    }

    @Test
    fun selectTierCanSetActiveTravelLocation() = runBlocking {
        val viewer = UUID.randomUUID()
        val repository = InMemoryTravelLocationRepository()
        val service = TravelLocationService(
            repository = repository,
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT),
            clock = clock
        )

        val response = service.set(viewer, validRequest("Tokyo", 35.6762, 139.6503))

        assertEquals("Tokyo", response.city)
        assertEquals(35.6762, response.latitude)
        assertEquals(139.6503, response.longitude)
        assertEquals(TravelLocationStatus.ACTIVE, response.status)
        assertNull(response.activeUntil)
        // Read-back returns the same row.
        assertEquals(response.city, service.getCurrent(viewer)?.city)
    }

    @Test
    fun settingNewLocationSupersedesPriorActiveRow() = runBlocking {
        // Spec L345: "one virtual city/location at a time". A second `set` call
        // must clear the prior row (the partial unique index enforces this at
        // the DB level; this test verifies the service path honors it).
        val viewer = UUID.randomUUID()
        val repository = InMemoryTravelLocationRepository()
        val service = TravelLocationService(
            repository = repository,
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT),
            clock = clock
        )

        service.set(viewer, validRequest("Tokyo", 35.6762, 139.6503))
        val nyc = service.set(viewer, validRequest("New York", 40.7128, -74.0060))

        assertEquals("New York", nyc.city)
        assertEquals(1, repository.activeRowCount(viewer))
        assertEquals(2, repository.totalRowCount(viewer))
    }

    @Test
    fun clearMarksActiveRowAsCleared() = runBlocking {
        val viewer = UUID.randomUUID()
        val repository = InMemoryTravelLocationRepository()
        val service = TravelLocationService(
            repository = repository,
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT),
            clock = clock
        )
        service.set(viewer, validRequest("Tokyo", 35.6762, 139.6503))

        val clearedFirst = service.clear(viewer)
        val clearedSecond = service.clear(viewer)

        assertTrue(clearedFirst)
        assertFalse(clearedSecond, "Second clear should be a no-op since nothing is active")
        assertNull(service.getCurrent(viewer))
    }

    @Test
    fun getCurrentReturnsNullForFreeTier() = runBlocking {
        // Free-tier read is open (returns null without erroring) so the client
        // can render the entry point and a paywall hint without a round-trip
        // through the error pipeline.
        val viewer = UUID.randomUUID()
        val service = TravelLocationService(
            repository = InMemoryTravelLocationRepository(),
            subscriptionService = SubscriptionService(StubSubscriptionRepository(), clock = clock),
            clock = clock
        )

        assertNull(service.getCurrent(viewer))
    }

    @Test
    fun invalidLatitudeRejected() = runBlocking {
        val viewer = UUID.randomUUID()
        val service = TravelLocationService(
            repository = InMemoryTravelLocationRepository(),
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT),
            clock = clock
        )

        val error = assertFailsWith<TravelLocationException> {
            service.set(viewer, validRequest("Somewhere", latitude = 200.0, longitude = 0.0))
        }
        assertEquals("INVALID_TRAVEL_COORDINATES", error.code)
    }

    @Test
    fun blankCityRejected() = runBlocking {
        val viewer = UUID.randomUUID()
        val service = TravelLocationService(
            repository = InMemoryTravelLocationRepository(),
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT),
            clock = clock
        )

        val error = assertFailsWith<TravelLocationException> {
            service.set(viewer, validRequest(city = "   ", latitude = 0.0, longitude = 0.0))
        }
        assertEquals("INVALID_TRAVEL_CITY", error.code)
    }

    @Test
    fun regionGatedDestinationRequiresAgeVerifiedViewer() = runBlocking {
        // SWIPE_IMPLEMENTATION_SPEC L349: a flagged region returns
        // AGE_VERIFICATION_REQUIRED when the viewer hasn't completed age
        // verification. Reuses the existing error code so the client routes
        // through the same verification UI as the lifestyle filter gate.
        val viewer = UUID.randomUUID()
        val service = TravelLocationService(
            repository = InMemoryTravelLocationRepository(),
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT),
            regionPolicy = StaticTravelRegionPolicy(
                ageVerifiedCountries = setOf("Sensitiveland")
            ),
            viewerTrustRepository = StaticViewerTrustRepository(ageVerifiedUserIds = emptySet()),
            clock = clock
        )

        val error = assertFailsWith<TravelLocationException> {
            service.set(
                viewer,
                SetTravelLocationRequestDto(
                    latitude = 0.0,
                    longitude = 0.0,
                    city = "Capital",
                    region = null,
                    country = "Sensitiveland"
                )
            )
        }
        assertEquals("AGE_VERIFICATION_REQUIRED", error.code)
    }

    @Test
    fun regionGatedDestinationAcceptsAgeVerifiedViewer() = runBlocking {
        // Same flagged region, but the viewer has age_verified_at set; the
        // gate passes and the row is persisted.
        val viewer = UUID.randomUUID()
        val service = TravelLocationService(
            repository = InMemoryTravelLocationRepository(),
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT),
            regionPolicy = StaticTravelRegionPolicy(
                ageVerifiedCountries = setOf("Sensitiveland")
            ),
            viewerTrustRepository = StaticViewerTrustRepository(ageVerifiedUserIds = setOf(viewer)),
            clock = clock
        )

        val response = service.set(
            viewer,
            SetTravelLocationRequestDto(
                latitude = 0.0,
                longitude = 0.0,
                city = "Capital",
                region = null,
                country = "Sensitiveland"
            )
        )

        assertEquals("Capital", response.city)
        assertEquals("Sensitiveland", response.country)
    }

    @Test
    fun unflaggedRegionDoesNotRequireVerification() = runBlocking {
        // Default empty policy + an unflagged country still works for an
        // unverified viewer — the gate is opt-in per region.
        val viewer = UUID.randomUUID()
        val service = TravelLocationService(
            repository = InMemoryTravelLocationRepository(),
            subscriptionService = paidSubscriptionService(viewer, SubscriptionTier.SELECT),
            regionPolicy = StaticTravelRegionPolicy(
                ageVerifiedCountries = setOf("Sensitiveland")
            ),
            viewerTrustRepository = StaticViewerTrustRepository(ageVerifiedUserIds = emptySet()),
            clock = clock
        )

        val response = service.set(
            viewer,
            SetTravelLocationRequestDto(
                latitude = 35.6762,
                longitude = 139.6503,
                city = "Tokyo",
                region = null,
                country = "Japan"
            )
        )

        assertEquals("Tokyo", response.city)
    }

    private fun validRequest(city: String, latitude: Double, longitude: Double): SetTravelLocationRequestDto =
        SetTravelLocationRequestDto(
            latitude = latitude,
            longitude = longitude,
            city = city,
            region = null,
            country = null
        )

    private fun paidSubscriptionService(userId: UUID, tier: String): SubscriptionService =
        SubscriptionService(
            StubSubscriptionRepository(
                own = mapOf(
                    userId to SubscriptionRecord(
                        subscriptionId = UUID.randomUUID(),
                        userId = userId,
                        tier = tier,
                        status = "active",
                        currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                        currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z")
                    )
                )
            ),
            clock = clock
        )
}

private class InMemoryTravelLocationRepository : TravelLocationRepository {
    private val rows = mutableListOf<TravelLocationRecord>()

    override suspend fun findActive(userId: UUID): TravelLocationRecord? =
        rows.lastOrNull {
            it.userId == userId && it.status == "active" && it.activeUntil == null
        }

    override suspend fun setActive(
        userId: UUID,
        request: TravelLocationUpsert,
        now: Instant
    ): TravelLocationRecord {
        // Mirror production: any existing active row gets cleared first.
        rows.indices.forEach { idx ->
            val current = rows[idx]
            if (current.userId == userId && current.status == "active" && current.activeUntil == null) {
                rows[idx] = current.copy(status = "cleared", activeUntil = now)
            }
        }
        val record = TravelLocationRecord(
            id = UUID.randomUUID(),
            userId = userId,
            city = request.city,
            region = request.region,
            country = request.country,
            latitude = request.latitude,
            longitude = request.longitude,
            activeFrom = now,
            activeUntil = null,
            status = "active"
        )
        rows += record
        return record
    }

    override suspend fun clearActive(userId: UUID, now: Instant): Boolean {
        var cleared = false
        rows.indices.forEach { idx ->
            val current = rows[idx]
            if (current.userId == userId && current.status == "active" && current.activeUntil == null) {
                rows[idx] = current.copy(status = "cleared", activeUntil = now)
                cleared = true
            }
        }
        return cleared
    }

    fun activeRowCount(userId: UUID): Int =
        rows.count { it.userId == userId && it.status == "active" && it.activeUntil == null }

    fun totalRowCount(userId: UUID): Int =
        rows.count { it.userId == userId }
}

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

private class StaticTravelRegionPolicy(
    private val ageVerifiedCountries: Set<String>
) : TravelRegionPolicy {
    override fun requirementFor(destination: TravelRegionRef): TravelRegionRequirement =
        if (destination.country != null && destination.country.equals(any = ageVerifiedCountries)) {
            TravelRegionRequirement.AgeVerified
        } else {
            TravelRegionRequirement.None
        }

    private fun String.equals(any: Set<String>): Boolean =
        any.any { it.equals(this, ignoreCase = true) }
}

private class StaticViewerTrustRepository(
    private val ageVerifiedUserIds: Set<UUID>
) : com.pynapli.server.grid.GridViewerTrustRepository {
    override suspend fun isAgeVerified(userId: UUID): Boolean = userId in ageVerifiedUserIds
}
