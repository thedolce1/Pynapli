package com.pynapli.server.location

import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TravelPlaceRepositoryIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun fixtureRows(): Sequence<TravelPlaceRow> =
            sequenceOf(
                TravelPlaceRow("city", "US", null, "Las Vegas", "Las Vegas", "Nevada", 36.17497, -115.13722, 641_903),
                TravelPlaceRow("city", "US", null, "North Las Vegas", "North Las Vegas", "Nevada", 36.19886, -115.1175, 234_807),
                TravelPlaceRow("city", "PH", null, "Las Piñas", "Las Pinas", "Metro Manila", 14.45056, 120.98278, 590_000),
                TravelPlaceRow("city", "CA", null, "Toronto", "Toronto", "Ontario", 43.70011, -79.4163, 2_600_000),
                TravelPlaceRow("postal", "US", "89101", "Las Vegas", "Las Vegas", "NV", 36.1699, -115.1398, 0),
                TravelPlaceRow("postal", "US", "89102", "Las Vegas", "Las Vegas", "NV", 36.1452, -115.1838, 0),
                TravelPlaceRow("postal", "CA", "M5V", "Toronto (Entertainment District)", "Toronto", "ON", 43.6444, -79.3937, 0),
            )

        "dataset load is versioned and skip-aware, search is prefix + population ordered" {
            runBlocking {
                val repository = JdbcTravelPlaceRepository(dataSource)
                assertEquals(null, repository.loadedDatasetVersion())

                val inserted = repository.replaceDataset("fixture-v1", fixtureRows())
                assertEquals(7, inserted)
                assertEquals("fixture-v1", repository.loadedDatasetVersion())

                // Case-insensitive prefix match; biggest city first; ascii name matches accents.
                val cities = repository.searchCities("las", 8)
                assertEquals(listOf("Las Vegas", "Las Piñas"), cities.map { it.name })

                val postal = repository.searchPostal("891", 8)
                assertEquals(listOf("89101", "89102"), postal.map { it.postalCode })

                val fsa = repository.searchPostal("m5v", 8)
                assertEquals(listOf("M5V"), fsa.map { it.postalCode })
                assertEquals("Toronto (Entertainment District)", fsa.single().name)

                // Reloading under a new version replaces, not appends.
                repository.replaceDataset("fixture-v2", fixtureRows())
                assertEquals("fixture-v2", repository.loadedDatasetVersion())
                assertEquals(2, repository.searchPostal("891", 8).size)
            }
        }

        "packaged dataset loader loads the real resource and resolves Las Vegas" {
            runBlocking {
                val repository = JdbcTravelPlaceRepository(dataSource)
                TravelPlaceDatasetLoader(repository).loadIfStale()

                val version = repository.loadedDatasetVersion()
                assertTrue(!version.isNullOrBlank(), "Loader must record the dataset version")

                val vegas = repository.searchCities("las veg", 8)
                assertTrue(
                    vegas.any { it.name == "Las Vegas" && it.region == "Nevada" },
                    "Packaged dataset must resolve Las Vegas, Nevada; got ${vegas.map { it.name }}",
                )

                val zip = repository.searchPostal("89101", 8)
                assertEquals("Las Vegas", zip.single().name)

                // Second invocation with the same packaged version is a no-op (skip path).
                TravelPlaceDatasetLoader(repository).loadIfStale()
                assertEquals(version, repository.loadedDatasetVersion())
            }
        }
    })
