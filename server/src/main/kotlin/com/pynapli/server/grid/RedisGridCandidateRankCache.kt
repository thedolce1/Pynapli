package com.pynapli.server.grid

import com.pynapli.server.dependencies.RedisCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * Cross-instance ranked-candidate cache backed by Redis — the production primitive
 * named by GRID_IMPLEMENTATION_SPEC ("Candidate Cache", key
 * `grid:candidates:v1:cell:{cell}:radius:{radius}:...`). Behind a horizontally
 * scaled fleet the per-instance [InMemoryGridCandidateRankCache] warms each node
 * independently; this implementation shares one window across every instance in
 * the same coarse location + filter class, so the expensive spatial rank scan runs
 * far less often fleet-wide.
 *
 * The cached value is an EXCLUSION-FREE ranked window (no per-viewer block / hide /
 * self), exactly as the in-memory cache; per-viewer exclusion is applied AFTER the
 * cache in [JdbcGridRepository] / `GridService`, so the shared window is safe.
 *
 * Fails OPEN: any Redis error (or a corrupt/old blob) falls through to the loader,
 * so a Redis outage degrades to direct queries rather than breaking the Grid.
 */
internal class RedisGridCandidateRankCache(
    private val redisCache: RedisCache,
    private val ttl: Duration,
) : GridCandidateRankCache {
    private val ttlSeconds: Long = ttl.seconds.coerceAtLeast(1)

    override fun isEnabled(): Boolean = !ttl.isZero && !ttl.isNegative

    override fun load(
        cacheKey: GridRankCacheKey,
        loader: () -> List<GridRankRow>,
    ): List<GridRankRow> {
        val redisKey = redisKey(cacheKey)

        val cached = runCatching { redisCache.commands.get(redisKey) }.getOrNull()
        if (cached != null) {
            val decoded = runCatching { GridRankWindowCodec.decode(cached) }.getOrNull()
            if (decoded != null) return decoded
        }

        val loaded = loader()
        runCatching { redisCache.commands.setex(redisKey, ttlSeconds, GridRankWindowCodec.encode(loaded)) }
        return loaded
    }

    private fun redisKey(key: GridRankCacheKey): String {
        // Spec naming: grid:candidates:v1:cell:{cell}:radius:{radius}:... . The cell
        // is the coarse E3 lat/lng bucket; the rich filter class is hashed so the
        // key stays bounded regardless of how many filters are active.
        val cell = "${key.location.longitudeE3}_${key.location.latitudeE3}"
        val filterHash = sha256Hex(key.filterKey).take(FILTER_HASH_CHARS)
        return "grid:candidates:v1:cell:$cell:radius:${key.radiusMeters}:w:${key.window}:f:$filterHash"
    }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val FILTER_HASH_CHARS = 16
    }
}

/**
 * Compact JSON codec for the ranked window. A dedicated [CachedGridRankRowDto] with
 * short field names keeps the blob small and avoids needing a UUID serializer on
 * the domain [GridRankRow] (UUIDs are carried as strings).
 */
internal object GridRankWindowCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CachedGridRankRowDto.serializer())

    fun encode(rows: List<GridRankRow>): String = json.encodeToString(serializer, rows.map { it.toDto() })

    fun decode(blob: String): List<GridRankRow> = json.decodeFromString(serializer, blob).map { it.toDomain() }

    private fun GridRankRow.toDto() =
        CachedGridRankRowDto(
            u = userId.toString(),
            n = displayName,
            sd = showDistance,
            bb = boostBucket,
            db = distanceBucketMeters,
            dm = distanceMeters,
            v = visiting,
        )

    private fun CachedGridRankRowDto.toDomain() =
        GridRankRow(
            userId = UUID.fromString(u),
            displayName = n,
            showDistance = sd,
            boostBucket = bb,
            distanceBucketMeters = db,
            distanceMeters = dm,
            visiting = v,
        )

    @Serializable
    private data class CachedGridRankRowDto(
        val u: String,
        val n: String,
        val sd: Boolean,
        val bb: Int,
        val db: Int,
        val dm: Double,
        val v: Boolean,
    )
}
