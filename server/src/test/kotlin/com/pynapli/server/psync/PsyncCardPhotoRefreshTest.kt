package com.pynapli.server.psync

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.SwipeCardDto
import io.kotest.core.spec.style.StringSpec
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PsyncCardPhotoRefreshTest :
    StringSpec({
        val cover = UUID.randomUUID()
        val second = UUID.randomUUID()
        val partnerThumb = UUID.randomUUID()
        val resolver =
            object : MediaAssetUrlResolver {
                override fun publicContentUrl(mediaAssetId: UUID): String = "https://cdn.test/media/$mediaAssetId?sig=fresh"
            }

        "the asset id is recovered from every resolver URL shape" {
            assertEquals(cover, "http://10.0.2.2:8080/v1/media/uploads/local/$cover".mediaAssetIdOrNull())
            assertEquals(cover, "https://storage.googleapis.com/bucket/media/$cover?X-Goog-Signature=abc&X-Goog-Expires=900".mediaAssetIdOrNull())
            assertEquals(cover, "https://cdn.pynapli.test/media/$cover?Expires=1&KeyName=k&Signature=s".mediaAssetIdOrNull())
            assertNull("https://example.test/avatar.png".mediaAssetIdOrNull())
        }

        "a snapshot card gets freshly issued photo URLs for the cover, the carousel and the partner" {
            val stale =
                SwipeCardDto(
                    userId = UUID.randomUUID().toString(),
                    displayName = "Sam",
                    rankToken = "rank",
                    photoUrl = "https://storage.googleapis.com/bucket/media/$cover?X-Goog-Signature=old",
                    photoUrls =
                        listOf(
                            "https://storage.googleapis.com/bucket/media/$cover?X-Goog-Signature=old",
                            "https://storage.googleapis.com/bucket/media/$second?X-Goog-Signature=old",
                        ),
                    linkedPartner =
                        LinkedPartnerMiniDto(
                            userId = UUID.randomUUID().toString(),
                            thumbnailUrl = "https://storage.googleapis.com/bucket/media/$partnerThumb?X-Goog-Signature=old",
                        ),
                )

            val fresh = stale.withFreshPhotoUrls(resolver)

            assertEquals("https://cdn.test/media/$cover?sig=fresh", fresh.photoUrl)
            assertEquals(
                listOf("https://cdn.test/media/$cover?sig=fresh", "https://cdn.test/media/$second?sig=fresh"),
                fresh.photoUrls,
            )
            assertEquals("https://cdn.test/media/$partnerThumb?sig=fresh", fresh.linkedPartner?.thumbnailUrl)
        }

        "an unrecognised URL and a missing resolver leave the card untouched" {
            val card = SwipeCardDto(userId = "u", displayName = "Sam", rankToken = "rank", photoUrl = "https://example.test/avatar.png")
            assertEquals(card, card.withFreshPhotoUrls(resolver))
            assertEquals(card, card.withFreshPhotoUrls(null))
        }
    })
