package com.pynapli.server.safety

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddenProfileServiceTest {
    @Test
    fun hideProfileCreatesOneWayDiscoveryHide() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000601")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000602")
        val repository = InMemoryHiddenProfileRepository()
        val service = HiddenProfileService(repository)

        val summary = service.hideProfile(viewer, target.toString())

        assertEquals(target.toString(), summary.hiddenUserId)
        assertTrue(target in repository.listHiddenProfileIds(viewer))
        assertFalse(viewer in repository.listHiddenProfileIds(target))
    }

    @Test
    fun hideProfileRejectsSelfHide() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000611")
        val service = HiddenProfileService(InMemoryHiddenProfileRepository())

        val error = assertFailsWith<SafetyException> {
            service.hideProfile(viewer, viewer.toString())
        }

        assertEquals("HIDE_SELF_FORBIDDEN", error.code)
    }

    @Test
    fun hideProfileRejectsInvalidTarget() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000621")
        val service = HiddenProfileService(InMemoryHiddenProfileRepository())

        val error = assertFailsWith<SafetyException> {
            service.hideProfile(viewer, "not-a-uuid")
        }

        assertEquals("INVALID_HIDDEN_PROFILE_TARGET", error.code)
    }

    @Test
    fun unhideProfileRemovesDiscoveryHide() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000631")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000632")
        val repository = InMemoryHiddenProfileRepository()
        val service = HiddenProfileService(repository)
        service.hideProfile(viewer, target.toString())

        val removed = service.unhideProfile(viewer, target.toString())

        assertTrue(removed)
        assertFalse(target in repository.listHiddenProfileIds(viewer))
    }

    @Test
    fun listHiddenProfilesReturnsDisplayNames() = runBlocking {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000641")
        val target = UUID.fromString("00000000-0000-0000-0000-000000000642")
        val repository = InMemoryHiddenProfileRepository().apply {
            setDisplayName(target, "Riley")
        }
        val service = HiddenProfileService(repository)
        service.hideProfile(viewer, target.toString())

        val response = service.listHiddenProfiles(viewer)

        assertEquals("Riley", response.hiddenProfiles.single().hiddenDisplayName)
        assertEquals(target.toString(), response.hiddenProfiles.single().hiddenUserId)
    }
}

class InMemoryHiddenProfileRepository : HiddenProfileRepository {
    private val records = mutableMapOf<Pair<UUID, UUID>, HiddenProfileRecord>()
    private val displayNames = mutableMapOf<UUID, String>()
    private val now = Instant.parse("2026-05-26T18:00:00Z")

    override suspend fun hideProfile(
        hiderUserId: UUID,
        hiddenUserId: UUID,
        hideIdFactory: () -> UUID
    ): HiddenProfileRecord {
        val key = hiderUserId to hiddenUserId
        val record = records[key]
            ?: HiddenProfileRecord(
                id = hideIdFactory(),
                hiderUserId = hiderUserId,
                hiddenUserId = hiddenUserId,
                createdAt = now
            )
        records[key] = record
        return record
    }

    override suspend fun unhideProfile(hiderUserId: UUID, hiddenUserId: UUID): Boolean =
        records.remove(hiderUserId to hiddenUserId) != null

    override suspend fun listHiddenProfiles(hiderUserId: UUID): List<HiddenProfileSummaryRecord> =
        records.values
            .filter { it.hiderUserId == hiderUserId }
            .sortedWith(compareByDescending<HiddenProfileRecord> { it.createdAt }.thenBy { it.hiddenUserId })
            .map { record ->
                HiddenProfileSummaryRecord(
                    hiddenUserId = record.hiddenUserId,
                    hiddenDisplayName = displayNames[record.hiddenUserId],
                    createdAt = record.createdAt
                )
            }

    override suspend fun listHiddenProfileIds(hiderUserId: UUID): Set<UUID> =
        records.values
            .filter { it.hiderUserId == hiderUserId }
            .mapTo(mutableSetOf()) { it.hiddenUserId }

    fun setDisplayName(userId: UUID, displayName: String) {
        displayNames[userId] = displayName
    }
}
