package com.pynapli.server.tonight

import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationOutboxRecord
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import io.kotest.core.spec.style.StringSpec
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TonightSessionServiceIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        fun paidService(
            clock: Clock = Clock.systemUTC(),
            notificationPublisher: NotificationPublisher? = null,
            presenceService: PresenceService? = null,
        ): TonightSessionService {
            val subscriptions = mockk<SubscriptionService>()
            coEvery { subscriptions.getPolicy(any()) } returns policy(SubscriptionTier.SELECT)
            return TonightSessionService(
                repository = JdbcTonightSessionRepository(dataSource),
                subscriptionService = subscriptions,
                notificationPublisher = notificationPublisher,
                presenceService = presenceService,
                clock = clock,
            )
        }

        // Presence mock that reports exactly [online] as the live subset of whatever is queried.
        fun presence(online: Set<UUID>): PresenceService {
            val mock = mockk<PresenceService>()
            coEvery { mock.onlineUserIds(any()) } answers {
                firstArg<Collection<UUID>>().filterTo(mutableSetOf()) { it in online }
            }
            return mock
        }

        // Creates an active together session (propose by owner + partner approves) at [clock].
        fun activeTogether(
            owner: UUID,
            partner: UUID,
            clock: Clock,
        ): UUID {
            dataSource.insertUserWithProfile(owner, "Owner ${owner.toString().take(4)}")
            dataSource.insertUserWithProfile(partner, "Partner ${partner.toString().take(4)}")
            dataSource.insertActiveLink(owner, partner)
            val service = paidService(clock = clock)
            val proposed =
                runBlocking {
                    val p = service.propose(owner, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))
                    service.approve(UUID.fromString(p.id), partner)
                    p
                }
            return UUID.fromString(proposed.id)
        }

        "solo propose goes active immediately with an approved owner" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Solo Sam")
                val service = paidService()

                val session =
                    service.propose(
                        user,
                        ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"),
                    )

                assertEquals("active", session.status)
                assertNotNull(session.startedAt)
                val owner = session.participants.single()
                assertEquals(user.toString(), owner.userId)
                assertEquals("owner", owner.role)
                assertEquals("approved", owner.approvalStatus)
            }
        }

        "together propose is proposed then activates when partner approves" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Owner Olivia")
                dataSource.insertUserWithProfile(partner, "Partner Pat")
                dataSource.insertActiveLink(user, partner)
                val service = paidService()

                val proposed =
                    service.propose(
                        user,
                        ProposeTonightSessionRequestDto(mode = "together", intent = "together", durationHours = 3),
                    )
                assertEquals("proposed", proposed.status)
                assertNull(proposed.startedAt)
                val ownerRow = proposed.participants.single { it.userId == user.toString() }
                val partnerRow = proposed.participants.single { it.userId == partner.toString() }
                assertEquals("approved", ownerRow.approvalStatus)
                assertEquals("pending", partnerRow.approvalStatus)

                val sessionId = UUID.fromString(proposed.id)
                val activated = service.approve(sessionId, partner)
                assertEquals("active", activated.status)
                assertNotNull(activated.startedAt)
                assertTrue(activated.participants.all { it.approvalStatus == "approved" })
                // expires_at is reset to now + originalWindow (3h) at activation.
                val started = Instant.parse(activated.startedAt)
                val expires = Instant.parse(activated.expiresAt)
                val window = Duration.between(started, expires)
                assertTrue(window.toMinutes() in 175..185, "window was ${window.toMinutes()} minutes")
            }
        }

        "partner decline marks the session declined" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Owner")
                dataSource.insertUserWithProfile(partner, "Partner")
                dataSource.insertActiveLink(user, partner)
                val service = paidService()

                val proposed =
                    service.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))
                val declined = service.decline(UUID.fromString(proposed.id), partner)

                assertEquals("declined", declined.status)
                assertEquals(
                    "declined",
                    declined.participants.single { it.userId == partner.toString() }.approvalStatus,
                )
            }
        }

        "either participant can end the session" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Owner")
                dataSource.insertUserWithProfile(partner, "Partner")
                dataSource.insertActiveLink(user, partner)
                val service = paidService()

                val proposed =
                    service.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))
                // The partner (who has not approved) can still end it.
                val ended = service.end(UUID.fromString(proposed.id), partner)
                assertEquals("ended", ended.status)
            }
        }

        "together propose without an active partner is rejected" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Lonely")
                val service = paidService()

                val error =
                    assertFailsWith<TonightSessionException> {
                        service.propose(
                            user,
                            ProposeTonightSessionRequestDto(mode = "together", intent = "together"),
                        )
                    }
                assertEquals("TONIGHT_NO_LINKED_PARTNER", error.code)
            }
        }

        "a second propose while one is live is rejected" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Busy")
                val service = paidService()

                service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"))
                val error =
                    assertFailsWith<TonightSessionException> {
                        service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "social"))
                    }
                assertEquals("TONIGHT_ALREADY_ACTIVE", error.code)
            }
        }

        "note over 100 characters is rejected" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Wordy")
                val service = paidService()

                val error =
                    assertFailsWith<TonightSessionException> {
                        service.propose(
                            user,
                            ProposeTonightSessionRequestDto(mode = "solo", intent = "solo", note = "x".repeat(101)),
                        )
                    }
                assertEquals("TONIGHT_NOTE_TOO_LONG", error.code)
            }
        }

        "intent that does not match the mode is rejected" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Mismatch")
                val service = paidService()

                val error =
                    assertFailsWith<TonightSessionException> {
                        // solo mode cannot carry the 'together' intent.
                        service.propose(
                            user,
                            ProposeTonightSessionRequestDto(mode = "solo", intent = "together"),
                        )
                    }
                assertEquals("INVALID_TONIGHT_INTENT", error.code)
            }
        }

        "free tier weekly cap blocks the fourth session" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Free Fred")
                val subscriptions = mockk<SubscriptionService>()
                coEvery { subscriptions.getPolicy(any()) } returns policy(SubscriptionTier.FREE)
                val service =
                    TonightSessionService(
                        repository = JdbcTonightSessionRepository(dataSource),
                        subscriptionService = subscriptions,
                    )

                // Seed three non-declined sessions inside the 7-day window.
                repeat(3) { dataSource.insertEndedSessionCreatedDaysAgo(user, daysAgo = 1) }

                val error =
                    assertFailsWith<TonightSessionException> {
                        service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"))
                    }
                assertEquals("TONIGHT_WEEKLY_LIMIT_REACHED", error.code)
            }
        }

        "paid tier is not capped after three sessions" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Select Sue")
                val service = paidService()

                repeat(3) { dataSource.insertEndedSessionCreatedDaysAgo(user, daysAgo = 1) }

                val session = service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"))
                assertEquals("active", session.status)
            }
        }

        "current returns participants with display names" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Owner Olga")
                dataSource.insertUserWithProfile(partner, "Partner Pim")
                dataSource.insertActiveLink(user, partner)
                val service = paidService()

                service.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))

                val current = service.current(user)
                assertNotNull(current.session)
                val names =
                    current.session!!
                        .participants
                        .map { it.displayName }
                        .toSet()
                assertTrue("Owner Olga" in names)
                assertTrue("Partner Pim" in names)
            }
        }

        "current does not return an expired session" {
            runBlocking {
                val now = Instant.parse("2026-06-01T20:00:00Z")
                val proposeClock = Clock.fixed(now, ZoneOffset.UTC)
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Expiring Ed")

                // Propose a 1-hour solo session at `now`.
                val service = paidService(proposeClock)
                service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo", durationHours = 1))

                // Read 2 hours later: the session is past expiry, so current() must be empty
                // even though no sweep has run.
                val laterService = paidService(Clock.fixed(now.plus(Duration.ofHours(2)), ZoneOffset.UTC))
                val current = laterService.current(user)
                assertNull(current.session)
            }
        }

        "expireStale sweeps elapsed sessions to expired" {
            runBlocking {
                val now = Instant.parse("2026-06-01T20:00:00Z")
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Sweepy Sue")
                paidService(Clock.fixed(now, ZoneOffset.UTC))
                    .propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo", durationHours = 1))

                // Sweep 2 hours later: the elapsed session is moved to 'expired', and a fresh
                // propose is allowed (the active-session guard no longer blocks it).
                val swept = paidService(Clock.fixed(now.plus(Duration.ofHours(2)), ZoneOffset.UTC)).expireStale()
                assertEquals(1, swept)

                val reproposed =
                    paidService(Clock.fixed(now.plus(Duration.ofHours(2)), ZoneOffset.UTC))
                        .propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"))
                assertEquals("active", reproposed.status)
            }
        }

        "unlinking ends a live together session, so the pair leaves the public feed" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Inviting Ivy")
                dataSource.insertUserWithProfile(partner, "Pending Pete")
                dataSource.insertActiveLink(user, partner)
                val service = paidService()
                val together = service.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))
                service.approve(UUID.fromString(together.id), partner)
                assertEquals("active", service.current(user).session?.status)

                assertTrue(
                    com.pynapli.server.profilelink
                        .JdbcProfileLinkRepository(dataSource)
                        .unlinkCurrent(user),
                )

                assertEquals(null, service.current(user).session)
                assertEquals(null, service.current(partner).session)
            }
        }

        "unlinking leaves a solo session alone" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Solo Sam")
                dataSource.insertUserWithProfile(partner, "Partner Pat")
                dataSource.insertActiveLink(user, partner)
                val service = paidService()
                service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"))

                com.pynapli.server.profilelink
                    .JdbcProfileLinkRepository(dataSource)
                    .unlinkCurrent(user)

                assertEquals("active", service.current(user).session?.status)
            }
        }

        "the proposer is told when their partner approves, declines or ends" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Inviting Ivy")
                dataSource.insertUserWithProfile(partner, "Pending Pete")
                dataSource.insertActiveLink(user, partner)
                val publisher = RecordingPublisher()
                val service = paidService(notificationPublisher = publisher)

                val first = service.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))
                service.approve(UUID.fromString(first.id), partner)
                val accepted = publisher.drafts.single { it.kind == NotificationKind.TONIGHT_ACCEPTED }
                assertEquals(user, accepted.recipientUserId)
                assertEquals("tonight", accepted.payload["target"])

                service.end(UUID.fromString(first.id), partner)
                assertEquals(user, publisher.drafts.single { it.kind == NotificationKind.TONIGHT_ENDED }.recipientUserId)

                val second = service.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))
                service.decline(UUID.fromString(second.id), partner)
                assertEquals(user, publisher.drafts.single { it.kind == NotificationKind.TONIGHT_DECLINED }.recipientUserId)
                // Nobody is pushed about their own action.
                assertTrue(publisher.drafts.none { it.recipientUserId == partner && it.kind != NotificationKind.TONIGHT_INVITE })
            }
        }

        "together propose enqueues exactly one TONIGHT_INVITE to the partner" {
            runBlocking {
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Inviting Ivy")
                dataSource.insertUserWithProfile(partner, "Pending Pete")
                dataSource.insertActiveLink(user, partner)
                val publisher = RecordingPublisher()
                val service = paidService(notificationPublisher = publisher)

                val proposed =
                    service.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))

                assertEquals(1, publisher.drafts.size)
                val draft = publisher.drafts.single()
                assertEquals(NotificationKind.TONIGHT_INVITE, draft.kind)
                assertEquals(partner, draft.recipientUserId)
                assertEquals(user, draft.actorUserId)
                assertEquals(UUID.fromString(proposed.id), draft.subjectId)
                assertEquals("tonight_invite:${proposed.id}:$partner", draft.idempotencyKey)
                assertEquals(proposed.id, draft.payload["sessionId"])
            }
        }

        "solo propose enqueues no notification" {
            runBlocking {
                val user = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Quiet Quinn")
                val publisher = RecordingPublisher()
                val service = paidService(notificationPublisher = publisher)

                service.propose(user, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"))

                assertTrue(publisher.drafts.isEmpty())
            }
        }

        "sendExpiryReminders reminds both participants once when inside the lead window" {
            runBlocking {
                // Propose+activate a 2h together session at `start`; it expires at start+2h.
                val start = Instant.parse("2026-06-01T20:00:00Z")
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Owner Otto")
                dataSource.insertUserWithProfile(partner, "Partner Peg")
                dataSource.insertActiveLink(user, partner)

                val proposePublisher = RecordingPublisher()
                val proposeService =
                    paidService(
                        clock = Clock.fixed(start, ZoneOffset.UTC),
                        notificationPublisher = proposePublisher,
                    )
                val proposed =
                    proposeService.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))
                proposeService.approve(UUID.fromString(proposed.id), partner)
                val sessionId = UUID.fromString(proposed.id)

                // 10 minutes before expiry (start + 1h50m) -> inside the 15-minute lead window.
                val reminderClock = Clock.fixed(start.plus(Duration.ofMinutes(110)), ZoneOffset.UTC)
                val reminderPublisher = RecordingPublisher()
                val reminderService =
                    paidService(clock = reminderClock, notificationPublisher = reminderPublisher)

                val reminded = reminderService.sendExpiryReminders()
                assertEquals(1, reminded)

                val reminderDrafts = reminderPublisher.drafts.filter { it.kind == NotificationKind.TONIGHT_REMINDER }
                assertEquals(2, reminderDrafts.size)
                assertEquals(setOf(user, partner), reminderDrafts.map { it.recipientUserId }.toSet())
                reminderDrafts.forEach { draft ->
                    assertNull(draft.actorUserId)
                    assertEquals(sessionId, draft.subjectId)
                    assertEquals(
                        "tonight_reminder:$sessionId:${draft.recipientUserId}",
                        draft.idempotencyKey,
                    )
                }
                // reminder_sent_at is now stamped.
                assertNotNull(dataSource.reminderSentAt(sessionId))

                // A second call is idempotent: the latch suppresses any further reminder.
                val secondPublisher = RecordingPublisher()
                val secondReminded =
                    paidService(clock = reminderClock, notificationPublisher = secondPublisher)
                        .sendExpiryReminders()
                assertEquals(0, secondReminded)
                assertTrue(secondPublisher.drafts.isEmpty())
            }
        }

        "sendExpiryReminders does not remind a session expiring beyond the lead window" {
            runBlocking {
                val start = Instant.parse("2026-06-01T20:00:00Z")
                val user = UUID.randomUUID()
                val partner = UUID.randomUUID()
                dataSource.insertUserWithProfile(user, "Owner Ola")
                dataSource.insertUserWithProfile(partner, "Partner Per")
                dataSource.insertActiveLink(user, partner)

                val proposeService = paidService(clock = Clock.fixed(start, ZoneOffset.UTC))
                val proposed =
                    proposeService.propose(user, ProposeTonightSessionRequestDto(mode = "together", intent = "together"))
                proposeService.approve(UUID.fromString(proposed.id), partner)
                val sessionId = UUID.fromString(proposed.id)

                // 1 hour before expiry (start + 1h) -> outside the 15-minute lead window.
                val earlyClock = Clock.fixed(start.plus(Duration.ofHours(1)), ZoneOffset.UTC)
                val publisher = RecordingPublisher()
                val reminded =
                    paidService(clock = earlyClock, notificationPublisher = publisher).sendExpiryReminders()

                assertEquals(0, reminded)
                assertTrue(publisher.drafts.isEmpty())
                assertNull(dataSource.reminderSentAt(sessionId))
            }
        }

        "reconcile pauses a together session when one participant is offline" {
            runBlocking {
                val now = Instant.parse("2026-06-01T20:00:00Z")
                val clock = Clock.fixed(now, ZoneOffset.UTC)
                val owner = UUID.randomUUID()
                val partner = UUID.randomUUID()
                val sessionId = activeTogether(owner, partner, clock)
                assertNull(dataSource.pausedAt(sessionId))

                // Owner online, partner offline.
                val service = paidService(clock = clock, presenceService = presence(online = setOf(owner)))
                val summary = service.reconcilePresence()

                assertEquals(1, summary.paused)
                assertEquals(0, summary.resumed)
                assertEquals(0, summary.ended)
                assertNotNull(dataSource.pausedAt(sessionId))
                // Still 'active' (paused is a timestamp, not a status) and current() still returns it.
                assertEquals("active", dataSource.statusOf(sessionId))
                assertNotNull(service.current(owner).session)
            }
        }

        "reconcile resumes a paused session when both participants are back online" {
            runBlocking {
                val now = Instant.parse("2026-06-01T20:00:00Z")
                val clock = Clock.fixed(now, ZoneOffset.UTC)
                val owner = UUID.randomUUID()
                val partner = UUID.randomUUID()
                val sessionId = activeTogether(owner, partner, clock)

                // First pass pauses (partner offline)...
                paidService(clock = clock, presenceService = presence(online = setOf(owner))).reconcilePresence()
                assertNotNull(dataSource.pausedAt(sessionId))

                // ...both back online -> resume.
                val summary =
                    paidService(clock = clock, presenceService = presence(online = setOf(owner, partner)))
                        .reconcilePresence()

                assertEquals(0, summary.paused)
                assertEquals(1, summary.resumed)
                assertEquals(0, summary.ended)
                assertNull(dataSource.pausedAt(sessionId))
                assertEquals("active", dataSource.statusOf(sessionId))
            }
        }

        "reconcile ends a session still offline past the grace window" {
            runBlocking {
                val start = Instant.parse("2026-06-01T20:00:00Z")
                val owner = UUID.randomUUID()
                val partner = UUID.randomUUID()
                val sessionId = activeTogether(owner, partner, Clock.fixed(start, ZoneOffset.UTC))

                // Pause at start (partner offline).
                paidService(clock = Clock.fixed(start, ZoneOffset.UTC), presenceService = presence(online = setOf(owner)))
                    .reconcilePresence()
                assertNotNull(dataSource.pausedAt(sessionId))

                // 6 minutes later, still offline -> past the 5-minute grace -> end.
                val later = Clock.fixed(start.plus(Duration.ofMinutes(6)), ZoneOffset.UTC)
                val summary =
                    paidService(clock = later, presenceService = presence(online = setOf(owner))).reconcilePresence()

                assertEquals(0, summary.paused)
                assertEquals(0, summary.resumed)
                assertEquals(1, summary.ended)
                assertEquals("ended", dataSource.statusOf(sessionId))
            }
        }

        "reconcile does not pause a paused session within the grace window" {
            runBlocking {
                val start = Instant.parse("2026-06-01T20:00:00Z")
                val owner = UUID.randomUUID()
                val partner = UUID.randomUUID()
                val sessionId = activeTogether(owner, partner, Clock.fixed(start, ZoneOffset.UTC))

                paidService(clock = Clock.fixed(start, ZoneOffset.UTC), presenceService = presence(online = setOf(owner)))
                    .reconcilePresence()
                val firstPausedAt = dataSource.pausedAt(sessionId)
                assertNotNull(firstPausedAt)

                // 2 minutes later, still offline but inside grace -> no change.
                val later = Clock.fixed(start.plus(Duration.ofMinutes(2)), ZoneOffset.UTC)
                val summary =
                    paidService(clock = later, presenceService = presence(online = setOf(owner))).reconcilePresence()

                assertEquals(0, summary.paused)
                assertEquals(0, summary.resumed)
                assertEquals(0, summary.ended)
                assertEquals("active", dataSource.statusOf(sessionId))
                // paused_at is unchanged (grace measured from the original pause).
                assertEquals(firstPausedAt, dataSource.pausedAt(sessionId))
            }
        }

        "reconcile leaves a solo session untouched even when the owner is offline" {
            runBlocking {
                val now = Instant.parse("2026-06-01T20:00:00Z")
                val clock = Clock.fixed(now, ZoneOffset.UTC)
                val owner = UUID.randomUUID()
                dataSource.insertUserWithProfile(owner, "Solo Sol")
                val proposeService = paidService(clock = clock)
                val solo =
                    proposeService.propose(owner, ProposeTonightSessionRequestDto(mode = "solo", intent = "solo"))
                val sessionId = UUID.fromString(solo.id)

                // Owner offline; solo sessions are out of scope for presence pause/end.
                val summary =
                    paidService(clock = clock, presenceService = presence(online = emptySet())).reconcilePresence()

                assertEquals(0, summary.paused)
                assertEquals(0, summary.resumed)
                assertEquals(0, summary.ended)
                assertNull(dataSource.pausedAt(sessionId))
                assertEquals("active", dataSource.statusOf(sessionId))
            }
        }

        "reconcile with no presence service is a no-op" {
            runBlocking {
                val now = Instant.parse("2026-06-01T20:00:00Z")
                val clock = Clock.fixed(now, ZoneOffset.UTC)
                val owner = UUID.randomUUID()
                val partner = UUID.randomUUID()
                val sessionId = activeTogether(owner, partner, clock)

                val summary = paidService(clock = clock, presenceService = null).reconcilePresence()

                assertEquals(0, summary.paused)
                assertEquals(0, summary.resumed)
                assertEquals(0, summary.ended)
                assertNull(dataSource.pausedAt(sessionId))
                assertEquals("active", dataSource.statusOf(sessionId))
            }
        }
    })

