package com.pynapli.server.admin

import com.pynapli.server.moderation.ModerationException
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AdminPartnerLinkReviewServiceTest {
    private val userId = UUID.randomUUID()
    private val activePartnerId = UUID.randomUUID()
    private val pastPartnerId = UUID.randomUUID()
    private val clock = Clock.fixed(Instant.parse("2026-06-16T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun mapsCurrentHistoryCountsAndAuditsTheReview() =
        runBlocking {
            val repository =
                RecordingPartnerLinkReviewRepository(
                    facts =
                        AdminPartnerLinkReviewFacts(
                            partnerVisibility = "matches_only",
                            links =
                                listOf(
                                    linkFact(
                                        otherUserId = activePartnerId,
                                        status = "active",
                                        createdAt = "2026-06-01T00:00:00Z",
                                        acceptedAt = "2026-06-01T01:00:00Z",
                                    ),
                                    linkFact(
                                        otherUserId = pastPartnerId,
                                        status = "unlinked",
                                        createdAt = "2026-05-20T00:00:00Z",
                                        acceptedAt = "2026-05-20T01:00:00Z",
                                        unlinkedAt = "2026-06-10T00:00:00Z",
                                    ),
                                ),
                            reportsAgainstUserCount = 2,
                            reportsAgainstCurrentPartnerCount = 1,
                        ),
                )
            val service = AdminPartnerLinkReviewService(repository = repository, clock = clock)

            val review = service.review(actorId = "ops-1", userId = userId, reason = "support review")

            assertEquals(userId.toString(), review.userId)
            assertEquals("matches_only", review.partnerVisibility)
            assertEquals(activePartnerId.toString(), review.currentLink?.otherUserId)
            assertEquals(1, review.history.size)
            assertEquals(pastPartnerId.toString(), review.history.first().otherUserId)
            assertEquals(1, review.activeLinkCount)
            assertEquals(1, review.pastLinkCount)
            assertEquals(1, review.recentUnlinkCount)
            assertEquals(2, review.reportsAgainstUserCount)
            assertEquals(1, review.reportsAgainstCurrentPartnerCount)
            assertEquals(Triple("ops-1", userId, "support review"), repository.audited)
        }

    @Test
    fun unknownUserIsNotFoundAndNotAudited() =
        runBlocking {
            val repository = RecordingPartnerLinkReviewRepository(facts = null)
            val service = AdminPartnerLinkReviewService(repository = repository, clock = clock)

            val error =
                assertFailsWith<ModerationException> {
                    service.review(actorId = "ops-1", userId = userId, reason = "support review")
                }

            assertEquals("USER_NOT_FOUND", error.code)
            assertNull(repository.audited)
        }

    private fun linkFact(
        otherUserId: UUID,
        status: String,
        createdAt: String,
        acceptedAt: String? = null,
        unlinkedAt: String? = null,
    ) = AdminPartnerLinkFact(
        linkId = UUID.randomUUID(),
        otherUserId = otherUserId,
        status = status,
        direction = "initiated",
        createdAt = Instant.parse(createdAt),
        acceptedAt = acceptedAt?.let(Instant::parse),
        unlinkedAt = unlinkedAt?.let(Instant::parse),
    )

    private class RecordingPartnerLinkReviewRepository(
        private val facts: AdminPartnerLinkReviewFacts?,
    ) : AdminPartnerLinkReviewRepository {
        var audited: Triple<String, UUID, String>? = null

        override suspend fun review(userId: UUID): AdminPartnerLinkReviewFacts? = facts

        override suspend fun recordReviewAudit(
            actorId: String,
            userId: UUID,
            reason: String,
        ) {
            audited = Triple(actorId, userId, reason)
        }
    }
}
