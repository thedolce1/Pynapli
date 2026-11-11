package com.pynapli.server.location

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.TravelPlaceDto
import com.pynapli.shared.network.dto.TravelPlaceSearchResponseDto
import java.util.UUID

/**
 * Travel-mode type-ahead search (founder UX decision 2026-06-09): the user types a city name or a
 * ZIP/postal code instead of picking from a preset list — e.g. a Toronto user typing "las v" or
 * "89101" before a Las Vegas weekend. Queries resolve server-side against the packaged GeoNames
 * place directory, so the client never needs an external geocoder.
 *
 * Query routing: a string shaped like a US ZIP (3-5 digits) or a Canadian postal code (letter-digit
 * alternation, e.g. "M5V" / "M5V 2T6", matched at forward-sortation-area level) searches postal
 * rows; anything else prefix-matches city names, biggest city first.
 */
class TravelPlaceSearchService(
    private val repository: TravelPlaceRepository,
    private val rateLimitService: RateLimitService? = null,
    private val regionPolicy: TravelRegionPolicy = EmptyTravelRegionPolicy,
) {
    suspend fun search(
        viewerUserId: UUID,
        queryText: String,
    ): TravelPlaceSearchResponseDto {
        if (rateLimitService?.check(viewerUserId, RateLimitedAction.TRAVEL_SEARCH)?.allowed == false) {
            throw TravelLocationException("RATE_LIMITED")
        }
        val query = queryText.trim()
        if (query.length > MAX_QUERY_LENGTH) throw TravelLocationException("INVALID_TRAVEL_SEARCH_QUERY")
        if (query.length < MIN_QUERY_LENGTH) return TravelPlaceSearchResponseDto(items = emptyList())

        val places =
            when {
                US_ZIP_PATTERN.matches(query) -> repository.searchPostal(query, RESULT_LIMIT)
                CA_POSTAL_PATTERN.matches(query) -> repository.searchPostal(query.take(3), RESULT_LIMIT)
                else -> repository.searchCities(query, RESULT_LIMIT)
            }
        // Don't offer a destination Travel mode would refuse.
        val offered =
            places.filterNot { place ->
                regionPolicy.requirementFor(TravelRegionRef(place.name, place.region, place.countryCode, place.countryCode)) ==
                    TravelRegionRequirement.Blocked
            }
        return TravelPlaceSearchResponseDto(items = offered.map { it.toDto() })
    }

    private fun TravelPlaceRecord.toDto(): TravelPlaceDto {
        val locationLabel = listOfNotNull(name, region, countryCode).joinToString(", ")
        return TravelPlaceDto(
            displayName = if (kind == "postal") "${postalCode.orEmpty()} · $locationLabel" else locationLabel,
            city = name,
            region = region,
            country = countryCode,
            latitude = latitude,
            longitude = longitude,
        )
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 80
        const val RESULT_LIMIT = 8

        private val US_ZIP_PATTERN = Regex("\\d{3,5}")

        // Full or partial Canadian postal code ("M5V", "M5V 2T6"). The dataset is
        // FSA-level, so only the first three characters participate in the match.
        private val CA_POSTAL_PATTERN = Regex("[A-Za-z]\\d[A-Za-z](\\s?\\d[A-Za-z]\\d)?")
    }
}
