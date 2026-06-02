package com.pynapli.server.grid

/**
 * Cache over the EXCLUSION-FREE ranked candidate window produced by the Grid rank
 * phase (see [JdbcGridRepository]). The cached value is shared across viewers in
 * the same coarse location + filter class; per-viewer block / hide / self
 * exclusion is always applied AFTER the cache, so a shared window is safe.
 *
 * Two implementations:
 * - [InMemoryGridCandidateRankCache] — per-instance ConcurrentHashMap, the default
 *   used by tests and single-instance deployments.
 * - [RedisGridCandidateRankCache] — shares the window across server instances via
 *   Redis (the production primitive named by GRID_IMPLEMENTATION_SPEC, key
 *   `grid:candidates:v1:cell:...`). Wired in `ServerDependencies` when Redis is
 *   present; it always fails open to the loader so a Redis problem never breaks
 *   the Grid.
 */
internal interface GridCandidateRankCache {
    fun isEnabled(): Boolean

    fun load(
        cacheKey: GridRankCacheKey,
        loader: () -> List<GridRankRow>,
    ): List<GridRankRow>
}