private class RecordingPublisher : NotificationPublisher {
    val drafts = mutableListOf<NotificationEventDraft>()

    override suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? {
        drafts.add(notification)
        return null
    }
}

private fun DataSource.reminderSentAt(sessionId: UUID): Instant? =
    connection.use { connection ->
        connection
            .prepareStatement("SELECT reminder_sent_at FROM tonight_sessions WHERE id = ?")
            .use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getTimestamp("reminder_sent_at")?.toInstant()
                }
            }
    }

private fun DataSource.pausedAt(sessionId: UUID): Instant? =
    connection.use { connection ->
        connection
            .prepareStatement("SELECT paused_at FROM tonight_sessions WHERE id = ?")
            .use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getTimestamp("paused_at")?.toInstant()
                }
            }
    }

private fun DataSource.statusOf(sessionId: UUID): String? =
    connection.use { connection ->
        connection
            .prepareStatement("SELECT status FROM tonight_sessions WHERE id = ?")
            .use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getString("status")
                }
            }
    }

private fun policy(tier: String): EntitlementPolicy =
    EntitlementPolicy(
        tier = tier,
        adSupported = tier == SubscriptionTier.FREE,
        activityRevealLimit = if (tier == SubscriptionTier.FREE) 1 else Int.MAX_VALUE,
        advancedFiltersAllowed = tier != SubscriptionTier.FREE,
        introNoteInventoryRequired = true,
        gridPageLimit = 60,
        gridResultDepthLimit = 60,
        psyncMaxDeckSize = if (tier == SubscriptionTier.FREE) 10 else 50,
    )

