package com.pynapli.server.location

import com.pynapli.server.safety.RateLimitCounter
import com.pynapli.server.safety.RateLimitRepository
import com.pynapli.server.safety.RateLimitService
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TravelPlaceSearchServiceTest {
    private val viewer = UUID.fromString("00000000-0000-0000-0000-000000000601")

    private val vegas =
        TravelPlaceRecord(
            kind = "city",
            countryCode = "US",
            postalCode = null,
            name = "Las Vegas",
            region = "Nevada",
            latitude = 36.17497,
            longitude = -115.13722,
        )
    private val vegasZip =
        TravelPlaceRecord(
            kind = "postal",
            countryCode = "US",
            postalCode = "89101",
            name = "Las Vegas",
            region = "NV",
            latitude = 36.1699,
            longitude = -115.1398,
        )

    @Test
    fun cityQueryPrefixSearchesCitiesAndBuildsDisplayName() =
        runBlocking {
            val repository = RecordingTravelPlaceRepository(cities = listOf(vegas))
            val service = TravelPlaceSearchService(repository)

            val response = service.search(viewer, "las v")

            assertEquals("las v", repository.cityQueries.single())
            assertEquals(emptyList(), repository.postalQueries)
            assertEquals("Las Vegas, Nevada, US", response.items.single().displayName)
            assertEquals("Las Vegas", response.items.single().city)
        }

    @Test
    fun usZipQuerySearchesPostalRows() =
        runBlocking {
            val repository = RecordingTravelPlaceRepository(postal = listOf(vegasZip))
            val service = TravelPlaceSearchService(repository)

            val response = service.search(viewer, "891")

            assertEquals("891", repository.postalQueries.single())
            assertEquals(emptyList(), repository.cityQueries)
            assertEquals("89101 · Las Vegas, NV, US", response.items.single().displayName)
        }

    @Test
    fun canadianPostalQueryMatchesAtForwardSortationAreaLevel() =
        runBlocking {
            val repository = RecordingTravelPlaceRepository()
            val service = TravelPlaceSearchService(repository)

            service.search(viewer, "M5V 2T6")
            service.search(viewer, "m5v")

            assertEquals(listOf("M5V", "m5v"), repository.postalQueries)
            assertEquals(emptyList(), repository.cityQueries)
        }

    @Test
    fun subMinimumQueryReturnsEmptyWithoutSearching() =
        runBlocking {
            val repository = RecordingTravelPlaceRepository()
            val service = TravelPlaceSearchService(repository)

            val response = service.search(viewer, " L ")

            assertTrue(response.items.isEmpty())
            assertEquals(emptyList(), repository.cityQueries)
            assertEquals(emptyList(), repository.postalQueries)
        }

    @Test
    fun overlongQueryIsRejected() =
        runBlocking {
            val service = TravelPlaceSearchService(RecordingTravelPlaceRepository())

            val error =
                assertFailsWith<TravelLocationException> {
                    service.search(viewer, "x".repeat(TravelPlaceSearchService.MAX_QUERY_LENGTH + 1))
                }

            assertEquals("INVALID_TRAVEL_SEARCH_QUERY", error.code)
        }

    @Test
    fun rateLimitedViewerIsRejectedBeforeSearching() =
        runBlocking {
            val repository = RecordingTravelPlaceRepository()
            val service =
                TravelPlaceSearchService(
                    repository = repository,
                    rateLimitService = denyingRateLimitService(),
                )

            val error = assertFailsWith<TravelLocationException> { service.search(viewer, "las vegas") }

            assertEquals("RATE_LIMITED", error.code)
            assertEquals(emptyList(), repository.cityQueries)
        }
}

private class RecordingTravelPlaceRepository(
    private val cities: List<TravelPlaceRecord> = emptyList(),
    private val postal: List<TravelPlaceRecord> = emptyList(),
) : TravelPlaceRepository {
    val cityQueries = mutableListOf<String>()
    val postalQueries = mutableListOf<String>()

    override suspend fun searchCities(
        namePrefix: String,
        limit: Int,
    ): List<TravelPlaceRecord> {
        cityQueries += namePrefix
        return cities.take(limit)
    }

    override suspend fun searchPostal(
        postalPrefix: String,
        limit: Int,
    ): List<TravelPlaceRecord> {
        postalQueries += postalPrefix
        return postal.take(limit)
    }

    override suspend fun loadedDatasetVersion(): String? = null

    override suspend fun replaceDataset(
        version: String,
        rows: Sequence<TravelPlaceRow>,
    ): Int = 0
}

private fun denyingRateLimitService(): RateLimitService =
    RateLimitService(
        object : RateLimitRepository {
            override suspend fun increment(
                key: String,
                windowSeconds: Long,
            ): RateLimitCounter = RateLimitCounter(count = Long.MAX_VALUE, ttlSeconds = windowSeconds)
        },
    )
