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
 *
 * Privacy: a cached card holds only what the server sent for the card (name, thumbnail URL, coarse
 * distance bucket, presence flag, partner mini), never a coordinate. The refetch replaces the cached
 * cards wholesale, so anyone blocked or hidden since drops out, and sign-out wipes the cache.
 */
interface GridSnapshotStore {
    suspend fun load(): List<GridProfileCardUi>

    suspend fun save(cards: List<GridProfileCardUi>)

    /** Forgets the cached cards (sign-out / account switch). */
    suspend fun clear()
}

/** No-op store for previews/tests and platforms without local persistence wired. */
object NoOpGridSnapshotStore : GridSnapshotStore {
    override suspend fun load(): List<GridProfileCardUi> = emptyList()

    override suspend fun save(cards: List<GridProfileCardUi>) = Unit

    override suspend fun clear() = Unit
}

/** The cards a snapshot holds and the account they were ranked for. */
@kotlinx.serialization.Serializable
internal data class OwnedGridSnapshot(
    val ownerUserId: String,
    val cards: List<GridProfileCardUi>,
)

/**
 * A snapshot bound to the signed-in account: it is written with the account's id and painted only
 * for that same account. On web another tab can switch this tab's session to someone else (the
 * session lives in `localStorage`, the snapshot in this tab's `sessionStorage`); a mismatch, or no
 * signed-in account, reads as empty and the stale blob is removed, so another account's cards never
 * paint. Sign-out clears it as well (`GridStateHolder.clearForSignOut`).
 */
class AccountBoundGridSnapshotStore(
    private val currentUserId: () -> String?,
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val remove: () -> Unit,
    private val json: Json = PynapliJson,
    private val maxCards: Int = SqlDelightGridSnapshotStore.MAX_SNAPSHOT_CARDS,
) : GridSnapshotStore {
    override suspend fun load(): List<GridProfileCardUi> {
        val value = read() ?: return emptyList()
        val owner = currentUserId()
        val snapshot = runCatching { json.decodeFromString(OwnedGridSnapshot.serializer(), value) }.getOrNull()
        if (owner == null || snapshot == null || snapshot.ownerUserId != owner) {
            remove()
            return emptyList()
        }
        return snapshot.cards
    }

    override suspend fun save(cards: List<GridProfileCardUi>) {
        // Nobody signed in (a late answer after sign-out): nothing to bind it to, so nothing is kept.
        val owner = currentUserId() ?: return remove()
        write(json.encodeToString(OwnedGridSnapshot.serializer(), OwnedGridSnapshot(owner, cards.take(maxCards))))
    }

    override suspend fun clear() = remove()
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

    override suspend fun clear() {
        database.appMetaQueries.deleteByKey(GRID_SNAPSHOT_KEY)
    }

    @OptIn(ExperimentalTime::class)
    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        private const val GRID_SNAPSHOT_KEY = "grid_snapshot_v1"

        // One screenful-plus is plenty for an instant first paint; the network refresh fills the rest.
        const val MAX_SNAPSHOT_CARDS = 60
    }
}
