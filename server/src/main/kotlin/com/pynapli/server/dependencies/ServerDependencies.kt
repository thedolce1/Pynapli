package com.pynapli.server.dependencies

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.JdbcActivityRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.grid.GridService
import com.pynapli.server.grid.JdbcGridRepository
import com.pynapli.server.location.JdbcLocationRepository
import com.pynapli.server.location.LocationService
import com.pynapli.server.media.JdbcMediaRepository
import com.pynapli.server.media.LocalDevMediaUploadSigner
import com.pynapli.server.media.LocalMediaAssetUrlResolver
import com.pynapli.server.media.LocalMediaUploadStorage
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaService
import com.pynapli.server.media.StaticMediaUploadSigner
import com.pynapli.server.messaging.JdbcMessagingRepository
import com.pynapli.server.messaging.MessagingService
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.presence.RedisPresenceRepository
import com.pynapli.server.profile.JdbcProfileRepository
import com.pynapli.server.profile.ProfileService
import com.pynapli.server.safety.BlockService
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.safety.JdbcReportRepository
import com.pynapli.server.safety.ReportService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import java.nio.file.Path

class ServerDependencies(
    val config: ServerConfig,
    val database: Database?,
    private val redisCache: RedisCache?,
    val authService: AuthService?,
    val profileService: ProfileService?,
    val locationService: LocationService?,
    val presenceService: PresenceService?,
    val gridService: GridService?,
    val messagingService: MessagingService?,
    val mediaService: MediaService?,
    val activityService: ActivityService?,
    val blockService: BlockService?,
    val reportService: ReportService?,
    val localMediaUploadStorage: LocalMediaUploadStorage?,
    private val probes: List<DependencyProbe>
) : AutoCloseable {
    suspend fun checkAll() = probes.map { it.check() }

    override fun close() {
        probes.asReversed().forEach { probe ->
            runCatching { probe.close() }
        }
        runCatching { redisCache?.close() }
        runCatching { database?.close() }
    }

    companion object {
        fun fromEnvironment(config: ServerConfig = ServerConfig.fromEnvironment()): ServerDependencies {
            val database = Database(config)
            val redisCache = RedisCache(config.redisUrl)
            val postgres = PostgresDependencyProbe(database)
            val redis = RedisDependencyProbe(redisCache)
            val authService = AuthService(JdbcAuthRepository(database.dataSource))
            val locationService = LocationService(JdbcLocationRepository(database.dataSource))
            val presenceService = PresenceService(RedisPresenceRepository(redisCache))
            val mediaAssetUrlResolver: MediaAssetUrlResolver? =
                if (config.mediaStorageProvider.equals("local", ignoreCase = true)) {
                    LocalMediaAssetUrlResolver(config.mediaUploadBaseUrl)
                } else {
                    null
                }
            val localMediaUploadStorage = if (config.mediaStorageProvider.equals("local", ignoreCase = true)) {
                LocalMediaUploadStorage(Path.of(config.mediaLocalUploadDirectory))
            } else {
                null
            }
            val uploadSigner = if (config.mediaStorageProvider.equals("local", ignoreCase = true)) {
                LocalDevMediaUploadSigner(
                    storageBucket = config.mediaStorageBucket,
                    uploadBaseUrl = config.mediaUploadBaseUrl
                )
            } else {
                StaticMediaUploadSigner(
                    storageProvider = config.mediaStorageProvider,
                    storageBucket = config.mediaStorageBucket,
                    uploadBaseUrl = config.mediaUploadBaseUrl
                )
            }
            val blockRepository = JdbcBlockRepository(database.dataSource)
            val blockService = BlockService(blockRepository)
            val reportService = ReportService(JdbcReportRepository(database.dataSource))
            val mediaRepository = JdbcMediaRepository(database.dataSource)
            val mediaService = MediaService(
                repository = mediaRepository,
                uploadSigner = uploadSigner,
                devAutoApproveUploads = config.mediaDevAutoApproveUploads,
                blockRepository = blockRepository
            )
            val activityService = ActivityService(
                repository = JdbcActivityRepository(database.dataSource),
                mediaService = mediaService,
                mediaAssetUrlResolver = mediaAssetUrlResolver
            )
            val profileService = ProfileService(
                repository = JdbcProfileRepository(database.dataSource),
                mediaAssetUrlResolver = mediaAssetUrlResolver,
                activityService = activityService,
                blockRepository = blockRepository
            )
            val gridService = GridService(
                repository = JdbcGridRepository(database.dataSource),
                presenceService = presenceService,
                mediaAssetUrlResolver = mediaAssetUrlResolver,
                blockRepository = blockRepository
            )
            val messagingService = MessagingService(
                repository = JdbcMessagingRepository(database.dataSource),
                blockRepository = blockRepository
            )
            return ServerDependencies(
                config = config,
                database = database,
                redisCache = redisCache,
                authService = authService,
                profileService = profileService,
                locationService = locationService,
                presenceService = presenceService,
                gridService = gridService,
                messagingService = messagingService,
                mediaService = mediaService,
                activityService = activityService,
                blockService = blockService,
                reportService = reportService,
                localMediaUploadStorage = localMediaUploadStorage,
                probes = listOf(postgres, redis)
            )
        }

        fun test(config: ServerConfig = ServerConfig.fromEnvironment()): ServerDependencies =
            ServerDependencies(
                config = config,
                database = null,
                redisCache = null,
                authService = null,
                profileService = null,
                locationService = null,
                presenceService = null,
                gridService = null,
                messagingService = null,
                mediaService = null,
                activityService = null,
                blockService = null,
                reportService = null,
                localMediaUploadStorage = null,
                probes = emptyList()
            )
    }
}

fun Application.closeDependenciesOnStop(dependencies: ServerDependencies) {
    monitor.subscribe(ApplicationStopped) {
        dependencies.close()
    }
}
