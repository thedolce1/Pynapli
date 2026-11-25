package com.pynapli.app.grid

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's sessionStorage snapshot (plan G12) goes through this blob store. */
class KeyValueGridSnapshotStoreTest {
    private var blob: String? = null
    private val store =
        KeyValueGridSnapshotStore(
            read = { blob },
            write = { blob = it },
            remove = { blob = null },
            maxCards = 2,
        )

    private fun card(id: String) = GridProfileCardUi(id = id, displayName = id, online = true, distanceLabel = "1 km", tone = GridCardTone.Moss, activeAgo = GridActiveAgo.ONLINE)

    @Test
    fun savesACappedListAndReadsItBack() =
        runTest {
            store.save(listOf(card("a"), card("b"), card("c")))
            assertEquals(listOf("a", "b"), store.load().map { it.id })
            assertEquals(GridActiveAgo.ONLINE, store.load().first().activeAgo)
        }

    @Test
    fun aCorruptBlobReadsAsEmptyAndClearRemovesIt() =
        runTest {
            blob = "{broken"
            assertTrue(store.load().isEmpty())
            store.save(listOf(card("a")))
            store.clear()
            assertNull(blob)
            assertTrue(store.load().isEmpty())
        }

    @Test
    fun anOlderBlobWithoutTheNewFieldsStillDecodes() =
        runTest {
            blob = """[{"id":"x","displayName":"X","online":false,"distanceLabel":null,"tone":"Warm"}]"""
            val decoded = store.load().single()
            assertEquals("x", decoded.id)
            assertNull(decoded.activeAgo)
        }
}
