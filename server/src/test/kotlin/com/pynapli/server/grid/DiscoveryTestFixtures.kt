package com.pynapli.server.grid

import com.pynapli.server.swipe.canonicalPair
import java.util.UUID
import javax.sql.DataSource

/**
 * Shared JDBC seeding fixtures for the discovery (grid + swipe) integration specs.
 * Extracted from GridAndSwipeRepositoryIntegrationTest so the focused specs share one seeding
 * vocabulary instead of duplicating INSERTs. All run against [PynapliTestDatabase.dataSource].
 */

internal fun DataSource.insertDiscoverableUser(
    userId: UUID,
    displayName: String,
    age: Int,
    longitude: Double,
    latitude: Double,
    relationshipContext: String = "single",
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO users (id, status)
                VALUES (?, 'active')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO profiles (
                    user_id,
                    display_name,
                    display_age,
                    relationship_context,
                    moderation_status,
                    visibility_status
                )
                VALUES (?, ?, ?, ?, 'approved', 'visible')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, displayName)
                statement.setInt(3, age)
                statement.setString(4, relationshipContext)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO discovery_settings (
                    user_id,
                    grid_visible,
                    swipe_visible,
                    incognito,
                    show_distance
                )
                VALUES (?, true, true, false, true)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO user_locations (
                    user_id,
                    geom,
                    location_source,
                    accuracy_meters
                )
                VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 'gps', 12)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setDouble(2, longitude)
                statement.setDouble(3, latitude)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.insertActiveProfileLink(
    userId: UUID,
    linkedUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO user_profile_links (
                    id,
                    user_id,
                    linked_user_id,
                    status,
                    accepted_at
                )
                VALUES (?, ?, ?, 'active', now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setObject(3, linkedUserId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.insertSession(
    userId: UUID,
    lastSeenAt: java.time.Instant,
    revoked: Boolean = false,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO user_sessions (
                    id, user_id, access_token_hash, refresh_token_hash,
                    access_expires_at, refresh_expires_at, last_seen_at, revoked_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setString(3, "access-${UUID.randomUUID()}")
                statement.setString(4, "refresh-${UUID.randomUUID()}")
                statement.setTimestamp(5, java.sql.Timestamp.from(lastSeenAt.plusSeconds(3_600)))
                statement.setTimestamp(6, java.sql.Timestamp.from(lastSeenAt.plusSeconds(86_400)))
                statement.setTimestamp(7, java.sql.Timestamp.from(lastSeenAt))
                statement.setTimestamp(8, revoked.takeIf { it }?.let { java.sql.Timestamp.from(lastSeenAt) })
                statement.executeUpdate()
            }
    }
}

/** Sets the durable last-active row (user_last_active) the Grid tier, Fresh and activeAgo read. */
internal fun DataSource.setLastActive(
    userId: UUID,
    at: java.time.Instant,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO user_last_active (user_id, last_active_at) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET last_active_at = EXCLUDED.last_active_at
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setTimestamp(2, java.sql.Timestamp.from(at))
                statement.executeUpdate()
            }
    }
}

