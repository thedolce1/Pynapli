package com.pynapli.app.grid

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's sessionStorage snapshot (plan G12): it paints only for the account it was ranked for. */
class AccountBoundGridSnapshotStoreTest {
    private var blob: String? = null
    private var userId: String? = "member-a"
    private val store =
        AccountBoundGridSnapshotStore(
            currentUserId = { userId },
            read = { blob },
            write = { blob = it },
            remove = { blob = null },
            maxCards = 2,
        )

    private fun card(id: String) = GridProfileCardUi(id = id, displayName = id, online = true, distanceLabel = "1 km", tone = GridCardTone.Moss)

    @Test
    fun theSameAccountGetsItsCappedSnapshotBack() =
        runTest {
            store.save(listOf(card("a"), card("b"), card("c")))
            assertEquals(listOf("a", "b"), store.load().map { it.id })
        }

    @Test
    fun anotherAccountNeverSeesTheOldCardsAndTheBlobIsRemoved() =
        runTest {
            store.save(listOf(card("a")))
            // Another tab signed this tab's session in as someone else.
            userId = "member-b"
            assertTrue(store.load().isEmpty())
            assertNull(blob, "the old account's cards are gone, not just hidden")
        }

    @Test
    fun nothingPaintsOrIsKeptWithNobodySignedIn() =
        runTest {
            store.save(listOf(card("a")))
            userId = null
            assertTrue(store.load().isEmpty())
            store.save(listOf(card("late")))
            assertNull(blob, "a late answer after sign-out is not kept")
        }

    @Test
    fun aCorruptOrUnboundBlobReadsAsEmptyAndSignOutClears() =
        runTest {
            blob = "{broken"
            assertTrue(store.load().isEmpty())
            // A bare card list from an earlier build carries no owner: never painted.
            blob = """[{"id":"x","displayName":"X","online":false,"distanceLabel":null,"tone":"Warm"}]"""
            assertTrue(store.load().isEmpty())
            store.save(listOf(card("a")))
            store.clear()
            assertNull(blob)
        }
}
