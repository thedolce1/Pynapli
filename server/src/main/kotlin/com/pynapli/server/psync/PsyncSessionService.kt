package com.pynapli.server.psync

import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.CurrentPsyncSessionDto
import com.pynapli.shared.network.dto.PsyncActionResponseDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import com.pynapli.shared.network.dto.PsyncRecapDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import com.pynapli.shared.network.dto.SuggestPsyncFiltersRequestDto
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.UUID

/**
 * Psync session product authority (PSYNC_SPEC — PS-1 negotiation lifecycle only).
 *
 * "Psync is shared discovery without shared accounts." Both linked partners must consent,
 * and re-approve after any filter change, before a session is `ready`.
 */
class PsyncSessionService(
    private val repository: PsyncSessionRepository,
    deckGenerator: PsyncDeckGenerator? = null,
    blockRepository: com.pynapli.server.safety.BlockRepository? = null,
    hiddenProfileRepository: com.pynapli.server.safety.HiddenProfileRepository? = null,
    presenceService: com.pynapli.server.presence.PresenceService? = null,
    mediaAssetUrlResolver: com.pynapli.server.media.MediaAssetUrlResolver? = null,
    travelLocationRepository: com.pynapli.server.location.TravelLocationRepository? = null,
    matchConversationOpener: com.pynapli.server.messaging.MatchConversationOpener? = null,
    activityService: com.pynapli.server.activity.ActivityService? = null,
    notificationPublisher: com.pynapli.server.notifications.NotificationPublisher? = null,
    broadcaster: PsyncBroadcaster? = null,
    rateLimitService: com.pynapli.server.safety.RateLimitService? = null,
    riskPolicyService: com.pynapli.server.safety.RiskPolicyService? = null,
    subscriptionService: com.pynapli.server.subscriptions.SubscriptionService? = null,
    // Pineapple Likes sent in a session spend inventory; null (tests, no billing) leaves them free.
    pineappleInventory: PsyncPineappleInventory? = subscriptionService?.let(::SubscriptionPsyncPineappleInventory),
    deckTokenCodec: com.pynapli.server.swipe.SwipeDeckTokenCodec =
        com.pynapli.server.swipe
            .SwipeDeckTokenCodec(),
    clock: Clock = Clock.systemUTC(),
    json: Json = Json,
) {
    private val events = PsyncSessionEvents(activityService, notificationPublisher, broadcaster, clock, matchConversationOpener)
    private val pineappleRefunds = PsyncPineappleRefunds(repository, pineappleInventory)
    private val deckCoordinator =
        PsyncDeckCoordinator(
            repository = repository,
            deckGenerator = deckGenerator,
            blockRepository = blockRepository,
            hiddenProfileRepository = hiddenProfileRepository,
            presenceService = presenceService,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            deckTokenCodec = deckTokenCodec,
            clock = clock,
        )
    private val activeSessions =
        PsyncActiveSessionActions(
            repository = repository,
            deckCoordinator = deckCoordinator,
            recapBuilder = PsyncRecapBuilder(repository),
            events = events,
            pineappleInventory = pineappleInventory,
            pineappleRefunds = pineappleRefunds,
            rateLimitService = rateLimitService,
            travelLocationRepository = travelLocationRepository,
            subscriptionService = subscriptionService,
            deckTokenCodec = deckTokenCodec,
            clock = clock,
        )
    private val lifecycle =
        PsyncLifecycleActions(
            repository = repository,
            activityService = activityService,
            riskPolicyService = riskPolicyService,
            subscriptionService = subscriptionService,
            events = events,
            pineappleRefunds = pineappleRefunds,
            resolveOverdueStarted = { activeSessions.resolveOverdue() },
            travelLocationRepository = travelLocationRepository,
            clock = clock,
            json = json,
        )

    suspend fun create(
        userId: UUID,
        request: CreatePsyncSessionRequestDto,
    ): PsyncSessionDto = lifecycle.create(userId, request)

    suspend fun accept(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = lifecycle.accept(sessionId, userId)

    suspend fun deny(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = lifecycle.deny(sessionId, userId)

    suspend fun suggestFilters(
        sessionId: UUID,
        userId: UUID,
        request: SuggestPsyncFiltersRequestDto,
    ): PsyncSessionDto = lifecycle.suggestFilters(sessionId, userId, request)

    suspend fun leave(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto {
        // Leaving before the deck exists is a plain cancel. Leaving a started session ends it for
        // both partners but keeps what each already decided (PSYNC_SPEC "Either partner ends the
        // session" -> complete).
        val session = repository.findById(sessionId, withDisplayNames = false)
        val started = session != null && session.status in PSYNC_STARTED_STATUSES && session.participants.any { it.userId == userId }
        return if (started) activeSessions.endStarted(sessionId, userId) else lifecycle.leave(sessionId, userId)
    }

    suspend fun start(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = activeSessions.start(sessionId, userId)

    suspend fun getDeck(
        sessionId: UUID,
        userId: UUID,
    ): PsyncDeckResponseDto = activeSessions.getDeck(sessionId, userId)

    suspend fun submitAction(
        sessionId: UUID,
        userId: UUID,
        request: SubmitPsyncActionRequestDto,
    ): PsyncActionResponseDto = activeSessions.submitAction(sessionId, userId, request)

    suspend fun complete(
        sessionId: UUID,
        userId: UUID,
    ): PsyncRecapDto = activeSessions.complete(sessionId, userId)

    suspend fun getRecap(
        sessionId: UUID,
        userId: UUID,
    ): PsyncRecapDto = activeSessions.getRecap(sessionId, userId)

    suspend fun pause(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = activeSessions.pause(sessionId, userId)

    suspend fun resume(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = activeSessions.resume(sessionId, userId)

    suspend fun nudge(
        sessionId: UUID,
        userId: UUID,
    ): com.pynapli.shared.network.dto.PsyncNudgeResponseDto = activeSessions.nudge(sessionId, userId)

    suspend fun get(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto = lifecycle.get(sessionId, userId)

    suspend fun current(userId: UUID): CurrentPsyncSessionDto = lifecycle.current(userId)

    suspend fun expireStale(): Int = lifecycle.expireStale()
}

class PsyncException(
    val code: String,
) : RuntimeException(code)
