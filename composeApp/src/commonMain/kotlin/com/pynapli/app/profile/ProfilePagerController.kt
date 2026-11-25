package com.pynapli.app.profile

import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.app.grid.isLockedCard

/**
 * What the Grid's profile pager shows right now (GRID_IMPLEMENTATION_SPEC "Profile open"): the
 * grid's cards in grid order, and the page of the person the profile sheet has selected.
 * [replacement] is set when the selected person was just blocked or hidden: the host opens that
 * card next so the pager moves on instead of closing. [closeRequested] means nobody is left.
 */
data class ProfilePagerModel(
    val pages: List<GridProfileCardUi>,
    val currentIndex: Int,
    val replacement: GridProfileCardUi? = null,
    val closeRequested: Boolean = false,
)

/** Side effects for a page the pager settled on. Each fires at most once per settle. */
data class ProfilePagerSettleEffects(
    // Open (fetch) this person's full profile: the fetch is also what records the profile view.
    val open: GridProfileCardUi? = null,
    // Ask the grid's own state holder for its next page.
    val loadMore: Boolean = false,
)

/**
 * Plain state for swiping between Grid profiles, kept out of Compose so its rules are testable on
 * every target. The profile sheet's selection ([ProfileDetailUiState.selectedCard]) stays the one
 * authority on who is open; this only maps it onto the grid's live card list:
 *
 * - The selected card's index is the page; a card opened from outside the list (a linked partner)
 *   is not browsable, so the host falls back to the single-profile sheet.
 * - Pages merely composed fire nothing; [onPageSettled] opens a page's profile once, and never for
 *   the page that is already open (so the tap that opened the pager doesn't fetch twice).
 * - Settling near the end asks for the grid's next page, once per list size.
 * - The open card leaving the list for a safety reason (block, hide) moves on to the card that took
 *   its place; leaving for any other reason (un-starred in Favorites, a quiet refresh) keeps it on
 *   screen until the viewer swipes away.
 *
 * [isLocked] marks Section 3's locked cards: a locked page shows the paywall instead of a profile and
 * never fetches one. The pages end at the first locked card: one "See plans" page after the last
 * person the viewer can see, rather than hundreds of identical paywall pages, and settling there
 * asks for no more pages (they would only be more locks). A block or hide moves on to an unlocked
 * person, never onto the locked page.
 */
class ProfilePagerController(
    val isLocked: (GridProfileCardUi) -> Boolean = { it.isLockedCard },
) {
    private var currentId: String? = null
    private var currentCard: GridProfileCardUi? = null
    private var lastIndex: Int = 0
    private var requestedOpenId: String? = null
    private var loadMoreRequestedAtSize: Int = -1
    private var pages: List<GridProfileCardUi> = emptyList()

    /**
     * Maps [selected] onto [live] (the grid's cards, in grid order). [removed] holds people blocked
     * or hidden from this pager. Returns null when the selection is not browsable.
     */
    fun reconcile(
        live: List<GridProfileCardUi>,
        selected: GridProfileCardUi?,
        removed: Set<String> = emptySet(),
    ): ProfilePagerModel? {
        if (selected == null) {
            reset()
            return null
        }
        val visible = (if (removed.isEmpty()) live else live.filterNot { it.id in removed }).endingAtFirstLocked()
        val liveIndex = visible.indexOfFirst { it.id == selected.id }
        if (liveIndex >= 0) {
            return track(visible, selected, liveIndex)
        }
        if (selected.id != currentId) {
            // Opened from outside the list (a linked partner): the single-profile sheet shows it.
            reset()
            return null
        }
        if (selected.id in removed) {
            pages = visible
            val index = replacementIndex(visible)
            if (index < 0) return ProfilePagerModel(pages = visible, currentIndex = 0, closeRequested = true)
            return ProfilePagerModel(pages = visible, currentIndex = index, replacement = visible[index])
        }
        // Gone from the list for a non-safety reason: keep it where it was until the viewer moves on.
        val kept = visible.toMutableList().apply { add(lastIndex.coerceIn(0, size), currentCard ?: selected) }
        return track(kept, selected, kept.indexOfFirst { it.id == selected.id })
    }

    /** The pager came to rest on [index] (a swipe, a previous/next button, an arrow key). */
    fun onPageSettled(
        index: Int,
        hasMore: Boolean,
        isLoadingMore: Boolean,
    ): ProfilePagerSettleEffects {
        val card = pages.getOrNull(index) ?: return ProfilePagerSettleEffects()
        val open =
            card.takeIf {
                it.id != currentId && it.id != requestedOpenId && !isLocked(it)
            }
        if (open != null) requestedOpenId = open.id
        val nearEnd = index >= pages.size - LOAD_MORE_THRESHOLD
        // Ending on the locked page: more pages would only be more locks.
        val endsLocked = pages.lastOrNull()?.let(isLocked) == true
        val loadMore = nearEnd && !endsLocked && hasMore && !isLoadingMore && loadMoreRequestedAtSize != pages.size
        if (loadMore) loadMoreRequestedAtSize = pages.size
        return ProfilePagerSettleEffects(open = open, loadMore = loadMore)
    }

    private fun track(
        pagesNow: List<GridProfileCardUi>,
        selected: GridProfileCardUi,
        index: Int,
    ): ProfilePagerModel {
        pages = pagesNow
        if (currentId != selected.id) requestedOpenId = null
        currentId = selected.id
        currentCard = pagesNow[index]
        lastIndex = index
        return ProfilePagerModel(pages = pagesNow, currentIndex = index)
    }

    /**
     * Who takes a removed person's place: the next person in their slot, else the one before. Never
     * the locked page, which has no profile to open. -1 when nobody unlocked is left.
     */
    private fun replacementIndex(visible: List<GridProfileCardUi>): Int {
        if (visible.isEmpty()) return -1
        val slot = lastIndex.coerceIn(0, visible.lastIndex)
        if (!isLocked(visible[slot])) return slot
        return visible.subList(0, slot).indexOfLast { !isLocked(it) }
    }

    /** Everyone up to and including the first locked card: the pager's single "See plans" page. */
    private fun List<GridProfileCardUi>.endingAtFirstLocked(): List<GridProfileCardUi> {
        val firstLocked = indexOfFirst(isLocked)
        return if (firstLocked < 0 || firstLocked == lastIndex) this else subList(0, firstLocked + 1).toList()
    }

    private fun reset() {
        currentId = null
        currentCard = null
        lastIndex = 0
        requestedOpenId = null
        loadMoreRequestedAtSize = -1
        pages = emptyList()
    }

    companion object {
        // Settling this close to the end of the loaded cards fetches the grid's next page.
        const val LOAD_MORE_THRESHOLD: Int = 3
    }
}