/** Backdates the newest profile-album item of [userId] by [hours] (for the Fresh photo rule). */
internal fun DataSource.setProfilePhotosAddedHoursAgo(
    userId: UUID,
    hours: Int,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE media_album_items SET added_at = now() - make_interval(hours => ?) WHERE owner_user_id = ?",
            ).use { statement ->
                statement.setInt(1, hours)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.setUserCreatedDaysAgo(
    userId: UUID,
    days: Int,
) {
    connection.use { connection ->
        connection
            .prepareStatement("UPDATE users SET created_at = now() - make_interval(days => ?) WHERE id = ?")
            .use { statement ->
                statement.setInt(1, days)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.markProfileVerified(userId: UUID) = setProfileTimestamp(userId, column = "verified_at")

internal fun DataSource.markFacePhotoVerified(userId: UUID) = setProfileTimestamp(userId, column = "face_photo_verified_at")

internal fun DataSource.markAgeVerified(userId: UUID) = setProfileTimestamp(userId, column = "age_verified_at")

internal fun DataSource.setProfileDiscoveryStyle(
    userId: UUID,
    style: String,
) {
    require(style in setOf("solo", "together", "both", "social", "ask_in_chat")) {
        "Refusing to UPDATE profiles.discovery_style to invalid value from a test helper: $style"
    }
    connection.use { connection ->
        connection.prepareStatement("UPDATE profiles SET discovery_style = ? WHERE user_id = ?").use { statement ->
            statement.setString(1, style)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}

// Seeds one approved, visible public (profile-album) photo so the "Has photos" filter
// matches. Mirrors the canonical profile_photo projection: media_albums(kind=profile,
// visibility_status=visible) + an image media_asset (ready/approved) + a live album item.
internal fun DataSource.insertApprovedProfilePhoto(userId: UUID) {
    connection.use { connection ->
        val albumId = UUID.randomUUID()
        val assetId = UUID.randomUUID()
        connection
            .prepareStatement(
                """
                INSERT INTO media_assets (
                    id, owner_user_id, media_type, storage_provider, storage_bucket,
                    storage_object_key, content_type, byte_size, processing_status, moderation_status, intended_album_kind
                )
                VALUES (?, ?, 'image', 'gcs', 'pynapli-local-media', ?, 'image/jpeg', 1024, 'ready', 'approved', 'profile')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, assetId)
                statement.setObject(2, userId)
                statement.setString(3, "media/$assetId")
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                "INSERT INTO media_albums (id, owner_user_id, kind, title, visibility_status) " +
                    "VALUES (?, ?, 'profile', 'Profile', 'visible')",
            ).use { statement ->
                statement.setObject(1, albumId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                "INSERT INTO media_album_items (album_id, media_asset_id, owner_user_id, sort_order) VALUES (?, ?, ?, 0)",
            ).use { statement ->
                statement.setObject(1, albumId)
                statement.setObject(2, assetId)
                statement.setObject(3, userId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.insertApprovedPrivateAlbumPhoto(userId: UUID) {
    connection.use { connection ->
        val albumId = UUID.randomUUID()
        val assetId = UUID.randomUUID()
        connection
            .prepareStatement(
                """
                INSERT INTO media_assets (
                    id, owner_user_id, media_type, storage_provider, storage_bucket,
                    storage_object_key, content_type, byte_size, processing_status, moderation_status, intended_album_kind
                )
                VALUES (?, ?, 'image', 'gcs', 'pynapli-local-media', ?, 'image/jpeg', 1024, 'ready', 'approved', 'private')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, assetId)
                statement.setObject(2, userId)
                statement.setString(3, "media/$assetId")
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                "INSERT INTO media_albums (id, owner_user_id, kind, title, visibility_status) " +
                    "VALUES (?, ?, 'private', 'Private', 'visible')",
            ).use { statement ->
                statement.setObject(1, albumId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                "INSERT INTO media_album_items (album_id, media_asset_id, owner_user_id, sort_order) VALUES (?, ?, ?, 0)",
            ).use { statement ->
                statement.setObject(1, albumId)
                statement.setObject(2, assetId)
                statement.setObject(3, userId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.setProfileGender(
    userId: UUID,
    gender: String,
) = setProfileTextColumn(userId, column = "gender", value = gender)

internal fun DataSource.setProfileOrientation(
    userId: UUID,
    orientation: String,
) = setProfileTextColumn(userId, column = "orientation", value = orientation)

internal fun DataSource.setProfileTextColumn(
    userId: UUID,
    column: String,
    value: String,
) {
    require(column in setOf("gender", "orientation")) { "Unexpected profile column: $column" }
    connection.use { connection ->
        connection.prepareStatement("UPDATE profiles SET $column = ? WHERE user_id = ?").use { statement ->
            statement.setString(1, value)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}

internal fun DataSource.setProfileLifestyleTags(
    userId: UUID,
    tags: List<String>,
) = setProfileTagArray(userId, column = "lifestyle_tags", tags = tags)

internal fun DataSource.setProfileExperienceLevel(
    userId: UUID,
    level: String?,
) {
    connection.use { connection ->
        connection.prepareStatement("UPDATE profiles SET experience_level = ? WHERE user_id = ?").use { statement ->
            statement.setString(1, level)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}

internal fun DataSource.setProfileBoundaries(
    userId: UUID,
    tokens: List<String>,
) {
    connection.use { connection ->
        val array = connection.createArrayOf("text", tokens.toTypedArray())
        connection.prepareStatement("UPDATE profiles SET boundaries = ? WHERE user_id = ?").use { statement ->
            statement.setArray(1, array)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}

internal fun DataSource.setProfileLogisticsTags(
    userId: UUID,
    tags: List<String>,
) = setProfileTagArray(userId, column = "logistics_tags", tags = tags)

internal fun DataSource.setProfileIntents(
    userId: UUID,
    tags: List<String>,
) = setProfileTagArray(userId, column = "intents", tags = tags)

internal fun DataSource.setProfileTagArray(
    userId: UUID,
    column: String,
    tags: List<String>,
) {
    require(column in setOf("lifestyle_tags", "logistics_tags", "intents")) {
        "Refusing to UPDATE arbitrary profile tag column from a test helper: $column"
    }
    connection.use { connection ->
        val array = connection.createArrayOf("text", tags.toTypedArray())
        connection.prepareStatement("UPDATE profiles SET $column = ? WHERE user_id = ?").use { statement ->
            statement.setArray(1, array)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}

internal fun DataSource.setProfileTimestamp(
    userId: UUID,
    column: String,
) {
    require(column in setOf("verified_at", "face_photo_verified_at", "age_verified_at")) {
        "Refusing to UPDATE arbitrary profile column from a test helper: $column"
    }
    connection.use { connection ->
        connection.prepareStatement("UPDATE profiles SET $column = now() WHERE user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
    }
}

internal fun DataSource.insertMatch(
    userA: UUID,
    userB: UUID,
) {
    val (a, b) = canonicalPair(userA, userB)
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO matches (id, user_a, user_b)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, a)
                statement.setObject(3, b)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.setRiskState(
    userId: UUID,
    score: Int,
    tier: String,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO account_risk_states (
                    user_id,
                    score,
                    tier,
                    last_event_at,
                    updated_at
                )
                VALUES (?, ?, ?, now(), now())
                ON CONFLICT (user_id) DO UPDATE
                SET score = EXCLUDED.score,
                    tier = EXCLUDED.tier,
                    last_event_at = EXCLUDED.last_event_at,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setInt(2, score)
                statement.setString(3, tier)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.insertBoostActivation(userId: UUID) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO boost_activations (
                    id,
                    user_id,
                    boost_type,
                    source,
                    starts_at,
                    ends_at,
                    status
                )
                VALUES (?, ?, 'boost', 'inventory', now() - interval '1 minute', now() + interval '29 minutes', 'active')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

/** Seeds a live solo Playing Tonight session (active, owner-approved) for the user. */
internal fun DataSource.insertActiveTonightSession(userId: UUID) {
    val sessionId = UUID.randomUUID()
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO tonight_sessions (
                    id, created_by_user_id, mode, intent, host_mode, map_visible,
                    status, created_at, started_at, expires_at
                )
                VALUES (?, ?, 'solo', 'solo', 'public_meet_first', true,
                        'active', now() - interval '1 minute', now() - interval '1 minute', now() + interval '2 hours')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO tonight_session_participants (session_id, user_id, role, approval_status, approved_at)
                VALUES (?, ?, 'owner', 'approved', now() - interval '1 minute')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.insertDenseDiscoverableUsers(
    count: Int,
    baseLongitude: Double,
    baseLatitude: Double,
) {
    val users =
        List(count) { index ->
            DenseDiscoverableUser(
                id = UUID.nameUUIDFromBytes("dense-grid-user-$index".toByteArray()),
                displayName = "Dense ${index + 1}",
                age = 30 + (index % 25),
                longitude = baseLongitude + ((index % 100) - 50) * 0.00008,
                latitude = baseLatitude + ((index / 100) - 25) * 0.00008,
                relationshipContext = if (index % 3 == 0) "linked" else "single",
            )
        }

    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO users (id, status)
                VALUES (?, 'active')
                """.trimIndent(),
            ).use { statement ->
                users.forEach { user ->
                    statement.setObject(1, user.id)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO profiles (
                    user_id,
                    display_name,
                    display_age,
                    relationship_context,
                    moderation_status,
                    visibility_status
                )
                VALUES (?, ?, ?, ?, 'approved', 'visible')
                """.trimIndent(),
            ).use { statement ->
                users.forEach { user ->
                    statement.setObject(1, user.id)
                    statement.setString(2, user.displayName)
                    statement.setInt(3, user.age)
                    statement.setString(4, user.relationshipContext)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO discovery_settings (
                    user_id,
                    grid_visible,
                    swipe_visible,
                    incognito,
                    show_distance
                )
                VALUES (?, true, true, false, true)
                """.trimIndent(),
            ).use { statement ->
                users.forEach { user ->
                    statement.setObject(1, user.id)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO user_locations (
                    user_id,
                    geom,
                    location_source,
                    accuracy_meters
                )
                VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 'gps', 12)
                """.trimIndent(),
            ).use { statement ->
                users.forEach { user ->
                    statement.setObject(1, user.id)
                    statement.setDouble(2, user.longitude)
                    statement.setDouble(3, user.latitude)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }
}

internal fun DataSource.analyzeGridPlannerTables() {
    connection.use { connection ->
        connection.createStatement().use { statement ->
            listOf(
                "users",
                "profiles",
                "discovery_settings",
                "user_locations",
                "user_blocks",
                "user_hidden_profiles",
                "user_profile_links",
                "boost_activations",
                "media_albums",
                "media_album_items",
                "media_assets",
            ).forEach { tableName ->
                statement.execute("ANALYZE $tableName")
            }
        }
    }
}

private data class DenseDiscoverableUser(
    val id: UUID,
    val displayName: String,
    val age: Int,
    val longitude: Double,
    val latitude: Double,
    val relationshipContext: String,
)

internal fun DataSource.insertBlock(
    blockerUserId: UUID,
    blockedUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO user_blocks (
                    id,
                    blocker_user_id,
                    blocked_user_id
                )
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, blockerUserId)
                statement.setObject(3, blockedUserId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.insertHiddenProfile(
    hiderUserId: UUID,
    hiddenUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO user_hidden_profiles (
                    id,
                    hider_user_id,
                    hidden_user_id
                )
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, hiderUserId)
                statement.setObject(3, hiddenUserId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.insertConsumedPineappleLikeInventory(
    inventoryId: UUID,
    userId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO premium_inventory (id, user_id, inventory_type, quantity, source)
                VALUES (?, ?, 'pineapple_like', 0, 'subscription')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, inventoryId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.selectPremiumInventoryQuantity(inventoryId: UUID): Int =
    connection.use { connection ->
        connection.prepareStatement("SELECT quantity FROM premium_inventory WHERE id = ?").use { statement ->
            statement.setObject(1, inventoryId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

internal fun DataSource.insertActiveTravelLocation(
    userId: UUID,
    city: String,
    latitude: Double,
    longitude: Double,
) {
    insertActiveTravelLocationReturningId(userId, city, latitude, longitude)
}

internal fun DataSource.insertActiveTravelLocationReturningId(
    userId: UUID,
    city: String,
    latitude: Double,
    longitude: Double,
): UUID {
    val id = UUID.randomUUID()
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO travel_locations (id, user_id, city, geom, status)
                VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 'active')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, userId)
                statement.setString(3, city)
                statement.setDouble(4, longitude)
                statement.setDouble(5, latitude)
                statement.executeUpdate()
            }
    }
    return id
}

internal fun DataSource.insertClearedTravelLocation(
    userId: UUID,
    city: String,
    latitude: Double,
    longitude: Double,
    clearedHoursAgo: Long,
): UUID {
    val id = UUID.randomUUID()
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO travel_locations (
                    id, user_id, city, geom, status, active_from, active_until
                )
                VALUES (
                    ?, ?, ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    'cleared',
                    now() - (? * INTERVAL '1 hour'),
                    now() - (? * INTERVAL '1 hour')
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, userId)
                statement.setString(3, city)
                statement.setDouble(4, longitude)
                statement.setDouble(5, latitude)
                // active_from must be < active_until, so offset start by an extra hour.
                statement.setLong(6, clearedHoursAgo + 1)
                statement.setLong(7, clearedHoursAgo)
                statement.executeUpdate()
            }
    }
    return id
}

internal fun DataSource.setVisibleWhileTraveling(
    userId: UUID,
    value: Boolean,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE discovery_settings SET visible_while_traveling = ? WHERE user_id = ?",
            ).use { statement ->
                statement.setBoolean(1, value)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.setSwipeVisible(
    userId: UUID,
    value: Boolean,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE discovery_settings SET swipe_visible = ? WHERE user_id = ?",
            ).use { statement ->
                statement.setBoolean(1, value)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.insertSwipeAction(
    actorUserId: UUID,
    targetUserId: UUID,
    action: String,
    travelLocationId: UUID? = null,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action, travel_location_id)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, actorUserId)
                statement.setObject(3, targetUserId)
                statement.setString(4, action)
                statement.setObject(5, travelLocationId)
                statement.executeUpdate()
            }
    }
}

internal fun DataSource.countSwipeActions(
    actorUserId: UUID,
    targetUserId: UUID,
): Int =
    connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT COUNT(*) FROM swipe_actions WHERE actor_user_id = ? AND target_user_id = ?",
            ).use { statement ->
                statement.setObject(1, actorUserId)
                statement.setObject(2, targetUserId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }

internal fun DataSource.selectActionConsumedInventoryId(actionId: UUID): UUID? =
    connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT consumed_inventory_id FROM swipe_actions WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, actionId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getObject("consumed_inventory_id", UUID::class.java) else null
                }
            }
    }
