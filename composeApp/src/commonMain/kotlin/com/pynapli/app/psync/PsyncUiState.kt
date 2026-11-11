package com.pynapli.app.psync

import androidx.compose.runtime.Immutable
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.network.dto.PsyncDeckCardDto
import com.pynapli.shared.network.dto.PsyncRecapDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto

/**
 * Where the viewer sits in the Psync flow, derived from the server-owned session status.
 * The server is the authority; this is a client convenience for routing the UI.
 */
enum class PsyncPhase {
    IDLE, // no current session
    INVITED, // an invite exists, awaiting approvals
    CONFIGURING, // filters were re-suggested, re-approval needed
    READY, // both approved, awaiting both to tap Start
    ACTIVE, // shared deck live
    PAUSED, // a partner paused
    COMPLETED, // resolved, recap available
    ENDED, // expired or cancelled
    ;

    companion object {
        /** Over, one way or another: nothing more to do in this session. */
        val FINISHED = setOf(COMPLETED, ENDED)
    }
}

/**
 * Immutable UI state for the Psync surfaces. DTOs never leak past this layer.
 *
 * The [Immutable] annotation makes that contract visible to the Compose compiler, which otherwise
 * infers the class unstable because `deck` is a `List` (an interface it cannot prove immutable) —
 * which in turn makes every composable taking this state non-skippable. Safe here: all fields are
 * `val`, and the deck is only ever replaced (`copy(deck = …map{})`), never mutated in place.
 */
@Immutable
data class PsyncUiState(
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val errorMessage: String? = null,
    val session: PsyncSessionUi? = null,
    val deck: List<PsyncDeckCardUi> = emptyList(),
    val recap: PsyncRecapUi? = null,
) {
    val phase: PsyncPhase get() = session?.phase ?: PsyncPhase.IDLE

    /** Cards the viewer has not decided on yet, in deck order. */
    val remainingCards: List<PsyncDeckCardUi> get() = deck.filter { it.viewerAction == null }
}

data class PsyncSessionUi(
    val id: String,
    val status: String,
    val deckSize: Int,
    val isInitiator: Boolean,
    val partnerDisplayName: String?,
    val viewerApproved: Boolean,
    val partnerApproved: Boolean,
    // The setup both partners are agreeing to.
    val filters: SwipeDeckRequestDto = SwipeDeckRequestDto(),
) {
    val phase: PsyncPhase
        get() =
            when (status) {
                "invited" -> PsyncPhase.INVITED
                "configuring" -> PsyncPhase.CONFIGURING
                "ready" -> PsyncPhase.READY
                "active" -> PsyncPhase.ACTIVE
                "paused" -> PsyncPhase.PAUSED
                "completed" -> PsyncPhase.COMPLETED
                else -> PsyncPhase.ENDED // expired | cancelled
            }

    /**
     * The viewer still needs to approve the current setup: they were invited, or their partner
     * suggested different filters (which resets everyone's approval but the suggester's).
     */
    val awaitingViewerApproval: Boolean
        get() = !viewerApproved && (status == "invited" || status == "configuring")

    /** The partner changed the filters and the viewer has not answered yet. */
    val partnerSuggestedChanges: Boolean
        get() = status == "configuring" && !viewerApproved && partnerApproved

    /** The setup in plain words, one line per fact, for both the inviter and the invitee. */
    fun setupSummary(distanceUnit: String): List<String> =
        buildList {
            add("$deckSize profiles")
            add("Within ${formatDistanceForUnit(filters.radiusMeters, fallbackLabel = null, distanceUnit = distanceUnit)}")
            val lo = filters.minimumAge
            val hi = filters.maximumAge
            when {
                lo != null && hi != null -> add("Ages $lo to $hi")
                lo != null -> add("Ages $lo and up")
                hi != null -> add("Ages up to $hi")
            }
            if (filters.verifiedOnly) add("Verified only")
            if (filters.linkedOnly == true) add("Linked partners only")
            if (filters.withPhotosOnly) add("With photos")
            val more =
                filters.discoveryStyles.size + filters.intents.size + filters.lifestyleTags.size + filters.logisticsTags.size +
                    filters.genders.size + filters.orientations.size
            if (more > 0) add(if (more == 1) "1 more preference" else "$more more preferences")
        }
}

data class PsyncDeckCardUi(
    val cardToken: String,
    val userId: String,
    val displayName: String,
    val online: Boolean,
    val viewerAction: String?,
    // The full card as dealt (photos, age, distance, badges), rendered by the shared Swipe card.
    val card: SwipeCardDto,
)

/** [Immutable] for the same reason as [PsyncUiState]: `togetherMatches` is a `List`. */
@Immutable
data class PsyncRecapUi(
    val profilesReviewed: Int,
    val bothLikedCount: Int,
    val togetherMatchCount: Int,
    val soloMatchCount: Int,
    val filtersSummary: String,
    val togetherMatches: List<PsyncMatchUi>,
)

data class PsyncMatchUi(
    val userId: String,
    val displayName: String?,
)

fun PsyncSessionDto.toUi(viewerUserId: String?): PsyncSessionUi {
    val viewer = participants.firstOrNull { it.userId == viewerUserId }
    val partner = participants.firstOrNull { it.userId != viewerUserId }
    return PsyncSessionUi(
        id = id,
        status = status,
        deckSize = deckSize,
        isInitiator = initiatorUserId == viewerUserId,
        partnerDisplayName = partner?.displayName,
        viewerApproved = viewer?.approved ?: false,
        partnerApproved = partner?.approved ?: false,
        filters = filters,
    )
}

fun PsyncDeckCardDto.toUi(): PsyncDeckCardUi =
    PsyncDeckCardUi(
        cardToken = cardToken,
        userId = card.userId,
        displayName = card.displayName,
        online = card.online,
        viewerAction = viewerAction,
        card = card,
    )

fun PsyncRecapDto.toUi(): PsyncRecapUi =
    PsyncRecapUi(
        profilesReviewed = profilesReviewed,
        bothLikedCount = bothLikedCount,
        togetherMatchCount = togetherMatchCount,
        soloMatchCount = soloMatchCount,
        filtersSummary = filtersSummary,
        togetherMatches = togetherMatches.map { PsyncMatchUi(it.targetUserId, it.displayName) },
    )
