package com.pynapli.app.grid

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.storage.PynapliDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Persists the last successfully-loaded Grid cards so the next app open can render them immediately
 * (GRID_IMPLEMENTATION_SPEC "Location Strategy"/"Performance": show the last cached grid briefly for
 * an instant first paint while the network refresh runs). Only the lightweight card list is cached —
 * cursor, paywall, and ad state always come fresh from the network refresh. This is a best-effort
 * convenience cache, never the source of truth.
 */
interface GridSnapshotStore {
    suspend fun load(): List<GridProfileCardUi>

    suspend fun save(cards: List<GridProfileCardUi>)
}

/** No-op store for previews/tests and platforms without local persistence wired. */
object NoOpGridSnapshotStore : GridSnapshotStore {
    override suspend fun load(): List<GridProfileCardUi> = emptyList()

    override suspend fun save(cards: List<GridProfileCardUi>) = Unit
}

/**
 * SQLDelight-backed snapshot store. Reuses the generic `app_meta` key-value table (same pattern as
 * `SqlDelightAppSettingsStore`), so no schema migration is needed. The card list is stored as a JSON
 * blob; a decode failure falls back to an empty snapshot so a stale/corrupt blob never blocks load.
 * Caps the cached count so the blob stays small.
 */
class SqlDelightGridSnapshotStore(
    private val database: PynapliDatabase,
    private val json: Json = PynapliJson,
    private val maxCards: Int = MAX_SNAPSHOT_CARDS,
) : GridSnapshotStore {
    private val cardListSerializer = ListSerializer(GridProfileCardUi.serializer())

    override suspend fun load(): List<GridProfileCardUi> {
        val value =
            database.appMetaQueries
                .selectByKey(GRID_SNAPSHOT_KEY)
                .executeAsOneOrNull()
                ?.value_
                ?: return emptyList()
        return runCatching { json.decodeFromString(cardListSerializer, value) }.getOrElse { emptyList() }
    }

    override suspend fun save(cards: List<GridProfileCardUi>) {
        val capped = cards.take(maxCards)
        database.appMetaQueries.upsert(
            key = GRID_SNAPSHOT_KEY,
            value_ = json.encodeToString(cardListSerializer, capped),
            updated_at_epoch_ms = currentTimeMillis(),
        )
    }

    @OptIn(ExperimentalTime::class)
    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        private const val GRID_SNAPSHOT_KEY = "grid_snapshot_v1"

        // One screenful-plus is plenty for an instant first paint; the network refresh fills the rest.
        const val MAX_SNAPSHOT_CARDS = 60
    }
}
