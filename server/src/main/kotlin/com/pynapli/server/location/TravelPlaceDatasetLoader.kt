package com.pynapli.server.location

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Loads the packaged GeoNames-derived travel place directory into Postgres at startup.
 *
 * The resource's first line is a `#pynapli-travel-places version=...` header; when it matches the
 * version already recorded in `travel_places_dataset` the (~77k-row) reload is skipped, so steady
 * restarts pay one SELECT. Regenerate the resource with `scripts/generate-travel-places.sh`.
 *
 * Place data (c) GeoNames (geonames.org), CC BY 4.0 — see docs/THIRD_PARTY_DATA.md.
 */
class TravelPlaceDatasetLoader(
    private val repository: TravelPlaceRepository,
    private val resourcePath: String = DEFAULT_RESOURCE,
) {
    fun loadIfStale() {
        val stream =
            javaClass.classLoader.getResourceAsStream(resourcePath)
                ?: error("Travel place dataset resource '$resourcePath' is missing from the server classpath.")
        BufferedReader(InputStreamReader(GZIPInputStream(stream), Charsets.UTF_8)).use { reader ->
            val header = reader.readLine() ?: error("Travel place dataset resource '$resourcePath' is empty.")
            val version =
                VERSION_PATTERN.find(header)?.groupValues?.get(1)
                    ?: error("Travel place dataset header is missing a version marker: $header")
            runBlocking {
                if (repository.loadedDatasetVersion() == version) {
                    logger.info("Travel place dataset $version already loaded; skipping.")
                    return@runBlocking
                }
                val startedAt = System.currentTimeMillis()
                val inserted = repository.replaceDataset(version, reader.lineSequence().mapNotNull(::parseRow))
                logger.info(
                    "Loaded travel place dataset {} ({} places) in {} ms.",
                    version,
                    inserted,
                    System.currentTimeMillis() - startedAt,
                )
            }
        }
    }

    private fun parseRow(line: String): TravelPlaceRow? {
        if (line.isBlank() || line.startsWith("#")) return null
        val fields = line.split('\t')
        if (fields.size < 9) return null
        return TravelPlaceRow(
            kind = fields[0],
            countryCode = fields[1],
            postalCode = fields[2].takeIf { it.isNotEmpty() },
            name = fields[3],
            asciiName = fields[4],
            region = fields[5].takeIf { it.isNotEmpty() },
            latitude = fields[6].toDoubleOrNull() ?: return null,
            longitude = fields[7].toDoubleOrNull() ?: return null,
            population = fields[8].toLongOrNull() ?: 0L,
        )
    }

    companion object {
        const val DEFAULT_RESOURCE = "travel/travel_places.tsv.gz"
        private val VERSION_PATTERN = Regex("version=(\\S+)")
        private val logger = LoggerFactory.getLogger(TravelPlaceDatasetLoader::class.java)
    }
}
