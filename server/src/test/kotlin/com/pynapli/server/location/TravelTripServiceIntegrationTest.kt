package com.pynapli.server.location

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.PlanTravelTripRequestDto
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TravelTripServiceIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val auth = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))
    private val travelRepository = JdbcTravelLocationRepository(dataSource)
    private val travel = TravelLocationService(travelRepository)

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun user(email: String) = UUID.fromString(auth.signupEmail(email, "trip-password-1", "1990-01-01", true, true, true, null, "android", "1.0").user.userId)

    private fun trip(
        city: String,
        startsOn: LocalDate,
        endsOn: LocalDate,
    ) = PlanTravelTripRequestDto(
        latitude = 36.17,
        longitude = -115.14,
        city = city,
        region = "Nevada",
        country = "US",
        startsOn = startsOn.toString(),
        endsOn = endsOn.toString(),
        timezone = "UTC",
    )

    @Test
    fun aTripStartingTodaySwitchesTravelOnAndItEndsAfterTheLastDay() =
        runBlocking<Unit> {
            val me = user("traveller@example.com")
            val today = LocalDate.now(ZoneOffset.UTC)
            val trips = TravelTripService(dataSource, travel)

            val planned = trips.plan(me, trip("Las Vegas", today, today.plusDays(2)))

            assertEquals("active", planned.status)
            assertEquals("Las Vegas", travel.getCurrent(me)?.city)

            // Three days on, the trip is over and Travel mode is off again.
            val later = TravelTripService(dataSource, travel, Clock.offset(Clock.systemUTC(), Duration.ofDays(3)))
            assertEquals(1, later.runDue())
            assertNull(travel.getCurrent(me))
            assertEquals(emptyList(), trips.list(me).trips)
        }

    @Test
    fun aFutureTripWaitsAndOverlappingOrBadDatesAreRefused() =
        runBlocking<Unit> {
            val me = user("planner@example.com")
            val today = LocalDate.now(ZoneOffset.UTC)
            val trips = TravelTripService(dataSource, travel)

            val planned = trips.plan(me, trip("Montreal", today.plusDays(10), today.plusDays(12)))
            assertEquals("planned", planned.status)
            assertNull(travel.getCurrent(me), "nothing switches on before the trip")

            assertEquals("TRIP_OVERLAPS", assertFailsWith<TravelLocationException> { trips.plan(me, trip("Quebec", today.plusDays(12), today.plusDays(14))) }.code)
            assertEquals("INVALID_TRIP_DATES", assertFailsWith<TravelLocationException> { trips.plan(me, trip("Ottawa", today.minusDays(1), today)) }.code)
            assertEquals("INVALID_TRIP_DATES", assertFailsWith<TravelLocationException> { trips.plan(me, trip("Ottawa", today.plusDays(20), today.plusDays(60))) }.code)

            trips.cancel(me, UUID.fromString(planned.tripId))
            assertEquals(emptyList(), trips.list(me).trips)
        }

    @Test
    fun endingATripLeavesAManualSwitchMadeDuringItAlone() =
        runBlocking<Unit> {
            val me = user("switcher@example.com")
            val today = LocalDate.now(ZoneOffset.UTC)
            val trips = TravelTripService(dataSource, travel)
            trips.plan(me, trip("Las Vegas", today, today))

            // Mid-trip they switch Travel mode somewhere else by hand.
            travel.set(me, SetTravelLocationRequestDto(latitude = 34.05, longitude = -118.24, city = "Los Angeles"))

            TravelTripService(dataSource, travel, Clock.offset(Clock.systemUTC(), Duration.ofDays(2))).runDue()
            assertEquals("Los Angeles", travel.getCurrent(me)?.city)
        }
}
