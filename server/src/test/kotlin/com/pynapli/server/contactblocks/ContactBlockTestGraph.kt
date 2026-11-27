package com.pynapli.server.contactblocks

import com.pynapli.server.activity.ActivityBroadcaster
import com.pynapli.server.activity.ActivityRealtimeDelta
import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.JdbcActivityRepository
import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.favorites.FavoriteService
import com.pynapli.server.favorites.JdbcFavoriteRepository
import com.pynapli.server.grid.GridService
import com.pynapli.server.grid.JdbcGridRepository
import com.pynapli.server.messaging.JdbcMessagingRepository
import com.pynapli.server.messaging.MessageBroadcaster
import com.pynapli.server.messaging.MessageRealtimeDelta
import com.pynapli.server.messaging.MessagingService
import com.pynapli.server.notifications.JdbcNotificationRepository
import com.pynapli.server.notifications.NotificationService
import com.pynapli.server.profile.JdbcProfileRepository
import com.pynapli.server.profile.ProfileService
import com.pynapli.server.profilelink.JdbcProfileLinkRepository
import com.pynapli.server.profilelink.ProfileLinkService
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.safety.JdbcHiddenProfileRepository
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.tonight.JdbcTonightFeedRepository
import com.pynapli.server.tonight.TonightFeedService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** Counts nudges instead of resolving: proves the add never resolves on the request path. */
internal class CountingResolutionTrigger : ContactBlockResolutionTrigger {
    val nudges = AtomicInteger(0)

    override fun requestResolution() {
        nudges.incrementAndGet()
    }
}

/**
 * The services a person touches, wired the way ServerDependencies wires them (contact block keys
 * and viewer-side visibility everywhere), against the shared test database. No rank caches, so each
 * query reads the database (the cache test builds its own).
 */
internal class ContactBlockTestGraph(
    val dataSource: DataSource,
) {
    val tokens = ContactBlockTokens.localDevelopment()
    val visibility = JdbcContactBlockVisibility(dataSource, tokens)
    val resolver = ContactBlockResolver(JdbcContactBlockResolverRepository(dataSource), tokens)
    val trigger = CountingResolutionTrigger()
    val contactBlocks = ContactBlockService(JdbcContactBlockRepository(dataSource), tokens, trigger)

    val activityBroadcaster = ActivityBroadcaster()
    val messageBroadcaster = MessageBroadcaster()
    val notifications = NotificationService(JdbcNotificationRepository(dataSource), contactBlockVisibility = visibility)
    private val blocks = JdbcBlockRepository(dataSource)

    val activity =
        ActivityService(
            repository = JdbcActivityRepository(dataSource, contactBlockTokens = tokens),
            blockRepository = blocks,
            notificationPublisher = notifications,
            activityBroadcaster = activityBroadcaster,
            contactBlockVisibility = visibility,
        )
    val favorites =
        FavoriteService(
            repository = JdbcFavoriteRepository(dataSource),
            cursorCodec = DiscoveryCursorCodec("contact-block-test-cursor-secret"),
            blockRepository = blocks,
            contactBlockVisibility = visibility,
            hiddenProfileRepository = JdbcHiddenProfileRepository(dataSource),
        )
    val profiles =
        ProfileService(
            repository = JdbcProfileRepository(dataSource),
            activityService = activity,
            blockRepository = blocks,
            favoriteService = favorites,
            contactBlockVisibility = visibility,
            hiddenProfileRepository = JdbcHiddenProfileRepository(dataSource),
        )
    val grid =
        GridService(
            repository = JdbcGridRepository(dataSource, rankCacheTtl = Duration.ZERO),
            blockRepository = blocks,
            hiddenProfileRepository = JdbcHiddenProfileRepository(dataSource),
            contactBlockVisibility = visibility,
        )
    val swipe =
        SwipeService(
            repository = JdbcSwipeRepository(dataSource, rankCacheTtl = Duration.ZERO),
            blockRepository = blocks,
            notificationPublisher = notifications,
            activityService = activity,
            contactBlockVisibility = visibility,
        )
    val messaging =
        MessagingService(
            repository = JdbcMessagingRepository(dataSource, contactBlockTokens = tokens),
            blockRepository = blocks,
            notificationPublisher = notifications,
            messageBroadcaster = messageBroadcaster,
            activityService = activity,
            contactBlockVisibility = visibility,
        )
    val tonight =
        TonightFeedService(
            repository = JdbcTonightFeedRepository(dataSource),
            blockRepository = blocks,
            hiddenProfileRepository = JdbcHiddenProfileRepository(dataSource),
            contactBlockVisibility = visibility,
        )
    val profileLinks =
        ProfileLinkService(
            repository = JdbcProfileLinkRepository(dataSource),
            blockRepository = blocks,
            notificationPublisher = notifications,
            contactBlockVisibility = visibility,
        )

    /** Every realtime delta both broadcasters emit while the returned job runs. */
    val activityDeltas: MutableList<ActivityRealtimeDelta> = Collections.synchronizedList(mutableListOf())
    val messageDeltas: MutableList<MessageRealtimeDelta> = Collections.synchronizedList(mutableListOf())

    fun recordRealtime(scope: CoroutineScope): List<Job> =
        listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) { activityBroadcaster.deltas.collect { activityDeltas += it } },
            scope.launch(start = CoroutineStart.UNDISPATCHED) { messageBroadcaster.deltas.collect { messageDeltas += it } },
        )

    /** Marks the account's address verified and records its contact token, as sign-up verify does. */
    suspend fun verifyAddress(
        userId: UUID,
        email: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE users SET email_verified_at = now() WHERE id = ?").use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        }
        resolver.bindNewlyVerifiedAccount(userId, email.trim().lowercase())
    }

    /** A push device, so the push gate would let a notification through (the outbox shows what reached them). */
    fun registerPushDevice(userId: UUID) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("INSERT INTO push_devices (id, user_id, platform, device_token) VALUES (?, ?, 'android', ?)")
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, userId)
                    statement.setString(3, "test-device-$userId")
                    statement.executeUpdate()
                }
        }
    }

    fun outboxCount(
        recipient: UUID,
        actor: UUID,
    ): Int = count("SELECT COUNT(*) FROM notification_outbox WHERE recipient_user_id = ? AND actor_user_id = ?", recipient, actor)

    fun count(
        sql: String,
        vararg params: Any?,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun execute(
        sql: String,
        vararg params: Any?,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }
}