private fun DataSource.insertUserWithProfile(
    userId: UUID,
    displayName: String,
) {
    connection.use { connection ->
        connection
            .prepareStatement("INSERT INTO users (id, status) VALUES (?, 'active')")
            .use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO profiles (user_id, display_name, display_age, relationship_context, moderation_status, visibility_status)
                VALUES (?, ?, 30, 'single', 'approved', 'visible')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, displayName)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertActiveLink(
    userId: UUID,
    linkedUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at)
                VALUES (?, ?, ?, 'active', now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setObject(3, linkedUserId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertEndedSessionCreatedDaysAgo(
    userId: UUID,
    daysAgo: Long,
) {
    val sessionId = UUID.randomUUID()
    val createdAt = Instant.now().minus(Duration.ofDays(daysAgo))
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO tonight_sessions (id, created_by_user_id, mode, intent, host_mode, status, created_at, started_at, expires_at, ended_at)
                VALUES (?, ?, 'solo', 'solo', 'public_meet_first', 'ended', ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.setTimestamp(3, Timestamp.from(createdAt))
                statement.setTimestamp(4, Timestamp.from(createdAt))
                statement.setTimestamp(5, Timestamp.from(createdAt.plus(Duration.ofHours(2))))
                statement.setTimestamp(6, Timestamp.from(createdAt.plus(Duration.ofHours(2))))
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO tonight_session_participants (session_id, user_id, role, approval_status, approved_at)
                VALUES (?, ?, 'owner', 'approved', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.setTimestamp(3, Timestamp.from(createdAt))
                statement.executeUpdate()
            }
    }
}
