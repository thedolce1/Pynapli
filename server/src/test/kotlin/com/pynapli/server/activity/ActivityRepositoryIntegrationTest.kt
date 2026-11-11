package com.pynapli.server.activity

import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityRepositoryIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun recordProfileViewWritesEligibleViewInSingleUpsert() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000001001")
            val target = UUID.fromString("00000000-0000-0000-0000-000000001002")
            dataSource.insertUserWithDiscovery(viewer)
            dataSource.insertUserWithDiscovery(target)
            val repository = JdbcActivityRepository(dataSource)

            assertTrue(repository.recordProfileView(viewer, target))
            assertTrue(repository.recordProfileView(viewer, target))

            val event = dataSource.singleProfileViewEvent()
            assertEquals(target, event.recipientUserId)
            assertEquals(viewer, event.actorUserId)
            assertEquals(target, event.targetUserId)
            assertEquals(1, event.aggregateCount)
        }

    @Test
    fun recordProfileViewSuppressesIneligibleViewersAndTargets() =
        runBlocking {
            val target = UUID.fromString("00000000-0000-0000-0000-000000001101")
            val incognitoViewer = UUID.fromString("00000000-0000-0000-0000-000000001102")
            val hiddenFromViewedLists = UUID.fromString("00000000-0000-0000-0000-000000001103")
            val quarantinedViewer = UUID.fromString("00000000-0000-0000-0000-000000001104")
            val inactiveTarget = UUID.fromString("00000000-0000-0000-0000-000000001105")
            val activeViewer = UUID.fromString("00000000-0000-0000-0000-000000001106")
            val optedOutTarget = UUID.fromString("00000000-0000-0000-0000-000000001107")
            dataSource.insertUserWithDiscovery(target)
            dataSource.insertUserWithDiscovery(incognitoViewer, incognito = true)
            dataSource.insertUserWithDiscovery(hiddenFromViewedLists, showInViewedLists = false)
            dataSource.insertUserWithDiscovery(quarantinedViewer)
            dataSource.insertUserWithDiscovery(inactiveTarget, status = "paused")
            dataSource.insertUserWithDiscovery(activeViewer)
            dataSource.insertUserWithDiscovery(optedOutTarget, showInViewedLists = false)
            dataSource.setRiskState(quarantinedViewer, score = 75, tier = "quarantine")
            val repository = JdbcActivityRepository(dataSource)

            assertFalse(repository.recordProfileView(incognitoViewer, target))
            assertFalse(repository.recordProfileView(hiddenFromViewedLists, target))
            assertFalse(repository.recordProfileView(quarantinedViewer, target))
            assertFalse(repository.recordProfileView(activeViewer, inactiveTarget))
            assertFalse(repository.recordProfileView(activeViewer, optedOutTarget))

            assertEquals(0, dataSource.profileViewEventCount())
        }

    @Test
    fun listActivityAppliesTabFilterAndCursorPagination() =
        runBlocking {
            val recipient = UUID.fromString("00000000-0000-0000-0000-000000001201")
            val newestFlirtActor = UUID.fromString("00000000-0000-0000-0000-000000001202")
            val olderFlirtActor = UUID.fromString("00000000-0000-0000-0000-000000001203")
            val viewActor = UUID.fromString("00000000-0000-0000-0000-000000001204")
            dataSource.insertUserWithDiscovery(recipient, displayName = "Recipient")
            dataSource.insertUserWithDiscovery(newestFlirtActor, displayName = "Newest Flirt")
            dataSource.insertUserWithDiscovery(olderFlirtActor, displayName = "Older Flirt")
            dataSource.insertUserWithDiscovery(viewActor, displayName = "Viewer")
            val newestFlirtId = UUID.fromString("00000000-0000-0000-0000-000000001301")
            val olderFlirtId = UUID.fromString("00000000-0000-0000-0000-000000001302")
            dataSource.insertActivityEvent(
                eventId = olderFlirtId,
                recipientUserId = recipient,
                actorUserId = olderFlirtActor,
                eventType = "flirt",
                occurredAt = Instant.parse("2026-05-18T10:00:00Z"),
            )
            dataSource.insertActivityEvent(
                eventId = UUID.fromString("00000000-0000-0000-0000-000000001303"),
                recipientUserId = recipient,
                actorUserId = viewActor,
                eventType = "profile_view",
                occurredAt = Instant.parse("2026-05-18T10:30:00Z"),
            )
            dataSource.insertActivityEvent(
                eventId = newestFlirtId,
                recipientUserId = recipient,
                actorUserId = newestFlirtActor,
                eventType = "flirt",
                occurredAt = Instant.parse("2026-05-18T11:00:00Z"),
            )
            val repository = JdbcActivityRepository(dataSource)

            val firstPage =
                repository.listActivity(
                    ActivityFeedQuery(
                        recipientUserId = recipient,
                        eventTypes = setOf("flirt"),
                        limit = 1,
                        cursor = null,
                    ),
                )
            assertEquals(listOf(newestFlirtId), firstPage.map { it.eventId })
            assertEquals(listOf("flirt"), firstPage.map { it.type })

            val secondPage =
                repository.listActivity(
                    ActivityFeedQuery(
                        recipientUserId = recipient,
                        eventTypes = setOf("flirt"),
                        limit = 2,
                        cursor = firstPage.single().toCursorAnchor(seenCount = 1),
                    ),
                )
            assertEquals(listOf(olderFlirtId), secondPage.map { it.eventId })
        }

    @Test
    fun dismissActivityEventSoftDeletesOnlyOwnedEvent() =
        runBlocking {
            val recipient = UUID.fromString("00000000-0000-0000-0000-000000001401")
            val otherRecipient = UUID.fromString("00000000-0000-0000-0000-000000001402")
            val actor = UUID.fromString("00000000-0000-0000-0000-000000001403")
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000001501")
            dataSource.insertUserWithDiscovery(recipient, displayName = "Recipient")
            dataSource.insertUserWithDiscovery(otherRecipient, displayName = "Other Recipient")
            dataSource.insertUserWithDiscovery(actor, displayName = "Actor")
            dataSource.insertActivityEvent(
                eventId = eventId,
                recipientUserId = recipient,
                actorUserId = actor,
                eventType = "flirt",
                occurredAt = Instant.parse("2026-05-18T12:00:00Z"),
            )
            val repository = JdbcActivityRepository(dataSource)

            assertFalse(repository.dismissActivityEvent(otherRecipient, eventId))
            assertEquals(1, repository.listActivity(ActivityFeedQuery(recipient, emptySet(), 10, null)).size)

            assertTrue(repository.dismissActivityEvent(recipient, eventId))
            assertEquals(0, repository.listActivity(ActivityFeedQuery(recipient, emptySet(), 10, null)).size)
        }

    @Test
    fun listActivityExcludesBlockedRelationshipsInSqlFeed() =
        runBlocking {
            val recipient = UUID.fromString("00000000-0000-0000-0000-000000001601")
            val blockedActor = UUID.fromString("00000000-0000-0000-0000-000000001602")
            val visibleActor = UUID.fromString("00000000-0000-0000-0000-000000001603")
            dataSource.insertUserWithDiscovery(recipient, displayName = "Recipient")
            dataSource.insertUserWithDiscovery(blockedActor, displayName = "Blocked Actor")
            dataSource.insertUserWithDiscovery(visibleActor, displayName = "Visible Actor")
            dataSource.insertActivityEvent(
                eventId = UUID.fromString("00000000-0000-0000-0000-000000001701"),
                recipientUserId = recipient,
                actorUserId = blockedActor,
                eventType = "flirt",
                occurredAt = Instant.parse("2026-05-18T13:00:00Z"),
            )
            val visibleEventId = UUID.fromString("00000000-0000-0000-0000-000000001702")
            dataSource.insertActivityEvent(
                eventId = visibleEventId,
                recipientUserId = recipient,
                actorUserId = visibleActor,
                eventType = "flirt",
                occurredAt = Instant.parse("2026-05-18T12:00:00Z"),
            )
            dataSource.insertBlock(recipient, blockedActor)
            val repository = JdbcActivityRepository(dataSource)

            val feed = repository.listActivity(ActivityFeedQuery(recipient, emptySet(), 10, null))

            assertEquals(listOf(visibleEventId), feed.map { it.eventId })
        }

    @Test
    fun listActivityDisablesOwnViewedListWhenRecipientOptsOut() =
        runBlocking {
            val recipient = UUID.fromString("00000000-0000-0000-0000-000000001611")
            val viewActor = UUID.fromString("00000000-0000-0000-0000-000000001612")
            val flirtActor = UUID.fromString("00000000-0000-0000-0000-000000001613")
            dataSource.insertUserWithDiscovery(recipient, displayName = "Recipient", showInViewedLists = false)
            dataSource.insertUserWithDiscovery(viewActor, displayName = "Viewer")
            dataSource.insertUserWithDiscovery(flirtActor, displayName = "Flirt")
            val viewEventId = UUID.fromString("00000000-0000-0000-0000-000000001614")
            val flirtEventId = UUID.fromString("00000000-0000-0000-0000-000000001615")
            dataSource.insertActivityEvent(
                eventId = viewEventId,
                recipientUserId = recipient,
                actorUserId = viewActor,
                eventType = "profile_view",
                occurredAt = Instant.parse("2026-05-18T13:30:00Z"),
            )
            dataSource.insertActivityEvent(
                eventId = flirtEventId,
                recipientUserId = recipient,
                actorUserId = flirtActor,
                eventType = "flirt",
                occurredAt = Instant.parse("2026-05-18T13:31:00Z"),
            )
            val repository = JdbcActivityRepository(dataSource)

            val allFeed = repository.listActivity(ActivityFeedQuery(recipient, emptySet(), 10, null))
            val viewsFeed = repository.listActivity(ActivityFeedQuery(recipient, setOf("profile_view"), 10, null))
            val summary = repository.getActivitySummary(recipient)

            assertEquals(listOf(flirtEventId), allFeed.map { it.eventId })
            assertEquals(emptyList(), viewsFeed)
            assertEquals(0, summary.profileViewCount)
            assertEquals(1, summary.badgeCount)
        }

    @Test
    fun getActivitySummaryCountsMeaningfulBadgeEventsAndFiltersNoise() =
        runBlocking {
            val recipient = UUID.fromString("00000000-0000-0000-0000-000000001621")
            val viewActor = UUID.fromString("00000000-0000-0000-0000-000000001622")
            val flirtActor = UUID.fromString("00000000-0000-0000-0000-000000001623")
            val likeActor = UUID.fromString("00000000-0000-0000-0000-000000001624")
            val introActor = UUID.fromString("00000000-0000-0000-0000-000000001625")
            val matchActor = UUID.fromString("00000000-0000-0000-0000-000000001626")
            val pendingAlbumActor = UUID.fromString("00000000-0000-0000-0000-000000001627")
            val approvedAlbumActor = UUID.fromString("00000000-0000-0000-0000-000000001628")
            val inviteActor = UUID.fromString("00000000-0000-0000-0000-000000001629")
            val blockedActor = UUID.fromString("00000000-0000-0000-0000-000000001630")
            val quarantinedActor = UUID.fromString("00000000-0000-0000-0000-000000001631")
            listOf(
                recipient,
                viewActor,
                flirtActor,
                likeActor,
                introActor,
                matchActor,
                pendingAlbumActor,
                approvedAlbumActor,
                inviteActor,
                blockedActor,
                quarantinedActor,
            ).forEach { userId -> dataSource.insertUserWithDiscovery(userId) }
            dataSource.insertBlock(recipient, blockedActor)
            dataSource.setRiskState(quarantinedActor, score = 75, tier = "quarantine")
            dataSource.insertPrivateAlbumAccessRequest(recipient, pendingAlbumActor, "pending")
            dataSource.insertPrivateAlbumAccessRequest(recipient, approvedAlbumActor, "approved")
            val eventRows =
                listOf(
                    Triple(viewActor, "profile_view", Instant.parse("2026-05-18T14:00:00Z")),
                    Triple(flirtActor, "flirt", Instant.parse("2026-05-18T14:01:00Z")),
                    Triple(likeActor, "swipe_like", Instant.parse("2026-05-18T14:02:00Z")),
                    Triple(introActor, "intro_note", Instant.parse("2026-05-18T14:03:00Z")),
                    Triple(matchActor, "match", Instant.parse("2026-05-18T14:04:00Z")),
                    Triple(pendingAlbumActor, "album_request", Instant.parse("2026-05-18T14:05:00Z")),
                    Triple(inviteActor, "partner_chat_invite", Instant.parse("2026-05-18T14:06:00Z")),
                    Triple(approvedAlbumActor, "album_request", Instant.parse("2026-05-18T14:07:00Z")),
                    Triple(blockedActor, "flirt", Instant.parse("2026-05-18T14:08:00Z")),
                    Triple(quarantinedActor, "match", Instant.parse("2026-05-18T14:09:00Z")),
                )
            eventRows.forEachIndexed { index, row ->
                dataSource.insertActivityEvent(
                    eventId = UUID.fromString("00000000-0000-0000-0000-0000000017${index.toString().padStart(2, '0')}"),
                    recipientUserId = recipient,
                    actorUserId = row.first,
                    eventType = row.second,
                    occurredAt = row.third,
                )
            }
            val repository = JdbcActivityRepository(dataSource)

            val summary = repository.getActivitySummary(recipient)

            assertEquals(1, summary.profileViewCount)
            assertEquals(1, summary.flirtCount)
            assertEquals(2, summary.likeCount)
            assertEquals(1, summary.matchCount)
            assertEquals(1, summary.albumRequestCount)
            assertEquals(1, summary.partnerInviteCount)
            assertEquals(6, summary.badgeCount)
            assertEquals(Instant.parse("2026-05-18T14:06:00Z"), summary.latestBadgeOccurredAt)
        }

    @Test
    fun markActivityEventsSeenClearsOnlyOwnedUnreadBadgeRows() =
        runBlocking {
            val recipient = UUID.fromString("00000000-0000-0000-0000-000000001641")
            val otherRecipient = UUID.fromString("00000000-0000-0000-0000-000000001642")
            val firstActor = UUID.fromString("00000000-0000-0000-0000-000000001643")
            val secondActor = UUID.fromString("00000000-0000-0000-0000-000000001644")
            val otherActor = UUID.fromString("00000000-0000-0000-0000-000000001645")
            listOf(recipient, otherRecipient, firstActor, secondActor, otherActor)
                .forEach { userId -> dataSource.insertUserWithDiscovery(userId) }
            val firstEventId = UUID.fromString("00000000-0000-0000-0000-000000001646")
            val secondEventId = UUID.fromString("00000000-0000-0000-0000-000000001647")
            val otherEventId = UUID.fromString("00000000-0000-0000-0000-000000001648")
            dataSource.insertActivityEvent(
                eventId = firstEventId,
                recipientUserId = recipient,
                actorUserId = firstActor,
                eventType = "flirt",
                occurredAt = Instant.parse("2026-05-18T15:00:00Z"),
            )
            dataSource.insertActivityEvent(
                eventId = secondEventId,
                recipientUserId = recipient,
                actorUserId = secondActor,
                eventType = "match",
                occurredAt = Instant.parse("2026-05-18T15:01:00Z"),
            )
            dataSource.insertActivityEvent(
                eventId = otherEventId,
                recipientUserId = otherRecipient,
                actorUserId = otherActor,
                eventType = "flirt",
                occurredAt = Instant.parse("2026-05-18T15:02:00Z"),
            )
            val repository = JdbcActivityRepository(dataSource)

            val markedCount = repository.markActivityEventsSeen(recipient, setOf(firstEventId, otherEventId))
            val feed = repository.listActivity(ActivityFeedQuery(recipient, emptySet(), 10, null))
            val recipientSummary = repository.getActivitySummary(recipient)
            val otherSummary = repository.getActivitySummary(otherRecipient)

            assertEquals(1, markedCount)
            assertEquals(true, feed.single { it.eventId == firstEventId }.seen)
            assertEquals(false, feed.single { it.eventId == secondEventId }.seen)
            assertEquals(0, recipientSummary.flirtCount)
            assertEquals(1, recipientSummary.matchCount)
            assertEquals(1, recipientSummary.badgeCount)
            assertEquals(1, otherSummary.flirtCount)
            assertEquals(1, otherSummary.badgeCount)
        }

    @Test
    fun recordsSwipeMatchAndPartnerInviteEventsIdempotently() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000001801")
            val target = UUID.fromString("00000000-0000-0000-0000-000000001802")
            val matchId = UUID.fromString("00000000-0000-0000-0000-000000001803")
            val conversationId = UUID.fromString("00000000-0000-0000-0000-000000001804")
            dataSource.insertUserWithDiscovery(viewer, displayName = "Viewer")
            dataSource.insertUserWithDiscovery(target, displayName = "Target")
            val repository = JdbcActivityRepository(dataSource)

            assertTrue(repository.recordSwipeInterest(viewer, target, "swipe_like"))
            assertFalse(repository.recordSwipeInterest(viewer, target, "swipe_like"))
            assertTrue(repository.recordSwipeInterest(viewer, target, "intro_note"))
            assertTrue(repository.recordMatch(recipientUserId = viewer, actorUserId = target, matchId = matchId))
            assertFalse(repository.recordMatch(recipientUserId = viewer, actorUserId = target, matchId = matchId))
            assertTrue(repository.recordPartnerChatInvite(viewer, target, conversationId))
            assertFalse(repository.recordPartnerChatInvite(viewer, target, conversationId))

            assertEquals(1, dataSource.eventCount("swipe_like"))
            assertEquals(1, dataSource.eventCount("intro_note"))
            assertEquals(1, dataSource.eventCount("match"))
            assertEquals(1, dataSource.eventCount("partner_chat_invite"))
            val matchEvent = repository.listActivity(ActivityFeedQuery(viewer, setOf("match"), 10, null)).single()
            val inviteEvent = repository.listActivity(ActivityFeedQuery(target, setOf("partner_chat_invite"), 10, null)).single()
            assertEquals(matchId.toString(), matchEvent.matchId)
            assertEquals(conversationId.toString(), inviteEvent.conversationId)
        }

    @Test
    fun albumGrantedKeepsOneCardPerOwnerAndResurfacesOnReGrant() =
        runBlocking {
            val owner = UUID.fromString("00000000-0000-0000-0000-000000001851")
            val requester = UUID.fromString("00000000-0000-0000-0000-000000001852")
            dataSource.insertUserWithDiscovery(owner, displayName = "Owner")
            dataSource.insertUserWithDiscovery(requester, displayName = "Requester")
            val repository = JdbcActivityRepository(dataSource)

            assertTrue(repository.recordAlbumGranted(owner, requester))
            val first = repository.listActivity(ActivityFeedQuery(requester, setOf("album_granted"), 10, null)).single()
            repository.markActivityEventsSeen(requester, setOf(first.eventId))

            // Granted again later (after a revoke): the same card comes back as unseen.
            assertTrue(repository.recordAlbumGranted(owner, requester))
            assertEquals(1, dataSource.eventCount("album_granted"))
            val again = repository.listActivity(ActivityFeedQuery(requester, setOf("album_granted"), 10, null)).single()
            assertEquals(owner, again.actorUserId)
            assertFalse(again.seen)
            assertFalse(repository.recordAlbumGranted(owner, owner))
        }

    @Test
    fun recordFlirtRoundTripsPlayingTonightSourceIntoFeed() =
        runBlocking {
            val taggedSender = UUID.fromString("00000000-0000-0000-0000-000000002101")
            val untaggedSender = UUID.fromString("00000000-0000-0000-0000-000000002102")
            val unknownSourceSender = UUID.fromString("00000000-0000-0000-0000-000000002103")
            val recipient = UUID.fromString("00000000-0000-0000-0000-000000002104")
            listOf(taggedSender, untaggedSender, unknownSourceSender, recipient)
                .forEach { userId -> dataSource.insertUserWithDiscovery(userId) }
            val repository = JdbcActivityRepository(dataSource)

            // The service normalizes the source before reaching the repository, so the repository
            // only ever sees "playing_tonight" or null. Passing a stray value here proves the
            // metadata->>'source' read simply surfaces whatever was persisted (null when absent).
            assertTrue(repository.recordFlirt(taggedSender, recipient, "playing_tonight"))
            assertTrue(repository.recordFlirt(untaggedSender, recipient, null))
            assertTrue(repository.recordFlirt(unknownSourceSender, recipient, null))

            val feed = repository.listActivity(ActivityFeedQuery(recipient, setOf("flirt"), 10, null))
            val bySender = feed.associateBy { it.actorUserId }

            assertEquals("playing_tonight", bySender.getValue(taggedSender).source)
            assertEquals(null, bySender.getValue(untaggedSender).source)
            assertEquals(null, bySender.getValue(unknownSourceSender).source)
        }

    @Test
    fun retentionHidesExpiredProductVisibleActivityButKeepsMatches() =
        runBlocking {
            val recipient = UUID.fromString("00000000-0000-0000-0000-000000001901")
            val viewActor = UUID.fromString("00000000-0000-0000-0000-000000001902")
            val likeActor = UUID.fromString("00000000-0000-0000-0000-000000001903")
            val matchActor = UUID.fromString("00000000-0000-0000-0000-000000001904")
            val flirtActor = UUID.fromString("00000000-0000-0000-0000-000000001905")
            dataSource.insertUserWithDiscovery(recipient)
            dataSource.insertUserWithDiscovery(viewActor)
            dataSource.insertUserWithDiscovery(likeActor)
            dataSource.insertUserWithDiscovery(matchActor)
            dataSource.insertUserWithDiscovery(flirtActor)
            val oldViewId = UUID.fromString("00000000-0000-0000-0000-000000001911")
            val oldLikeId = UUID.fromString("00000000-0000-0000-0000-000000001912")
            val oldMatchId = UUID.fromString("00000000-0000-0000-0000-000000001913")
            val recentFlirtId = UUID.fromString("00000000-0000-0000-0000-000000001914")
            dataSource.insertActivityEvent(oldViewId, recipient, viewActor, "profile_view", Instant.parse("2026-04-01T12:00:00Z"))
            dataSource.insertActivityEvent(oldLikeId, recipient, likeActor, "swipe_like", Instant.parse("2026-01-01T12:00:00Z"))
            dataSource.insertActivityEvent(oldMatchId, recipient, matchActor, "match", Instant.parse("2026-01-01T12:00:00Z"))
            dataSource.insertActivityEvent(recentFlirtId, recipient, flirtActor, "flirt", Instant.parse("2026-05-10T12:00:00Z"))
            val repository = JdbcActivityRetentionRepository(dataSource)

            val summary = repository.hideExpiredProductVisibleEvents(Instant.parse("2026-05-20T12:00:00Z"))

            assertEquals(2, summary.hiddenTotal)
            assertEquals(mapOf("profile_view" to 1, "swipe_like" to 1), summary.hiddenByType)
            assertEquals("hidden", dataSource.activityEventVisibility(oldViewId))
            assertEquals("hidden", dataSource.activityEventVisibility(oldLikeId))
            assertEquals("visible", dataSource.activityEventVisibility(oldMatchId))
            assertEquals("visible", dataSource.activityEventVisibility(recentFlirtId))
        }

    private fun DataSource.insertUserWithDiscovery(
        userId: UUID,
        status: String = "active",
        showInViewedLists: Boolean = true,
        incognito: Boolean = false,
        displayName: String = "User ${userId.toString().takeLast(4)}",
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO users (id, status)
                    VALUES (?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, status)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO discovery_settings (
                        user_id,
                        incognito,
                        show_in_viewed_lists
                    )
                    VALUES (?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setBoolean(2, incognito)
                    statement.setBoolean(3, showInViewedLists)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO profiles (
                        user_id,
                        display_name,
                        moderation_status,
                        visibility_status,
                        profile_completeness_score
                    )
                    VALUES (?, ?, 'approved', 'visible', 80)
                    ON CONFLICT (user_id) DO UPDATE
                    SET display_name = EXCLUDED.display_name,
                        moderation_status = EXCLUDED.moderation_status,
                        visibility_status = EXCLUDED.visibility_status,
                        profile_completeness_score = EXCLUDED.profile_completeness_score,
                        updated_at = now()
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, displayName)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertActivityEvent(
        eventId: UUID,
        recipientUserId: UUID,
        actorUserId: UUID,
        eventType: String,
        occurredAt: Instant,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO activity_events (
                        id,
                        recipient_user_id,
                        actor_user_id,
                        event_type,
                        target_user_id,
                        aggregate_count,
                        first_occurred_at,
                        last_counted_at,
                        occurred_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    val occurredAtTimestamp = Timestamp.from(occurredAt)
                    statement.setObject(1, eventId)
                    statement.setObject(2, recipientUserId)
                    statement.setObject(3, actorUserId)
                    statement.setString(4, eventType)
                    statement.setObject(5, recipientUserId)
                    statement.setTimestamp(6, occurredAtTimestamp)
                    statement.setTimestamp(7, occurredAtTimestamp)
                    statement.setTimestamp(8, occurredAtTimestamp)
                    statement.setTimestamp(9, occurredAtTimestamp)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertPrivateAlbumAccessRequest(
        ownerUserId: UUID,
        requesterUserId: UUID,
        status: String,
    ) {
        val albumId = UUID.nameUUIDFromBytes("activity-summary-private-album:$ownerUserId".toByteArray())
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO media_albums (
                        id,
                        owner_user_id,
                        kind,
                        title,
                        visibility_status
                    )
                    VALUES (?, ?, 'private', 'Private album', 'visible')
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, albumId)
                    statement.setObject(2, ownerUserId)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO media_album_access_requests (
                        id,
                        album_id,
                        owner_user_id,
                        requester_user_id,
                        status,
                        requested_at,
                        decided_at,
                        decided_by_user_id
                    )
                    VALUES (
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        now(),
                        CASE WHEN ? = 'pending' THEN NULL ELSE now() END,
                        CASE WHEN ? = 'pending' THEN NULL ELSE ? END
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, albumId)
                    statement.setObject(3, ownerUserId)
                    statement.setObject(4, requesterUserId)
                    statement.setString(5, status)
                    statement.setString(6, status)
                    statement.setString(7, status)
                    statement.setObject(8, ownerUserId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertBlock(
        blockerUserId: UUID,
        blockedUserId: UUID,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_blocks (
                        id,
                        blocker_user_id,
                        blocked_user_id,
                        created_at
                    )
                    VALUES (?, ?, ?, now())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, blockerUserId)
                    statement.setObject(3, blockedUserId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.setRiskState(
        userId: UUID,
        score: Int,
        tier: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO account_risk_states (
                        user_id,
                        score,
                        tier,
                        last_event_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, now(), now())
                    ON CONFLICT (user_id) DO UPDATE
                    SET score = EXCLUDED.score,
                        tier = EXCLUDED.tier,
                        last_event_at = EXCLUDED.last_event_at,
                        updated_at = EXCLUDED.updated_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setInt(2, score)
                    statement.setString(3, tier)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.singleProfileViewEvent(): ProfileViewEventRow =
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT recipient_user_id, actor_user_id, target_user_id, aggregate_count
                    FROM activity_events
                    WHERE event_type = 'profile_view'
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        val row =
                            ProfileViewEventRow(
                                recipientUserId = rs.getObject("recipient_user_id", UUID::class.java),
                                actorUserId = rs.getObject("actor_user_id", UUID::class.java),
                                targetUserId = rs.getObject("target_user_id", UUID::class.java),
                                aggregateCount = rs.getInt("aggregate_count"),
                            )
                        assertFalse(rs.next())
                        row
                    }
                }
        }

    private fun DataSource.profileViewEventCount(): Int =
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT count(*)
                    FROM activity_events
                    WHERE event_type = 'profile_view'
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    private fun DataSource.eventCount(eventType: String): Int =
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT count(*)
                    FROM activity_events
                    WHERE event_type = ?
                      AND deleted_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, eventType)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    private fun DataSource.activityEventVisibility(eventId: UUID): String =
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT visibility_status
                    FROM activity_events
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, eventId)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        rs.getString("visibility_status")
                    }
                }
        }

    private data class ProfileViewEventRow(
        val recipientUserId: UUID,
        val actorUserId: UUID,
        val targetUserId: UUID,
        val aggregateCount: Int,
    )
}
