package com.pynapli.server.profile

import com.pynapli.shared.network.dto.TonightAvailabilityDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

class TonightAvailabilityService(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun current(userId: UUID): TonightAvailabilityDto =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "SELECT tonight_available_until FROM profiles WHERE user_id = ?",
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) {
                                TonightAvailabilityDto(availableUntil = null)
                            } else {
                                val ts = rs.getTimestamp("tonight_available_until")
                                TonightAvailabilityDto(availableUntil = ts?.toInstant()?.toString())
                            }
                        }
                    }
            }
        }

    suspend fun setAvailable(
        userId: UUID,
        durationHours: Int? = null,
    ): TonightAvailabilityDto {
        val hours =
            (durationHours ?: DEFAULT_DURATION_HOURS).also { hours ->
                if (hours !in MIN_DURATION_HOURS..MAX_DURATION_HOURS) {
                    throw TonightAvailabilityException("INVALID_TONIGHT_DURATION")
                }
            }
        val until = clock.instant().plus(Duration.ofHours(hours.toLong()))
        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE profiles SET tonight_available_until = ?, updated_at = now() WHERE user_id = ?",
                    ).use { statement ->
                        statement.setObject(1, java.sql.Timestamp.from(until))
                        statement.setObject(2, userId)
                        val rows = statement.executeUpdate()
                        if (rows == 0) throw TonightAvailabilityException("PROFILE_NOT_FOUND")
                    }
                TonightAvailabilityDto(availableUntil = until.toString())
            }
        }
    }

    suspend fun clear(userId: UUID): TonightAvailabilityDto =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE profiles SET tonight_available_until = NULL, updated_at = now() WHERE user_id = ?",
                    ).use { statement ->
                        statement.setObject(1, userId)
                        val rows = statement.executeUpdate()
                        if (rows == 0) throw TonightAvailabilityException("PROFILE_NOT_FOUND")
                    }
                TonightAvailabilityDto(availableUntil = null)
            }
        }

    companion object {
        const val DEFAULT_DURATION_HOURS: Int = 12
        const val MIN_DURATION_HOURS: Int = 1
        const val MAX_DURATION_HOURS: Int = 24
    }
}

class TonightAvailabilityException(
    val code: String,
) : RuntimeException(code)
