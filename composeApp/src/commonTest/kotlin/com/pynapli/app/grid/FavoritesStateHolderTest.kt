package com.pynapli.app.grid

import com.pynapli.app.profile.FavoriteChange
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.SamplePublicProfileRepository
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.FavoriteStatusDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoritesStateHolderTest {
    private fun card(id: String) = GridProfileCardUi(id = id, displayName = id, online = false, distanceLabel = null, tone = GridCardTone.Warm)

    @Test
    fun showLoadsOnceThenRefreshesQuietlyKeepingCards() =
        runTest {
            val repository = FakeFavoritesRepository(listOf(GridFeedUi(listOf(card("a"))), GridFeedUi(listOf(card("a"), card("b")))))
            val holder = FavoritesStateHolder(repository)

            holder.show()
            assertTrue(holder.state.value.showing)
            assertEquals(listOf("a"), holder.state.value.cards.map { it.id })

            holder.showNearby()
            assertFalse(holder.state.value.showing)
            holder.show()
            assertEquals(listOf("a", "b"), holder.state.value.cards.map { it.id })
            assertEquals(2, repository.calls.size)
        }

    @Test
    fun unstarringDropsTheCardAndBlockingDropsItToo() =
        runTest {
            val holder = FavoritesStateHolder(FakeFavoritesRepository(listOf(GridFeedUi(listOf(card("a"), card("b"), card("c"))))))
            holder.show()

            holder.onFavoriteChanged(FavoriteChange("a", favorite = false))
            holder.removeCardById("b")

            assertEquals(listOf("c"), holder.state.value.cards.map { it.id })
        }

    @Test
    fun pagingAppendsWithoutDuplicates() =
        runTest {
            val repository =
                FakeFavoritesRepository(
                    listOf(
                        GridFeedUi(listOf(card("a"), card("b")), nextCursor = "next"),
                        GridFeedUi(listOf(card("b"), card("c"))),
                    ),
                )
            val holder = FavoritesStateHolder(repository)
            holder.show()

            holder.loadMore()

            assertEquals(listOf("a", "b", "c"), holder.state.value.cards.map { it.id })
            assertEquals(listOf(null, "next"), repository.calls)
            assertEquals(null, holder.state.value.nextCursor)
        }

    @Test
    fun anEmptyListIsAnHonestEmptyStateAndSignOutForgetsEverything() =
        runTest {
            val holder = FavoritesStateHolder(FakeFavoritesRepository(listOf(GridFeedUi(emptyList()))))
            holder.show()
            assertTrue(holder.state.value.loaded)
            assertTrue(holder.state.value.cards.isEmpty())

            holder.clear()
            assertEquals(FavoritesUiState(), holder.state.value)
        }

    @Test
    fun theStarFlipsAtOnceAndRevertsWhenTheServerRefuses() =
        runTest {
            val repository = StarringRepository(fail = true)
            val profile = ProfileDetailStateHolder(repository)
            profile.open(card("target"))

            profile.toggleFavorite()

            assertFalse(profile.state.value.profile!!.favorite)
            assertEquals("Favorites limit reached.", profile.state.value.errorMessage)
        }

    @Test
    fun aConfirmedStarIsAnnouncedForTheFavoritesView() =
        runTest {
            val repository = StarringRepository(fail = false)
            val profile = ProfileDetailStateHolder(repository)
            profile.open(card("target"))
            var change: FavoriteChange? = null
            val job = launch { change = profile.favoriteChanges.first() }
            runCurrent()

            profile.toggleFavorite()
            runCurrent()

            assertTrue(profile.state.value.profile!!.favorite)
            assertEquals(FavoriteChange("target", favorite = true), change)
            assertEquals(listOf("target" to true), repository.calls)
            job.cancel()
        }
}

private class FakeFavoritesRepository(
    private val feeds: List<GridFeedUi>,
) : FavoritesRepository {
    val calls = mutableListOf<String?>()

    override suspend fun loadFavorites(cursor: String?): GridFeedUi {
        calls += cursor
        return feeds.getOrElse(calls.size - 1) { feeds.last() }
    }
}

private class StarringRepository(
    private val fail: Boolean,
) : com.pynapli.app.profile.PublicProfileRepository by SamplePublicProfileRepository() {
    val calls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun setFavorite(
        userId: String,
        favorite: Boolean,
    ): AppResult<FavoriteStatusDto> {
        calls += userId to favorite
        return if (fail) {
            AppResult.Failure(AppError(code = "FAVORITES_LIMIT_REACHED", message = "Favorites limit reached."))
        } else {
            AppResult.Success(FavoriteStatusDto(userId, favorite))
        }
    }
}
