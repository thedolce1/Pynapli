package com.pynapli.server.grid

import com.pynapli.shared.network.dto.GridCountResponseDto
import java.time.Duration
import java.time.Instant

/**
 * The filter sheet count's short per-viewer cache (plan G2-4). Keys are per viewer (the sealed grid
 * scope hash plus their blocks and hides), so nothing is shared across viewers. Bounded and short-lived:
 * a count is a hint while editing, and the Grid answer itself is never cached here.
 */
internal class GridCountCache(
    private val ttl: Duration = Duration.ofSeconds(15),
    private val maxEntries: Int = 4_096,
) {
    private class CachedCount(
        val response: GridCountResponseDto,
        val storedAt: Instant,
    )

    private val entries =
        object : LinkedHashMap<String, CachedCount>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedCount>?): Boolean = size > maxEntries
        }

    @Synchronized
    fun get(
        key: String,
        now: Instant,
    ): GridCountResponseDto? {
        val entry = entries[key] ?: return null
        if (Duration.between(entry.storedAt, now) >= ttl) {
            entries.remove(key)
            return null
        }
        return entry.response
    }

    @Synchronized
    fun put(
        key: String,
        response: GridCountResponseDto,
        now: Instant,
    ) {
        entries[key] = CachedCount(response, now)
    }
}
