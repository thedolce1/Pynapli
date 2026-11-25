package com.pynapli.app.profile

import com.pynapli.app.grid.GridCardTone
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.PublicProfileDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The holder rules the Grid's profile pager leans on when it swipes from one person to the next. */
class ProfileDetailPagingTest {
    @Test
    fun aSlowProfileNeverLandsUnderTheNextPersonsCard() =
        runTest {
            val gates = mutableMapOf("a" to CompletableDeferred<Unit>(), "b" to CompletableDeferred())
            val sample = SamplePublicProfileRepository()
            val repository =
                object : PublicProfileRepository by sample {
                    override suspend fun loadProfile(userId: String): AppResult<PublicProfileDto> {
                        gates[userId]?.await()
                        return sample.loadProfile(userId)
                    }
                }
            val holder = ProfileDetailStateHolder(repository)

            launch { holder.open(card("a")) }
            runCurrent()
            launch { holder.open(card("b")) }
            runCurrent()

            gates.getValue("b").complete(Unit)
            runCurrent()
            gates.getValue("a").complete(Unit)
            runCurrent()

            assertEquals("b", holder.state.value.selectedCard?.id)
            assertEquals("b", holder.state.value.profile?.userId)
        }

    @Test
    fun movingOnFromAReportedPersonRemovesThemFromTheGrid() =
        runTest {
            val holder = ProfileDetailStateHolder(SamplePublicProfileRepository())
            holder.open(card("r"))
            holder.submitReport("spam_or_fake_profile")

            val removed = async { holder.removedProfiles.first() }
            runCurrent()
            holder.open(card("next"))

            assertEquals("r", removed.await())
        }

    private fun card(id: String): GridProfileCardUi =
        GridProfileCardUi(
            id = id,
            displayName = "User $id",
            online = false,
            distanceLabel = null,
            tone = GridCardTone.entries.first(),
        )
}
