package com.pynapli.server.grid

import com.pynapli.server.media.LocalMediaAssetUrlResolver
import com.pynapli.server.media.MediaImageSet
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * G2-2 ticket 2: every Grid card photo carries the stable key that names its bytes next to the URL,
 * and the key always matches the URL actually returned (thumbnail when ready, else the original).
 */
class GridCardImageKeysTest {
    private val resolver = LocalMediaAssetUrlResolver("http://media.test")
    private val photo = UUID.randomUUID()
    private val partnerPhoto = UUID.randomUUID()
    private val card =
        GridCardRecord(
            userId = UUID.randomUUID(),
            displayName = "Maya",
            thumbnailMediaAssetId = photo,
            showDistance = true,
            distanceMeters = 1_200.0,
            linkedPartnerUserId = UUID.randomUUID(),
            linkedPartnerDisplayName = "Noa",
            linkedPartnerThumbnailMediaAssetId = partnerPhoto,
        )

    @Test
    fun aReadyThumbnailIsServedWithItsVariantKey() {
        val dto = card.toGridCardDto(emptySet(), resolver, images = MediaImageSet(resolver, setOf(photo, partnerPhoto)))

        assertEquals("http://media.test/v1/media/uploads/local/$photo/thumb", dto.thumbnailUrl)
        assertEquals("$photo:v1-thumb-480", dto.thumbnailCacheKey)
        assertEquals("http://media.test/v1/media/uploads/local/$partnerPhoto/thumb", dto.linkedPartner?.thumbnailUrl)
        assertEquals("$partnerPhoto:v1-thumb-480", dto.linkedPartner?.thumbnailCacheKey)
    }

    @Test
    fun withoutAThumbnailTheOriginalIsServedWithTheOriginalKey() {
        val dto = card.toGridCardDto(emptySet(), resolver)

        assertEquals("http://media.test/v1/media/uploads/local/$photo", dto.thumbnailUrl)
        assertEquals("$photo:orig", dto.thumbnailCacheKey)
        assertEquals("$partnerPhoto:orig", dto.linkedPartner?.thumbnailCacheKey)
    }

    @Test
    fun aLockedCardGetsTheSmallPhotoButNoStableKey() {
        val dto =
            card.toLockedGridCardDto(
                token = "locked_x",
                onlineUserIds = emptySet(),
                mediaAssetUrlResolver = resolver,
                images = MediaImageSet(resolver, setOf(photo)),
            )

        assertEquals("http://media.test/v1/media/uploads/local/$photo/thumb", dto.thumbnailUrl)
        assertNull(dto.thumbnailCacheKey)
    }

    @Test
    fun aCardWithoutAPhotoHasNoKey() {
        val dto = card.copy(thumbnailMediaAssetId = null).toGridCardDto(emptySet(), resolver)

        assertNull(dto.thumbnailUrl)
        assertNull(dto.thumbnailCacheKey)
    }
}
