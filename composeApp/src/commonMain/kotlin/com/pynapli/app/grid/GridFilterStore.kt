package com.pynapli.app.grid

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.storage.PynapliDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Remembers the viewer's Grid filters across cold starts (plan G9), on this device only. It holds
 * the filter choices and nothing else: no cards, no location, no cursor. Stored in the same shape as
 * a saved preset (`GridQueryRequestDto`, minus the cursor), so unknown tokens from an older build
 * drop silently on the way back in and the server re-validates on the next query. Sign-out clears it.
 */
interface GridFilterStore {
    suspend fun load(): GridFilters?

    suspend fun save(filters: GridFilters)

    suspend fun clear()
}

object NoOpGridFilterStore : GridFilterStore {
    override suspend fun load(): GridFilters? = null

    override suspend fun save(filters: GridFilters) = Unit

    override suspend fun clear() = Unit
}

/** The stored filters and the account that chose them. */
@Serializable
internal data class OwnedGridFilters(
    val ownerUserId: String,
    val filters: GridQueryRequestDto,
)

internal fun encodeGridFilters(
    ownerUserId: String,
    filters: GridFilters,
    json: Json = PynapliJson,
): String = json.encodeToString(OwnedGridFilters.serializer(), OwnedGridFilters(ownerUserId, filters.toGridQueryRequest(cursor = null)))

/**
 * The filters stored for [ownerUserId], or null. A corrupt blob, one from an earlier build without an
 * owner, or one chosen by another account reads as "nothing saved", never as an error.
 */
internal fun decodeGridFilters(
    value: String?,
    ownerUserId: String?,
    json: Json = PynapliJson,
): GridFilters? {
    if (value.isNullOrBlank() || ownerUserId == null) return null
    val owned = runCatching { json.decodeFromString(OwnedGridFilters.serializer(), value) }.getOrNull() ?: return null
    if (owned.ownerUserId != ownerUserId) return null
    return GridFilters.fromGridQueryRequest(owned.filters)
}

/**
 * Filter choices bound to the signed-in account (privacy review F9), like the Grid snapshot: written
 * with the account's id and restored only for that same account. On web the store is shared by every
 * tab while another tab can switch this one's session to someone else without a sign-out passing
 * through here; lifestyle tags are sensitive, so a mismatch, no session or a corrupt blob reads as
 * nothing saved and the blob is deleted. A save with nobody signed in keeps nothing.
 */
class AccountBoundGridFilterStore(
    private val currentUserId: () -> String?,
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val remove: () -> Unit,
) : GridFilterStore {
    override suspend fun load(): GridFilters? {
        val value = read() ?: return null
        val restored = decodeGridFilters(value, currentUserId())
        if (restored == null) remove()
        return restored
    }

    override suspend fun save(filters: GridFilters) {
        val owner = currentUserId() ?: return remove()
        write(encodeGridFilters(owner, filters))
    }

    override suspend fun clear() = remove()
}

/** Android and iOS: the `app_meta` key-value table, as the Grid snapshot and app settings use. */
fun sqlDelightGridFilterStore(
    database: PynapliDatabase,
    currentUserId: () -> String?,
): GridFilterStore =
    AccountBoundGridFilterStore(
        currentUserId = currentUserId,
        read = {
            database.appMetaQueries
                .selectByKey(GRID_FILTERS_KEY)
                .executeAsOneOrNull()
                ?.value_
        },
        write = { value ->
            database.appMetaQueries.upsert(key = GRID_FILTERS_KEY, value_ = value, updated_at_epoch_ms = currentTimeMillis())
        },
        remove = { database.appMetaQueries.deleteByKey(GRID_FILTERS_KEY) },
    )

private const val GRID_FILTERS_KEY = "grid_filters_v1"

@OptIn(ExperimentalTime::class)
private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
