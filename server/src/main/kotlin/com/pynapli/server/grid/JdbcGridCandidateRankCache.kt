package com.pynapli.server.grid

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

/**
 * In-memory ranked-candidate cache for the Grid discovery query. Direct copy of
 * the reviewed Swipe deck rank cache (`JdbcSwipeDeckRankCache`): a TTL-keyed
 * ConcurrentHashMap with single-flight via a CompletableFuture in-flight map and
 * size-bounded eviction. The cached value is an EXCLUSION-FREE ranked window
 * shared across viewers in the same coarse location + filter class; per-viewer
 * block / hide / self exclusion is applied AFTER the cache.
 */
internal class JdbcGridCandidateRankCache(
    private val ttl: Duration,
    private val maxEntries: Int,
) {
    private val rows = ConcurrentHashMap<GridRankCacheKey, CachedGridRankRows>()
    private val inFlight = ConcurrentHashMap<GridRankCacheKey, CompletableFuture<CachedGridRankRows>>()

    fun isEnabled(): Boolean = !ttl.isZero && !ttl.isNegative && maxEntries > 0

    fun load(
        cacheKey: GridRankCacheKey,
        loader: () -> List<GridRankRow>,
    ): List<GridRankRow> {
        val now = Instant.now()
        rows[cacheKey]?.let { cached ->
            if (cached.expiresAt.isAfter(now)) return cached.rows
            rows.remove(cacheKey, cached)
        }

        val newFuture = CompletableFuture<CachedGridRankRows>()
        val existingFuture = inFlight.putIfAbsent(cacheKey, newFuture)
        if (existingFuture != null) {
            return try {
                existingFuture.get().rows
            } catch (error: ExecutionException) {
                throw (error.cause ?: error)
            }
        }

        try {
            val loaded =
                CachedGridRankRows(
                    rows = loader(),
                    expiresAt = now.plus(ttl),
                )
            rows[cacheKey] = loaded
            evict(now)
            newFuture.complete(loaded)
            return loaded.rows
        } catch (error: Throwable) {
            newFuture.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(cacheKey, newFuture)
        }
    }

    private fun evict(now: Instant) {
        if (rows.size < maxEntries) return
        rows.entries.removeIf { (_, cached) -> !cached.expiresAt.isAfter(now) }
        if (rows.size >= maxEntries) {
            rows.clear()
        }
    }
}
