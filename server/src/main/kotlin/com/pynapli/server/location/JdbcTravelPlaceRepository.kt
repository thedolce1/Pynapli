package com.pynapli.server.location

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import javax.sql.DataSource

class JdbcTravelPlaceRepository(
    private val dataSource: DataSource,
) : TravelPlaceRepository {
    override suspend fun searchCities(
        namePrefix: String,
        limit: Int,
    ): List<TravelPlaceRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT kind, country_code, postal_code, name, region, latitude, longitude
                        FROM travel_places
                        WHERE kind = 'city'
                          AND lower(ascii_name) LIKE lower(?) || '%'
                        ORDER BY population DESC, name ASC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, namePrefix)
                        statement.setInt(2, limit)
                        statement.executeQuery().use { rs -> rs.readPlaces() }
                    }
            }
        }

    override suspend fun searchPostal(
        postalPrefix: String,
        limit: Int,
    ): List<TravelPlaceRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT kind, country_code, postal_code, name, region, latitude, longitude
                        FROM travel_places
                        WHERE kind = 'postal'
                          AND postal_code LIKE upper(?) || '%'
                        ORDER BY postal_code ASC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, postalPrefix)
                        statement.setInt(2, limit)
                        statement.executeQuery().use { rs -> rs.readPlaces() }
                    }
            }
        }

    override suspend fun loadedDatasetVersion(): String? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT version FROM travel_places_dataset").use { statement ->
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getString("version") else null }
                }
            }
        }

    override suspend fun replaceDataset(
        version: String,
        rows: Sequence<TravelPlaceRow>,
    ): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.prepareStatement("DELETE FROM travel_places").use { it.executeUpdate() }
                    var inserted = 0
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO travel_places (
                                kind, country_code, postal_code, name, ascii_name,
                                region, latitude, longitude, population
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                        ).use { statement ->
                            var pending = 0
                            for (row in rows) {
                                statement.setString(1, row.kind)
                                statement.setString(2, row.countryCode)
                                statement.setString(3, row.postalCode)
                                statement.setString(4, row.name)
                                statement.setString(5, row.asciiName)
                                statement.setString(6, row.region)
                                statement.setDouble(7, row.latitude)
                                statement.setDouble(8, row.longitude)
                                statement.setLong(9, row.population)
                                statement.addBatch()
                                pending++
                                inserted++
                                if (pending == BATCH_SIZE) {
                                    statement.executeBatch()
                                    pending = 0
                                }
                            }
                            if (pending > 0) statement.executeBatch()
                        }
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO travel_places_dataset (singleton, version, loaded_at)
                            VALUES (TRUE, ?, now())
                            ON CONFLICT (singleton) DO UPDATE SET version = EXCLUDED.version, loaded_at = now()
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, version)
                            statement.executeUpdate()
                        }
                    connection.commit()
                    inserted
                } catch (failure: Throwable) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }

    private fun ResultSet.readPlaces(): List<TravelPlaceRecord> {
        val places = mutableListOf<TravelPlaceRecord>()
        while (next()) {
            places +=
                TravelPlaceRecord(
                    kind = getString("kind"),
                    countryCode = getString("country_code"),
                    postalCode = getString("postal_code"),
                    name = getString("name"),
                    region = getString("region"),
                    latitude = getDouble("latitude"),
                    longitude = getDouble("longitude"),
                )
        }
        return places
    }

    private companion object {
        private const val BATCH_SIZE = 1_000
    }
}
