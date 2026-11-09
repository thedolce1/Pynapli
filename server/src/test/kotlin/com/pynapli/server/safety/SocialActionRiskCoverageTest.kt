package com.pynapli.server.safety

import com.pynapli.server.profilelink.ProfileLinkException
import com.pynapli.server.profilelink.ProfileLinkRepository
import com.pynapli.server.profilelink.ProfileLinkService
import com.pynapli.server.psync.PsyncException
import com.pynapli.server.psync.PsyncSessionRepository
import com.pynapli.server.psync.PsyncSessionService
import com.pynapli.server.tonight.TonightSessionException
import com.pynapli.server.tonight.TonightSessionRepository
import com.pynapli.server.tonight.TonightSessionService
import com.pynapli.shared.network.dto.CreateProfileLinkInvitationRequestDto
import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Risk enforcement is service-layer by architecture (routes thin, services own
 * product rules): every outbound social mutation must reject restricted tiers
 * before touching storage. The repositories here are strict mocks with no stubbed
 * calls — reaching storage at all fails the test (TRUST_AND_SAFETY_SPEC A4).
 */
class SocialActionRiskCoverageTest {
    private val restrictedPolicy =
        RiskPolicyService(loadRiskState = { userId ->
            AccountRiskStateRecord(
                userId = userId,
                score = 75,
                tier = "quarantine",
                lastEventAt = null,
                lastReviewedAt = null,
                updatedAt = Instant.parse("2026-06-10T00:00:00Z"),
            )
        })

    @Test
    fun tonightProposeAndApproveRejectRestrictedUsersBeforeStorage() =
        runBlocking {
            val service =
                TonightSessionService(
                    repository = mockk<TonightSessionRepository>(),
                    subscriptionService = null,
                    riskPolicyService = restrictedPolicy,
                )
            val userId = UUID.randomUUID()

            val proposeError =
                runCatching {
                    service.propose(userId, ProposeTonightSessionRequestDto())
                }.exceptionOrNull()
            assertEquals("RISK_RESTRICTED", (proposeError as TonightSessionException).code)

            val approveError =
                runCatching { service.approve(UUID.randomUUID(), userId) }.exceptionOrNull()
            assertEquals("RISK_RESTRICTED", (approveError as TonightSessionException).code)
        }

    @Test
    fun psyncCreateAndAcceptRejectRestrictedUsersBeforeStorage() =
        runBlocking {
            val service =
                PsyncSessionService(
                    repository = mockk<PsyncSessionRepository>(),
                    riskPolicyService = restrictedPolicy,
                )
            val userId = UUID.randomUUID()

            val createError =
                runCatching {
                    service.create(userId, CreatePsyncSessionRequestDto())
                }.exceptionOrNull()
            assertEquals("RISK_RESTRICTED", (createError as PsyncException).code)

            val acceptError =
                runCatching { service.accept(UUID.randomUUID(), userId) }.exceptionOrNull()
            assertEquals("RISK_RESTRICTED", (acceptError as PsyncException).code)
        }

    @Test
    fun profileLinkInvitationRejectsRestrictedRequesterBeforeStorage() =
        runBlocking {
            val service =
                ProfileLinkService(
                    repository = mockk<ProfileLinkRepository>(),
                    riskPolicyService = restrictedPolicy,
                )

            val error =
                runCatching {
                    service.createInvitation(
                        UUID.randomUUID(),
                        CreateProfileLinkInvitationRequestDto(targetUserId = UUID.randomUUID().toString()),
                    )
                }.exceptionOrNull()
            assertEquals("RISK_RESTRICTED", (error as ProfileLinkException).code)
        }

    @Test
    fun cleanTierIsNotRestricted() =
        runBlocking {
            val cleanPolicy = RiskPolicyService(loadRiskState = { null })
            assertEquals(false, cleanPolicy.isSocialActionRestricted(UUID.randomUUID()))
        }
}
