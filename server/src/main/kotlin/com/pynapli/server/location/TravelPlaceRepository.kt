package com.pynapli.server.location

interface TravelPlaceRepository {
    /** Case-insensitive prefix search over city ascii names, largest population first. */
    suspend fun searchCities(
        namePrefix: String,
        limit: Int,
    ): List<TravelPlaceRecord>

    /** Prefix search over postal codes (US ZIP, CA forward sortation area). */
    suspend fun searchPostal(
        postalPrefix: String,
        limit: Int,
    ): List<TravelPlaceRecord>

    /**
     * Country code of the nearest known city to a point, or null when none is within ~200 km
     * (open water, uncovered areas). Used to apply the region policy to the real destination.
     */
    suspend fun nearestCountryCode(
        latitude: Double,
        longitude: Double,
    ): String? = null

    /** Version string of the currently loaded dataset, or null when never loaded. */
    suspend fun loadedDatasetVersion(): String?

    /** Atomically replaces all rows with [rows] and records [version]. */
    suspend fun replaceDataset(
        version: String,
        rows: Sequence<TravelPlaceRow>,
    ): Int
}

data class TravelPlaceRecord(
    val kind: String,
    val countryCode: String,
    val postalCode: String?,
    val name: String,
    val region: String?,
    val latitude: Double,
    val longitude: Double,
)

data class TravelPlaceRow(
    val kind: String,
    val countryCode: String,
    val postalCode: String?,
    val name: String,
    val asciiName: String,
    val region: String?,
    val latitude: Double,
    val longitude: Double,
    val population: Long,
)
