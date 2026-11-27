package com.pynapli.app.grid

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A widened sparse first page shows "Showing people farther away"; a normal page clears it. */
class GridWidenedTest {
    private val someone = GridProfileCardUi(id = "a", displayName = "A", online = false, distanceLabel = null)

    @Test
    fun theWidenedFlagFollowsEachFirstPage() =
        runTest {
            var widened = true
            val holder =
                GridStateHolder(
                    object : GridFeedRepository {
                        override suspend fun loadGrid(
                            filters: GridFilters,
                            cursor: String?,
                        ): GridFeedUi = GridFeedUi(cards = listOf(someone), widened = widened)
                    },
                )

            holder.load()
            assertTrue(holder.state.value.widened)

            widened = false
            holder.refresh()
            assertFalse(holder.state.value.widened)
        }
}
