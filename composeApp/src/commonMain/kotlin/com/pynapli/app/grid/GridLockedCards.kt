package com.pynapli.app.grid

import com.pynapli.shared.network.dto.SubscriptionTier

/**
 * Section 3 locked cards (GRID_IMPLEMENTATION_SPEC "Locked cards"). Past the viewer's plan limit the
 * server keeps paging, but marks each card `locked`: its id is an opaque per-viewer token starting
 * with [LOCKED_CARD_ID_PREFIX], it has no name, and it carries only what a tile shows. The server
 * decides which cards are locked; the client never computes entitlement, it only keeps these cards
 * out of everything that needs a real person (presence, the cold-start cache, favourites, "N new
 * nearby", profile fetches).
 */
internal const val LOCKED_CARD_ID_PREFIX: String = "locked_"

/**
 * True for a card the server locked. The id prefix is checked too, so a token that ever arrived
 * without its flag still never reaches presence, the cache or a profile fetch.
 */
val GridProfileCardUi.isLockedCard: Boolean
    get() = locked || id.startsWith(LOCKED_CARD_ID_PREFIX)

/** The cards that stand for real, unlocked people. */
internal fun List<GridProfileCardUi>.withoutLockedCards(): List<GridProfileCardUi> = if (none { it.isLockedCard }) this else filterNot { it.isLockedCard }

/**
 * Where the "See everyone nearby" banner sits in [cards]: just before the first locked card, or null
 * when nothing is locked. Anchored on the list itself rather than the server's `lockedFromIndex`,
 * which counts the server's order: after a block or hide removed a card, or a page overlap was
 * de-duplicated, that index no longer points at the boundary in the list on screen.
 */
internal fun firstLockedCardIndex(cards: List<GridProfileCardUi>): Int? = cards.indexOfFirst { it.isLockedCard }.takeIf { it >= 0 }

/** What a tap on a Grid tile does: a locked tile sells the plan, never a profile. */
sealed interface GridTileTap {
    data class OpenProfile(
        val card: GridProfileCardUi,
    ) : GridTileTap

    /** Opens the plans sheet focused on [unlockTier] (the server's `unlockTier`; null = plan list). */
    data class OpenPaywall(
        val unlockTier: String?,
    ) : GridTileTap
}

internal fun routeGridTileTap(
    card: GridProfileCardUi,
    unlockTier: String?,
): GridTileTap = if (card.isLockedCard) GridTileTap.OpenPaywall(unlockTier) else GridTileTap.OpenProfile(card)

/** The boundary banner's line, naming the plan that unlocks the rest. */
internal fun lockedBannerMessage(unlockTier: String?): String =
    when (unlockTier) {
        SubscriptionTier.SELECT -> "See everyone nearby with Select"
        SubscriptionTier.SOCIAL -> "See everyone nearby with Social"
        else -> "See everyone nearby with a plan"
    }

/** Screen-reader text for a locked tile: neutral, and never anything that identifies the person. */
internal const val LOCKED_TILE_DESCRIPTION: String = "Locked profile, upgrade to see"
