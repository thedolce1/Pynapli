package com.pynapli.server.swipe

import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.SwipeAction
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A rewound card comes back exactly as it was dealt: the linked partner only when the owner's
 * partner visibility lets this viewer see them, and the couple badge only with that partner.
 */
class SwipeRewindPartnerVisibilityIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun aRewoundCardShowsThePartnerAndCoupleBadgeOnlyWhenThePartnerIsVisible() =
        runBlocking {
            val viewer = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val partner = UUID.randomUUID()
            dataSource.insertDiscoverableUser(viewer, "Viewer", 35, -79.38, 43.65)
            dataSource.insertDiscoverableUser(owner, "Owner", 34, -79.381, 43.651)
            dataSource.insertDiscoverableUser(partner, "Partner", 33, -79.382, 43.652)
            dataSource.sql("INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at) VALUES (?, ?, ?, 'active', now())", UUID.randomUUID(), owner, partner)
            dataSource.sql("UPDATE profiles SET verified_at = now() WHERE user_id IN (?, ?)", owner, partner)
            JdbcSwipeRepository(dataSource).recordSwipeAction(UUID.randomUUID(), viewer, owner, SwipeAction.LIKE)

            val visible = rewoundCard(viewer)
            assertEquals(partner, visible.linkedPartnerUserId)
            assertEquals(true, SwipeDtoMapper(null).cardToDto(visible, "t", false, emptySet()).coupleVerified)

            dataSource.sql("UPDATE discovery_settings SET partner_visibility = 'hidden' WHERE user_id = ?", owner)
            val hidden = rewoundCard(viewer)
            assertNull(hidden.linkedPartnerUserId)
            assertEquals(false, SwipeDtoMapper(null).cardToDto(hidden, "t", false, emptySet()).coupleVerified)

            // verified_only opens once the viewer is verified.
            dataSource.sql("UPDATE discovery_settings SET partner_visibility = 'verified_only' WHERE user_id = ?", owner)
            assertNull(rewoundCard(viewer).linkedPartnerUserId)
            dataSource.sql("UPDATE profiles SET verified_at = now() WHERE user_id = ?", viewer)
            assertEquals(partner, rewoundCard(viewer).linkedPartnerUserId)
        }

    @Test
    fun aRewoundCardDropsAPartnerTheViewerHidAsTheGridDoes() =
        runBlocking<Unit> {
            // G2-4 partner strip parity: Grid and Favorites strip a partner the viewer hid; so does rewind.
            val viewer = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val partner = UUID.randomUUID()
            dataSource.insertDiscoverableUser(viewer, "Viewer", 35, -79.38, 43.65)
            dataSource.insertDiscoverableUser(owner, "Owner", 34, -79.381, 43.651)
            dataSource.insertDiscoverableUser(partner, "Partner", 33, -79.382, 43.652)
            dataSource.sql("INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at) VALUES (?, ?, ?, 'active', now())", UUID.randomUUID(), owner, partner)
            val service =
                SwipeService(
                    repository = JdbcSwipeRepository(dataSource),
                    hiddenProfileRepository =
                        com.pynapli.server.safety
                            .JdbcHiddenProfileRepository(dataSource),
                )

            JdbcSwipeRepository(dataSource).recordSwipeAction(UUID.randomUUID(), viewer, owner, SwipeAction.LIKE)
            // The SQL still deals the partner (the owner shows them publicly); only the viewer's hide drops it.
            assertEquals(partner, rewoundCard(viewer).linkedPartnerUserId)
            dataSource.sql("INSERT INTO user_hidden_profiles (id, hider_user_id, hidden_user_id) VALUES (?, ?, ?)", UUID.randomUUID(), viewer, partner)

            val rewound = service.rewind(viewer)
            assertEquals(owner.toString(), rewound.card?.userId)
            assertNull(rewound.card?.linkedPartner)
        }

    private fun rewoundCard(viewer: UUID): SwipeCardRecord = assertNotNull(dataSource.connection.use { it.findLatestRewindableAction(viewer, radiusMeters = 50_000) }).card
}

private fun DataSource.sql(
    statement: String,
    vararg params: Any,
) {
    connection.use { connection ->
        connection.prepareStatement(statement).use { prepared ->
            params.forEachIndexed { index, value -> prepared.setObject(index + 1, value) }
            prepared.executeUpdate()
        }
    }
}
