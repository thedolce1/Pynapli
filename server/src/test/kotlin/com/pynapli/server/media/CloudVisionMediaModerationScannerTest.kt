package com.pynapli.server.media

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the pure decision logic of the Cloud Vision scanner: the SafeSearch likelihood -> decision
 * mapping ([SafeSearchPolicy]) and the scanner's fail-safe guards that never hit the network (non-image
 * content type, missing storage coordinates). The live HTTP path needs a real Vision endpoint and is
 * exercised by ops during media go-live, not in unit tests.
 */
class CloudVisionMediaModerationScannerTest {
    private fun annotation(
        adult: String? = "VERY_UNLIKELY",
        racy: String? = "VERY_UNLIKELY",
        violence: String? = "VERY_UNLIKELY",
    ) = SafeSearchAnnotation(adult = adult, racy = racy, violence = violence)

    @Test
    fun approvesWhenAllCategoriesBelowReviewFloor() {
        val decision = SafeSearchPolicy().decide(annotation(adult = "UNLIKELY", racy = "VERY_UNLIKELY"))
        assertEquals(MediaModerationDecision.Approved, decision)
    }

    @Test
    fun holdsForReviewAtPossible() {
        val decision = SafeSearchPolicy().decide(annotation(racy = "POSSIBLE"))
        assertEquals(MediaModerationDecision.PendingReview, decision)
    }

    @Test
    fun rejectsAtLikely() {
        val decision = SafeSearchPolicy().decide(annotation(adult = "LIKELY"))
        assertTrue(decision is MediaModerationDecision.Rejected, "expected Rejected, got $decision")
        assertEquals("safe_search_adult_likely", decision.reason)
    }

    @Test
    fun rejectsAtVeryLikely() {
        val decision = SafeSearchPolicy().decide(annotation(violence = "VERY_LIKELY"))
        assertTrue(decision is MediaModerationDecision.Rejected, "expected Rejected, got $decision")
    }

    @Test
    fun worstCategoryWins() {
        // racy POSSIBLE (review) but adult VERY_LIKELY (reject) -> reject dominates.
        val decision = SafeSearchPolicy().decide(annotation(adult = "VERY_LIKELY", racy = "POSSIBLE"))
        assertTrue(decision is MediaModerationDecision.Rejected, "expected Rejected, got $decision")
        assertEquals("safe_search_adult_very_likely", decision.reason)
    }

    @Test
    fun presentButUnknownLikelihoodsAreTreatedAsBenign() {
        // Contract: a present annotation whose categories are all UNKNOWN (ordinal 0, below the
        // POSSIBLE review floor) is treated as benign -> Approved. The *absence* of a SafeSearch
        // annotation entirely is the fail-safe case, and scan() maps that (and every error) to
        // PendingReview before this policy is consulted.
        val decision = SafeSearchPolicy().decide(annotation(adult = null, racy = null, violence = null))
        assertEquals(MediaModerationDecision.Approved, decision)
    }

    @Test
    fun ignoredCategoriesDoNotTriggerByDefault() {
        // medical/spoof are not in the default evaluated set, so a high medical likelihood is ignored.
        val decision =
            SafeSearchPolicy().decide(
                SafeSearchAnnotation(
                    adult = "VERY_UNLIKELY",
                    racy = "VERY_UNLIKELY",
                    violence = "VERY_UNLIKELY",
                    medical = "VERY_LIKELY",
                    spoof = "VERY_LIKELY",
                ),
            )
        assertEquals(MediaModerationDecision.Approved, decision)
    }

    @Test
    fun nonImageAssetHeldForReviewWithoutNetwork() =
        runBlocking {
            // No credentials/http wired; a non-image asset must short-circuit to PendingReview before
            // any network use. This would throw/NPE if it tried to call out.
            val scanner =
                CloudVisionMediaModerationScanner(
                    credentials =
                        com.google.auth.oauth2.GoogleCredentials
                            .create(
                                com.google.auth.oauth2
                                    .AccessToken("test", null),
                            ),
                )
            val decision = scanner.scan(asset(contentType = "video/mp4"))
            assertEquals(MediaModerationDecision.PendingReview, decision)
        }

    @Test
    fun blankStorageCoordinatesHeldForReviewWithoutNetwork() =
        runBlocking {
            val scanner =
                CloudVisionMediaModerationScanner(
                    credentials =
                        com.google.auth.oauth2.GoogleCredentials
                            .create(
                                com.google.auth.oauth2
                                    .AccessToken("test", null),
                            ),
                )
            val decision = scanner.scan(asset(storageObjectKey = ""))
            assertEquals(MediaModerationDecision.PendingReview, decision)
        }

    private fun asset(
        contentType: String = "image/jpeg",
        storageBucket: String = "pynapli-media",
        storageObjectKey: String = "media/abc",
    ) = MediaAssetRecord(
        mediaAssetId = UUID.randomUUID(),
        ownerUserId = UUID.randomUUID(),
        mediaType = "photo",
        storageProvider = "gcs",
        storageBucket = storageBucket,
        storageObjectKey = storageObjectKey,
        contentType = contentType,
        byteSize = 1_024L,
        pixelWidth = 100,
        pixelHeight = 100,
        moderationStatus = "pending",
        processingStatus = "uploaded",
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        deletedAt = null,
    )
}
