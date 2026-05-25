package com.pynapli.server.swipe

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

internal class JdbcSwipeDeckRankCache(
    private val ttl: Duration,
    private val maxEntries: Int
) {
    private val rows = ConcurrentHashMap<DeckRankCacheKey, CachedDeckRankRows>()
    private val inFlight = ConcurrentHashMap<DeckRankCacheKey, CompletableFuture<CachedDeckRankRows>>()

    fun isEnabled(): Boolean =
        !ttl.isZero && !ttl.isNegative && maxEntries > 0

    fun load(
        cacheKey: DeckRankCacheKey,
        loader: () -> List<DeckRankRow>
    ): List<DeckRankRow> {
        val now = Instant.now()
        rows[cacheKey]?.let { cached ->
            if (cached.expiresAt.isAfter(now)) return cached.rows
            rows.remove(cacheKey, cached)
        }

        val newFuture = CompletableFuture<CachedDeckRankRows>()
        val existingFuture = inFlight.putIfAbsent(cacheKey, newFuture)
        if (existingFuture != null) {
            return try {
                existingFuture.get().rows
            } catch (error: ExecutionException) {
                throw (error.cause ?: error)
            }
        }

        try {
            val loaded = CachedDeckRankRows(
                rows = loader(),
                expiresAt = now.plus(ttl)
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
