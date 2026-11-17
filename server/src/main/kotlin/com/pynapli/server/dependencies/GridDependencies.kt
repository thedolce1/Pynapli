package com.pynapli.server.dependencies

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.grid.GridService
import com.pynapli.server.grid.JdbcGridRepository
import com.pynapli.server.grid.JdbcGridViewerTrustRepository
import com.pynapli.server.grid.RedisGridCandidateRankCache
import com.pynapli.server.grid.SavedGridFilterService
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.savedfilters.JdbcSavedFilterRepository
import com.pynapli.server.savedfilters.SavedFilterSurface
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.swipe.SavedSwipeFilterService
import java.time.Duration
import javax.sql.DataSource

/**
 * Grid/discovery subsystem wiring extracted from `ServerDependencies.fromEnvironment` to keep the DI
 * root a thin orchestrator. Builds the grid query service and the saved grid/swipe filter services;
 * all three are consumed only by the `ServerDependencies` constructor, so the shared upstream
 * dependencies are threaded in as parameters. Behavior matches the original inline construction.
 */
internal class GridDependencies(
    val gridService: GridService,
    val savedGridFilterService: SavedGridFilterService,
    val savedSwipeFilterService: SavedSwipeFilterService,
)

internal fun buildGridDependencies(
    config: ServerConfig,
    dataSource: DataSource,
    redisCache: RedisCache,
    presenceService: PresenceService,
    mediaAssetUrlResolver: MediaAssetUrlResolver?,
    blockRepository: BlockRepository,
    hiddenProfileRepository: HiddenProfileRepository,
    subscriptionService: SubscriptionService,
    rateLimitService: RateLimitService,
): GridDependencies {
    val gridService =
        GridService(
            repository =
                JdbcGridRepository(
                    dataSource = dataSource,
                    // Share the ranked candidate window across instances via Redis
                    // (GRID_IMPLEMENTATION_SPEC candidate cache). The cached window is
                    // exclusion-free, so per-viewer safety is unchanged; the cache fails
                    // open to direct queries if Redis is unavailable. Single-instance /
                    // tests use the in-memory default baked into JdbcGridRepository.
                    candidateRankCache = RedisGridCandidateRankCache(redisCache, Duration.ofSeconds(5)),
                ),
            cursorCodec = DiscoveryCursorCodec(config.discoveryCursorSecret),
            presenceService = presenceService,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            blockRepository = blockRepository,
            hiddenProfileRepository = hiddenProfileRepository,
            subscriptionService = subscriptionService,
            viewerTrustRepository = JdbcGridViewerTrustRepository(dataSource),
            rateLimitService = rateLimitService,
        )
    val savedGridFilterService =
        SavedGridFilterService(
            repository = JdbcSavedFilterRepository(dataSource, SavedFilterSurface.Grid),
            subscriptionService = subscriptionService,
            viewerTrustRepository = JdbcGridViewerTrustRepository(dataSource),
        )
    val savedSwipeFilterService =
        SavedSwipeFilterService(
            repository = JdbcSavedFilterRepository(dataSource, SavedFilterSurface.Swipe),
            subscriptionService = subscriptionService,
            viewerTrustRepository = JdbcGridViewerTrustRepository(dataSource),
        )
    return GridDependencies(
        gridService = gridService,
        savedGridFilterService = savedGridFilterService,
        savedSwipeFilterService = savedSwipeFilterService,
    )
}
