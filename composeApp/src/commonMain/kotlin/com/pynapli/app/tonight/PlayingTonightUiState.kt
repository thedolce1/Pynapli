package com.pynapli.app.tonight

import androidx.compose.runtime.Immutable

/**
 * Availability intent for a Playing Tonight session. Server tokens are the canonical wire
 * values (PLAYING_TONIGHT_SPEC / TonightDtos). `null` filter means "All".
 */
enum class TonightIntent(
    val serverValue: String,
) {
    Solo("solo"),
    Together("together"),
    Social("social"),
    Event("event"),
    ;

    companion object {
        fun fromServer(value: String?): TonightIntent? = entries.firstOrNull { it.serverValue.equals(value, ignoreCase = true) }
    }
}

/** User-facing name for an intent (the map's filter pills, session rows, and the start sheet). */
internal val TonightIntent.label: String
    get() =
        when (this) {
            TonightIntent.Solo -> "Solo"
            TonightIntent.Together -> "Together"
            TonightIntent.Social -> "Social"
            TonightIntent.Event -> "Events"
        }

/**
 * One Playing Tonight feed row, mapped from [com.pynapli.shared.network.dto.TonightFeedItemDto].
 * Anchored on an individual (never a couple card); the linked partner is a mini-avatar only.
 * Location is intentionally coarse: only [zoneKey] (a ~111 m bucket) is carried — never precise
 * coordinates. The map jitters dots deterministically within their zone from [sessionId].
 */
@Immutable
data class TonightFeedItemUi(
    val sessionId: String,
    val userId: String,
    val displayName: String,
    val thumbnailUrl: String?,
    val online: Boolean,
    val intent: TonightIntent?,
    val intentRaw: String,
    val hostMode: String,
    val note: String?,
    val photoUrl: String?,
    val distanceLabel: String?,
    val distanceMeters: Int?,
    val zoneKey: String?,
    val linkedPartner: TonightLinkedPartnerUi?,
)

@Immutable
data class TonightLinkedPartnerUi(
    val userId: String,
    val displayName: String?,
    val thumbnailUrl: String?,
    val online: Boolean,
)

/**
 * The viewer's own live/pending session, mapped from [com.pynapli.shared.network.dto.TonightSessionDto].
 * Drives the top floater (live banner + End action). `expiresAt` is the raw ISO instant; the
 * screen formats a "expires in …" hint from it.
 */
@Immutable
data class TonightSessionUi(
    val id: String,
    val mode: String,
    val intent: TonightIntent?,
    val intentRaw: String,
    val hostMode: String,
    val status: String,
    val expiresAt: String,
    val participants: List<TonightParticipantUi> = emptyList(),
) {
    /** True while a linked-together session is waiting for the other partner to approve. */
    val isProposed: Boolean get() = status == "proposed"

    /** True when [viewerUserId] is the pending partner who must approve/decline this session. */
    fun pendingApprovalFor(viewerUserId: String?): Boolean =
        viewerUserId != null &&
            isProposed &&
            participants.any { it.userId == viewerUserId && it.approvalStatus == "pending" }

    fun ownerDisplayName(): String? = participants.firstOrNull { it.role == "owner" }?.displayName
}

@Immutable
data class TonightParticipantUi(
    val userId: String,
    val displayName: String?,
    val role: String,
    val approvalStatus: String,
)

/**
 * A photo the viewer can attach to a new session. Sourced from the viewer's already-approved
 * profile album (PLAYING_TONIGHT_SPEC "optional moderated session photo"): reusing approved photos
 * means the session photo is visible immediately, vs a fresh upload that moderation would hold past
 * the short session window.
 */
@Immutable
data class TonightPhotoOptionUi(
    val assetId: String,
    val thumbnailUrl: String?,
)

@Immutable
data class PlayingTonightUiState(
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val feedItems: List<TonightFeedItemUi> = emptyList(),
    val currentSession: TonightSessionUi? = null,
    val intentFilter: TonightIntent? = null,
    val photoOptions: List<TonightPhotoOptionUi> = emptyList(),
    val errorMessage: String? = null,
)
