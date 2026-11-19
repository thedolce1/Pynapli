package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.shared.network.dto.PineappleLikeNote
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import java.util.UUID

internal data class ParsedSwipeDeckRequest(
    val radiusMeters: Int,
    val limit: Int,
    val filters: DiscoveryFilters,
    val dealbreakers: Set<String>,
)

internal object SwipeDeckRequestParser {
    fun parse(
        request: SwipeDeckRequestDto,
        minRadiusMeters: Int,
        maxRadiusMeters: Int,
        maxLimit: Int,
    ): ParsedSwipeDeckRequest {
        val dealbreakers =
            request.dealbreakers.mapTo(mutableSetOf()) { key ->
                key.trim().also { trimmed ->
                    if (trimmed !in SwipeDealbreakerKey.ALL) throw SwipeException("INVALID_SWIPE_FILTER")
                }
            }
        val filters =
            discoveryFiltersFrom(
                minimumAge = request.minimumAge,
                maximumAge = request.maximumAge,
                relationshipContexts = request.relationshipContexts,
                linkedOnly = request.linkedOnly,
                verifiedOnly = request.verifiedOnly,
                facePhotoVerifiedOnly = request.facePhotoVerifiedOnly,
                ageVerifiedOnly = request.ageVerifiedOnly,
                discoveryStyles = request.discoveryStyles,
                lifestyleTags = request.lifestyleTags,
                boundaryItems = request.boundaryItems,
                logisticsTags = request.logisticsTags,
                withPhotosOnly = request.withPhotosOnly,
                hasPrivateAlbumOnly = request.hasPrivateAlbumOnly,
                intents = request.intents,
                genders = request.genders,
                orientations = request.orientations,
                verifiedPreferred = request.verifiedPreferred,
                verifiedCouplesOnly = request.verifiedCouplesOnly,
            ) { _ -> SwipeException("INVALID_SWIPE_FILTER") }
        return ParsedSwipeDeckRequest(
            radiusMeters = request.radiusMeters.coerceIn(minRadiusMeters, maxRadiusMeters),
            limit = request.limit.coerceIn(1, maxLimit),
            filters = filters,
            dealbreakers = dealbreakers,
        )
    }
}

internal data class ParsedSwipeActionRequest(
    val targetUserId: UUID,
    val action: String,
    val noteText: String?,
    val actionId: UUID,
    val deckSessionId: String,
    val cardToken: String,
)

internal object SwipeActionRequestParser {
    private val ACTIONS =
        setOf(
            SwipeAction.LIKE,
            SwipeAction.PASS,
            SwipeAction.PINEAPPLE_LIKE,
        )

    fun parse(
        viewerUserId: UUID,
        request: SwipeActionRequestDto,
    ): ParsedSwipeActionRequest {
        val targetUserId = request.targetUserId.toUuidOrThrow()
        if (targetUserId == viewerUserId) throw SwipeException("INVALID_SWIPE_TARGET")

        val action = request.action.trim().lowercase()
        if (action !in ACTIONS) throw SwipeException("INVALID_SWIPE_ACTION")
        // Only a Pineapple Like carries a note (Intro Notes merged into it, 2026-11-17); blank is no note.
        val noteText = PineappleLikeNote.normalize(request.noteText)
        if (noteText != null) {
            if (action != SwipeAction.PINEAPPLE_LIKE) throw SwipeException("INVALID_SWIPE_ACTION")
            if (noteText.length > PineappleLikeNote.MAX_LENGTH) throw SwipeException("PINEAPPLE_LIKE_NOTE_TOO_LONG")
        }

        val deckSessionId = request.deckSessionId.trim()
        if (deckSessionId.isEmpty()) throw SwipeException(SwipeDeckTokenCodec.INVALID_DECK_SESSION)
        val cardToken = request.cardToken.trim()
        if (cardToken.isEmpty()) throw SwipeException(SwipeDeckTokenCodec.INVALID_CARD_TOKEN)

        return ParsedSwipeActionRequest(
            targetUserId = targetUserId,
            action = action,
            noteText = noteText,
            actionId = request.actionId.trim().toActionIdOrThrow(),
            deckSessionId = deckSessionId,
            cardToken = cardToken,
        )
    }

    private fun String.toUuidOrThrow(): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw SwipeException("INVALID_SWIPE_TARGET") }

    private fun String.toActionIdOrThrow(): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw SwipeException("INVALID_SWIPE_ACTION_ID") }
}
