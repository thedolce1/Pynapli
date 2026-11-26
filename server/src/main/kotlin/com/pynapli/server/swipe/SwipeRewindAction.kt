package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.profiling.profile
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SwipeRewindResponseDto
import java.time.Clock
import java.util.UUID

internal class SwipeRewindAction(
    private val repository: SwipeRepository,
    private val dtoMapper: SwipeDtoMapper,
    private val blockRepository: BlockRepository?,
    private val rateLimitService: RateLimitService?,
    private val riskPolicyService: RiskPolicyService?,
    private val subscriptionService: SubscriptionService?,
    private val presenceService: PresenceService?,
    private val deckTokenCodec: SwipeDeckTokenCodec,
    private val clock: Clock,
    private val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
) {
    suspend fun rewind(viewerUserId: UUID): SwipeRewindResponseDto {
        if (profile("swipe.rewind.risk") { riskPolicyService?.isSocialActionRestricted(viewerUserId) == true }) {
            throw SwipeException("RISK_RESTRICTED")
        }
        val policy =
            profile("swipe.rewind.policy") {
                subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
            }
        if (policy.tier == SubscriptionTier.FREE) {
            throw SwipeException("ENTITLEMENT_REQUIRED")
        }
        if (profile("swipe.rewind.rateLimit") {
                rateLimitService?.check(viewerUserId, RateLimitedAction.SWIPE_ACTION)?.allowed == false
            }
        ) {
            throw SwipeException("RATE_LIMITED")
        }

        val candidate =
            profile("swipe.rewind.findCandidate") {
                repository.findLatestRewindableAction(
                    viewerUserId = viewerUserId,
                    radiusMeters = SWIPE_MAX_RADIUS_METERS,
                )
            } ?: return SwipeRewindResponseDto(rewound = false)
        if (profile("swipe.rewind.blocks") {
                blockRepository?.isBlockedEitherWay(viewerUserId, candidate.targetUserId) == true
            }
        ) {
            return SwipeRewindResponseDto(rewound = false)
        }
        // "Hide from people you know": never deal back someone hiding from the viewer (a pass isn't an
        // interaction, so a pass from before the block can be the latest one), nor their partner mini.
        val hiddenFromViewerIds = contactBlockVisibility?.hiddenFrom(viewerUserId).orEmpty()
        if (candidate.targetUserId in hiddenFromViewerIds) return SwipeRewindResponseDto(rewound = false)
        val card = candidate.card.withoutPartnerIn(hiddenFromViewerIds)

        val now = clock.instant()
        val filters = DiscoveryFilters()
        val scopeHash =
            SwipeDeckTokenCodec.deckScopeHash(
                viewerUserId = viewerUserId,
                radiusMeters = SWIPE_MAX_RADIUS_METERS,
                limit = 1,
                filters = filters,
            )
        val deckSession =
            deckTokenCodec.issueDeckSession(
                viewerUserId = viewerUserId,
                scopeHash = scopeHash,
                now = now,
            )
        val cardToken =
            deckTokenCodec.issueCardToken(
                deckSessionId = deckSession.deckSessionId,
                viewerUserId = viewerUserId,
                targetUserId = candidate.targetUserId,
                scopeHash = scopeHash,
                generatedAt = deckSession.generatedAt,
                expiresAt = deckSession.expiresAt,
                position = 0,
            )
        val rewound =
            profile("swipe.rewind.record") {
                repository.rewindSwipeAction(
                    actionId = candidate.actionId,
                    actorUserId = viewerUserId,
                    targetUserId = candidate.targetUserId,
                    session =
                        deckSession.toSwipeDeckSessionRecord(
                            viewerUserId = viewerUserId,
                            scopeHash = scopeHash,
                            radiusMeters = SWIPE_MAX_RADIUS_METERS,
                            pageLimit = 1,
                            filterPayload = filters.toPersistedJson(),
                        ),
                    card =
                        SwipeDeckSessionCardRecord(
                            cardId = UUID.randomUUID(),
                            sessionId = deckSession.sessionId,
                            viewerUserId = viewerUserId,
                            targetUserId = candidate.targetUserId,
                            cardTokenHash = deckTokenCodec.tokenHash(cardToken),
                            position = 0,
                            dealtAt = now,
                        ),
                    rewoundAt = now,
                )
            }

        return if (rewound) {
            val presenceLookupIds =
                buildSet {
                    add(candidate.targetUserId)
                    card.linkedPartnerUserId?.let { add(it) }
                }
            val online =
                profile("swipe.rewind.presence") {
                    onlineSwipeUserIdsFor(presenceService, presenceLookupIds)
                }
            SwipeRewindResponseDto(
                rewound = true,
                deckSessionId = deckSession.deckSessionId,
                card =
                    dtoMapper.cardToDto(
                        card = card,
                        rankToken = cardToken,
                        online = candidate.targetUserId in online,
                        onlineUserIds = online,
                        images = dtoMapper.imagesFor(listOf(card)),
                    ),
            )
        } else {
            SwipeRewindResponseDto(rewound = false)
        }
    }
}
