package com.pynapli.app.match

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MatchDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchCelebrationStateHolderTest {
    @Test
    fun unseenMatchesShowOneAtATimeAndAreMarkedSeenOnlyOnceShown() =
        runTest {
            val repository = FakeMatchCelebrationRepository(listOf(moment("m-1", "Maya"), moment("m-2", "Alex")))
            val holder = MatchCelebrationStateHolder(repository)

            holder.refresh()

            assertEquals(
                "m-1",
                holder.state.value.current
                    ?.primaryMatchId,
            )
            assertTrue(repository.marked.isEmpty(), "not seen until it is on screen")

            holder.markShown(holder.state.value.current!!)
            assertEquals(listOf(listOf("m-1")), repository.marked)

            holder.dismiss()
            assertEquals(
                "m-2",
                holder.state.value.current
                    ?.primaryMatchId,
            )
            holder.dismiss()
            assertNull(holder.state.value.current)
        }

    @Test
    fun theSwipersOwnMatchIsNotShownAgainWhenTheMatchDeltaRefetches() =
        runTest {
            val repository = FakeMatchCelebrationRepository(emptyList(), viewerPhotoUrl = "https://cdn.test/me.jpg")
            val holder = MatchCelebrationStateHolder(repository)
            holder.refresh()

            holder.celebrate(MatchDto(matchId = "m-1", matchedUserId = "maya", matchedDisplayName = "Maya", createdAt = "t"))
            val shown = holder.state.value.current!!
            assertEquals("https://cdn.test/me.jpg", shown.viewerPhotoUrl)

            // The match delta arrives before the server has the mark: the same match is still unseen there.
            repository.items = listOf(moment("m-1", "Maya"))
            holder.refresh()
            holder.dismiss()

            assertNull(holder.state.value.current)
        }

    @Test
    fun aPsyncPairIsOneMomentAndBothMatchesAreMarkedSeen() =
        runTest {
            val pair = moment("m-1", "Maya").copy(matchIds = listOf("m-1", "m-2"), pairUserId = "alex", pairDisplayName = "Alex")
            val repository = FakeMatchCelebrationRepository(listOf(pair))
            val holder = MatchCelebrationStateHolder(repository)

            holder.refresh()
            holder.markShown(holder.state.value.current!!)

            assertTrue(
                holder.state.value.current!!
                    .isPair,
            )
            assertEquals(listOf(listOf("m-1", "m-2")), repository.marked)
        }

    @Test
    fun aFailedMarkIsRetriedOnTheNextRefresh() =
        runTest {
            val repository = FakeMatchCelebrationRepository(listOf(moment("m-1", "Maya")))
            val holder = MatchCelebrationStateHolder(repository)
            holder.refresh()
            repository.failMarks = true
            holder.markShown(holder.state.value.current!!)
            assertTrue(repository.marked.isEmpty())

            repository.failMarks = false
            repository.items = emptyList()
            holder.refresh()

            assertEquals(listOf(listOf("m-1")), repository.marked)
        }

    @Test
    fun signingOutForgetsEverything() =
        runTest {
            val holder = MatchCelebrationStateHolder(FakeMatchCelebrationRepository(listOf(moment("m-1", "Maya"))))
            holder.refresh()

            holder.reset()

            assertNull(holder.state.value.current)
            assertTrue(
                holder.state.value.handledMatchIds
                    .isEmpty(),
            )
        }
}

private fun moment(
    matchId: String,
    name: String,
) = MatchCelebrationUi(
    matchIds = listOf(matchId),
    matchedUserId = name.lowercase(),
    matchedDisplayName = name,
    matchedPhotoUrl = null,
    viewerPhotoUrl = null,
)

private class FakeMatchCelebrationRepository(
    var items: List<MatchCelebrationUi>,
    private val viewerPhotoUrl: String? = null,
) : MatchCelebrationRepository {
    val marked = mutableListOf<List<String>>()
    var failMarks = false

    override suspend fun loadUnseen(): AppResult<MatchCelebrationsUi> = AppResult.Success(MatchCelebrationsUi(items, viewerPhotoUrl))

    override suspend fun markSeen(matchIds: List<String>): AppResult<Unit> {
        if (failMarks) return AppResult.Failure(AppError("NETWORK", "offline"))
        marked += matchIds
        return AppResult.Success(Unit)
    }
}
