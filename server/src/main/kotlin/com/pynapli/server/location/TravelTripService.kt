package com.pynapli.server.location

import com.pynapli.shared.network.dto.PlanTravelTripRequestDto
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.TravelTripDto
import com.pynapli.shared.network.dto.TravelTripsDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Date
import java.sql.ResultSet
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * Planned trips: Travel mode scheduled ahead. Planning is a Select feature and checks the
 * destination like a manual switch. [runDue] (every few minutes) starts each trip at local midnight
 * on its first day and ends it after its last, in the zone it was planned from; if the user is no
 * longer allowed Travel mode when a trip starts, the trip is cancelled instead.
 */
class TravelTripService(
    private val dataSource: DataSource,
    private val travel: TravelLocationService,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun list(userId: UUID): TravelTripsDto =
        TravelTripsDto(
            trips =
                query(
                    "SELECT * FROM travel_trips WHERE user_id = ? AND status IN ('planned', 'active') ORDER BY starts_on, created_at",
                    userId,
                ).map { it.toDto() },
        )

    suspend fun plan(
        userId: UUID,
        request: PlanTravelTripRequestDto,
    ): TravelTripDto {
        if (!travel.canPlanTrips(userId)) throw TravelLocationException("ENTITLEMENT_REQUIRED")
        val zone = runCatching { ZoneId.of(request.timezone) }.getOrNull() ?: throw TravelLocationException("INVALID_TRIP_DATES")
        val startsOn = parseDate(request.startsOn)
        val endsOn = parseDate(request.endsOn)
        val today = LocalDate.now(clock.withZone(zone))
        if (startsOn < today || endsOn < startsOn || endsOn > startsOn.plusDays(MAX_TRIP_DAYS) || startsOn > today.plusDays(MAX_DAYS_AHEAD)) {
            throw TravelLocationException("INVALID_TRIP_DATES")
        }
        val place =
            travel.validatedPlace(
                userId,
                SetTravelLocationRequestDto(request.latitude, request.longitude, request.city, request.region, request.country),
            )
        val upcoming = query("SELECT * FROM travel_trips WHERE user_id = ? AND status IN ('planned', 'active')", userId)
        if (upcoming.size >= MAX_UPCOMING_TRIPS) throw TravelLocationException("TRIP_LIMIT_REACHED")
        if (upcoming.any { it.startsOn <= endsOn && startsOn <= it.endsOn }) throw TravelLocationException("TRIP_OVERLAPS")
        val id = UUID.randomUUID()
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        INSERT INTO travel_trips (id, user_id, city, region, country, latitude, longitude, starts_on, ends_on, timezone)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, id)
                        statement.setObject(2, userId)
                        statement.setString(3, place.city)
                        statement.setString(4, place.region)
                        statement.setString(5, place.country)
                        statement.setDouble(6, place.latitude)
                        statement.setDouble(7, place.longitude)
                        statement.setDate(8, Date.valueOf(startsOn))
                        statement.setDate(9, Date.valueOf(endsOn))
                        statement.setString(10, zone.id)
                        statement.executeUpdate()
                    }
            }
        }
        // A trip that starts today switches on right away rather than at the next sweep.
        runDue()
        return query("SELECT * FROM travel_trips WHERE id = ?", id).single().toDto()
    }

    /** Cancels a planned or running trip; a running one turns its Travel mode off. */
    suspend fun cancel(
        userId: UUID,
        tripId: UUID,
    ) {
        val trip =
            query("SELECT * FROM travel_trips WHERE id = ? AND user_id = ? AND status IN ('planned', 'active')", tripId, userId).singleOrNull()
                ?: throw TravelLocationException("TRIP_NOT_FOUND")
        setStatus(trip.id, "cancelled", from = trip.status)
        trip.travelLocationId?.let { travel.clearIfStillActive(userId, it) }
    }

    /** Starts trips whose first day has begun and ends trips whose last day is over. Returns changes. */
    suspend fun runDue(): Int {
        val now = clock.instant()
        var changed = 0
        for (trip in query("SELECT * FROM travel_trips WHERE status IN ('planned', 'active') AND starts_on <= (now() AT TIME ZONE 'UTC')::date + 1")) {
            val zone = runCatching { ZoneId.of(trip.timezone) }.getOrDefault(ZoneId.of("UTC"))
            val startsAt = trip.startsOn.atStartOfDay(zone).toInstant()
            val endsAt =
                trip.endsOn
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
            when {
                now >= endsAt -> {
                    if (setStatus(trip.id, "done", from = trip.status)) {
                        trip.travelLocationId?.let { travel.clearIfStillActive(trip.userId, it) }
                        changed += 1
                    }
                }
                trip.status == "planned" && now >= startsAt -> {
                    val record =
                        travel.activateForTrip(
                            trip.userId,
                            SetTravelLocationRequestDto(trip.latitude, trip.longitude, trip.city, trip.region, trip.country),
                        )
                    if (record == null) {
                        setStatus(trip.id, "cancelled", from = "planned")
                    } else {
                        markActive(trip.id, record.id)
                    }
                    changed += 1
                }
            }
        }
        return changed
    }

    private suspend fun setStatus(
        tripId: UUID,
        status: String,
        from: String,
    ): Boolean = update("UPDATE travel_trips SET status = ?, updated_at = now() WHERE id = ? AND status = ?", status, tripId, from) > 0

    private suspend fun markActive(
        tripId: UUID,
        travelLocationId: UUID,
    ) {
        update(
            "UPDATE travel_trips SET status = 'active', travel_location_id = ?, updated_at = now() WHERE id = ? AND status = 'planned'",
            travelLocationId,
            tripId,
        )
    }

    private suspend fun update(
        sql: String,
        vararg args: Any?,
    ): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.executeUpdate()
                }
            }
        }

    private suspend fun query(
        sql: String,
        vararg args: Any?,
    ): List<TravelTripRow> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toTrip()) } }
                }
            }
        }

    private fun parseDate(text: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (_: DateTimeException) {
            throw TravelLocationException("INVALID_TRIP_DATES")
        }

    private data class TravelTripRow(
        val id: UUID,
        val userId: UUID,
        val city: String,
        val region: String?,
        val country: String?,
        val latitude: Double,
        val longitude: Double,
        val startsOn: LocalDate,
        val endsOn: LocalDate,
        val timezone: String,
        val status: String,
        val travelLocationId: UUID?,
    ) {
        fun toDto() = TravelTripDto(id.toString(), city, region, country, startsOn.toString(), endsOn.toString(), status)
    }

    private fun ResultSet.toTrip() =
        TravelTripRow(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            city = getString("city"),
            region = getString("region"),
            country = getString("country"),
            latitude = getDouble("latitude"),
            longitude = getDouble("longitude"),
            startsOn = getDate("starts_on").toLocalDate(),
            endsOn = getDate("ends_on").toLocalDate(),
            timezone = getString("timezone"),
            status = getString("status"),
            travelLocationId = getObject("travel_location_id", UUID::class.java),
        )

    companion object {
        const val MAX_TRIP_DAYS = 30L
        const val MAX_DAYS_AHEAD = 365L
        const val MAX_UPCOMING_TRIPS = 5
    }
}

/** Starts and ends planned trips on time. Status changes are conditional, so replicas can overlap. */
class TravelTripWorker(
    private val service: TravelTripService,
    private val intervalMillis: Long = 5 * 60 * 1_000L,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(TravelTripWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        val changed = service.runDue()
                        if (changed > 0) logger.info("Planned trips: {} started or ended", changed)
                    }.onFailure { error -> logger.warn("Planned trip sweep failed", error) }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
