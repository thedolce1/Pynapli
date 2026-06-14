package com.pynapli.server.psync

import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import java.time.Duration

internal const val PSYNC_STATUS_INVITED = "invited"
internal const val PSYNC_STATUS_CONFIGURING = "configuring"
internal const val PSYNC_STATUS_READY = "ready"
internal const val PSYNC_STATUS_ACTIVE = "active"
internal const val PSYNC_STATUS_PAUSED = "paused"
internal const val PSYNC_STATUS_COMPLETED = "completed"
internal const val PSYNC_STATUS_CANCELLED = "cancelled"
internal const val PSYNC_ROLE_INITIATOR = "initiator"
internal const val PSYNC_ROLE_PARTNER = "partner"
internal const val PSYNC_CONSENT_PENDING = "pending"
internal const val PSYNC_CONSENT_ACCEPTED = "accepted"
internal const val PSYNC_CONSENT_DENIED = "denied"
internal const val PSYNC_MIN_DECK_SIZE = 1
internal const val PSYNC_MAX_DECK_SIZE = 50

// Oversample ceiling for the ranker so cross-partner filtering still yields deckSize.
internal const val PSYNC_MAX_RANK_LIMIT = 60

internal val PSYNC_VALID_ACTIONS = setOf("like", "pass", "pineapple_like")

// Non-terminal: a session that still has a future (negotiating or live). Terminal =
// completed/expired/cancelled.
internal val PSYNC_NON_TERMINAL_STATUSES = setOf("invited", "configuring", "ready", "active", "paused")
internal val PSYNC_TERMINAL_STATUSES = setOf("completed", "expired", "cancelled")

// Filters can be re-suggested while negotiating or once ready (re-opens negotiation).
internal val PSYNC_SUGGESTABLE_STATUSES = setOf("invited", "configuring", "ready")

// Sweep these to 'expired' when past expires_at.
internal val PSYNC_EXPIRABLE_STATUSES = setOf("invited", "configuring", "ready", "paused")

internal val PSYNC_NON_TERMINAL_STATUS_LIST = PSYNC_NON_TERMINAL_STATUSES.joinToString(", ") { "'$it'" }
internal val PSYNC_EXPIRABLE_STATUS_LIST = PSYNC_EXPIRABLE_STATUSES.joinToString(", ") { "'$it'" }

// PSYNC_SPEC Edge Cases: "Partner ignores invite -> Invite expires." 24h window.
internal val PSYNC_INVITE_TTL: Duration = Duration.ofHours(24)

internal fun requirePsyncDeckSize(deckSize: Int) {
    if (deckSize !in PSYNC_MIN_DECK_SIZE..PSYNC_MAX_DECK_SIZE) {
        throw PsyncException("INVALID_PSYNC_DECK_SIZE")
    }
}

/** PSYNC_SPEC Monetization: advanced (lifestyle/logistics) filters need Social/Select. */
internal fun requirePsyncFiltersAllowed(
    filters: SwipeDeckRequestDto,
    policy: EntitlementPolicy,
) {
    val discovery =
        discoveryFiltersFrom(
            minimumAge = filters.minimumAge,
            maximumAge = filters.maximumAge,
            relationshipContexts = filters.relationshipContexts,
            linkedOnly = filters.linkedOnly,
            verifiedOnly = filters.verifiedOnly,
            facePhotoVerifiedOnly = filters.facePhotoVerifiedOnly,
            ageVerifiedOnly = filters.ageVerifiedOnly,
            discoveryStyles = filters.discoveryStyles,
            lifestyleTags = filters.lifestyleTags,
            logisticsTags = filters.logisticsTags,
            intents = filters.intents,
            verifiedPreferred = filters.verifiedPreferred,
        ) { PsyncException("INVALID_PSYNC_FILTER") }
    if (discovery.requiresAdvancedEntitlement && !policy.advancedFiltersAllowed) {
        throw PsyncException("PSYNC_ADVANCED_FILTERS_NOT_ALLOWED")
    }
}
