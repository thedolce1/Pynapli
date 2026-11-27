package com.pynapli.server.media

import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Photo pre-screening (TRUST_AND_SAFETY_SPEC "Photo pre-screening") with the Vision client faked:
 * the SafeSearch policy's ceilings on both sides of each boundary, fail-closed handling of unknown
 * likelihoods, errors and timeouts, and the response parser. No network.
 */
class CloudVisionMediaModerationScannerTest {
    private fun annotation(
        adult: String? = "VERY_UNLIKELY",
        racy: String? = "VERY_UNLIKELY",
        violence: String? = "VERY_UNLIKELY",
    ) = SafeSearchAnnotation(adult = adult, racy = racy, violence = violence)

    private val policy = SafeSearchPolicy()

    // ---- Policy boundaries ----

    @Test
    fun cleanPhotoIsApproved() {
        assertEquals(MediaModerationDecision.Approved, policy.decide(annotation()))
    }

    @Test
    fun adultCeilingIsUnlikely() {
        assertEquals(MediaModerationDecision.Approved, policy.decide(annotation(adult = "UNLIKELY")))
        assertEquals(MediaModerationDecision.PendingReview, policy.decide(annotation(adult = "POSSIBLE")))
        assertEquals(MediaModerationDecision.PendingReview, policy.decide(annotation(adult = "LIKELY")))
        assertEquals(MediaModerationDecision.PendingReview, policy.decide(annotation(adult = "VERY_LIKELY")))
    }

    @Test
    fun violenceCeilingIsPossible() {
        assertEquals(MediaModerationDecision.Approved, policy.decide(annotation(violence = "UNLIKELY")))
        assertEquals(MediaModerationDecision.Approved, policy.decide(annotation(violence = "POSSIBLE")))
        // LIKELY is above the ceiling and is also the severe signal (see below): never Approved.
        assertTrue(policy.decide(annotation(violence = "LIKELY")) != MediaModerationDecision.Approved)
    }

    @Test
    fun racyCeilingIsLikelyBecauseSuggestiveIsFineOnPublicPhotos() {
        assertEquals(MediaModerationDecision.Approved, policy.decide(annotation(racy = "POSSIBLE")))
        assertEquals(MediaModerationDecision.Approved, policy.decide(annotation(racy = "LIKELY")))
        assertEquals(MediaModerationDecision.PendingReview, policy.decide(annotation(racy = "VERY_LIKELY")))
    }

    @Test
    fun explicitAdultContentIsQueuedNotRejected() {
        // Never auto-reject on adult/racy: a person decides.
        assertEquals(MediaModerationDecision.PendingReview, policy.decide(annotation(adult = "VERY_LIKELY", racy = "VERY_LIKELY")))
    }

    @Test
    fun strongViolenceIsTheOnlySevereSignal() {
        for (level in listOf("LIKELY", "VERY_LIKELY")) {
            val decision = policy.decide(annotation(violence = level))
            assertTrue(decision is MediaModerationDecision.Rejected, "expected the severe signal at $level, got $decision")
            assertEquals(MediaModerationSeverity.SEVERE, decision.severity)
            assertEquals("harassment_or_threats", decision.reportCategory)
            assertEquals("safe_search_violence_${level.lowercase()}", decision.reason)
        }
    }

    @Test
    fun unknownOrMissingLikelihoodIsQueued() {
        // Fail closed: a category Vision could not rate is not evidence the photo is clean.
        assertEquals(MediaModerationDecision.PendingReview, policy.decide(annotation(adult = null)))
        assertEquals(MediaModerationDecision.PendingReview, policy.decide(annotation(racy = "UNKNOWN")))
        assertEquals(MediaModerationDecision.PendingReview, policy.decide(annotation(violence = "SOMETHING_NEW")))
        assertEquals(MediaModerationDecision.PendingReview, policy.decide(annotation(adult = null, racy = null, violence = null)))
    }

    @Test
    fun medicalAndSpoofAreIgnored() {
        val decision =
            policy.decide(
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
    fun configuredCeilingsAreHonoured() {
        val strict = SafeSearchPolicy(maxAdult = SafeSearchLikelihood.VERY_UNLIKELY, maxRacy = SafeSearchLikelihood.POSSIBLE)
        assertEquals(MediaModerationDecision.PendingReview, strict.decide(annotation(adult = "UNLIKELY")))
        assertEquals(MediaModerationDecision.PendingReview, strict.decide(annotation(racy = "LIKELY")))
        assertEquals(MediaModerationDecision.Approved, strict.decide(annotation(racy = "POSSIBLE")))
    }

    // ---- Scanner with a faked Vision client ----

    @Test
    fun cleanAnswerFromVisionApproves() =
        runBlocking {
            var askedFor: String? = null
            val scanner =
                CloudVisionMediaModerationScanner(
                    client = { uri ->
                        askedFor = uri
                        annotation(racy = "LIKELY")
                    },
                )
            assertEquals(MediaModerationDecision.Approved, scanner.scan(asset()))
            assertEquals("gs://pynapli-media/media/abc", askedFor)
        }

    @Test
    fun flaggedAnswerFromVisionQueues() =
        runBlocking {
            val scanner = CloudVisionMediaModerationScanner(client = { annotation(adult = "POSSIBLE") })
            assertEquals(MediaModerationDecision.PendingReview, scanner.scan(asset()))
        }

    @Test
    fun clientErrorQueues() =
        runBlocking {
            val scanner = CloudVisionMediaModerationScanner(client = { throw java.io.IOException("quota") })
            assertEquals(MediaModerationDecision.PendingReview, scanner.scan(asset()))
        }

    @Test
    fun noUsableAnswerQueues() =
        runBlocking {
            val scanner = CloudVisionMediaModerationScanner(client = { null })
            assertEquals(MediaModerationDecision.PendingReview, scanner.scan(asset()))
        }

    @Test
    fun slowVisionTimesOutToTheQueue() =
        runBlocking {
            val scanner =
                CloudVisionMediaModerationScanner(
                    client = {
                        Thread.sleep(10_000)
                        annotation()
                    },
                    scanTimeout = Duration.ofMillis(200),
                )
            val started = System.nanoTime()
            val decision = scanner.scan(asset())
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertEquals(MediaModerationDecision.PendingReview, decision)
            assertTrue(elapsedMillis < 5_000, "the timeout must interrupt the blocked call, took ${elapsedMillis}ms")
        }

    @Test
    fun nonImageAssetQueuedWithoutCallingVision() =
        runBlocking {
            val scanner = CloudVisionMediaModerationScanner(client = { error("must not be called") })
            assertEquals(MediaModerationDecision.PendingReview, scanner.scan(asset(contentType = "video/mp4")))
        }

    @Test
    fun blankStorageCoordinatesQueuedWithoutCallingVision() =
        runBlocking {
            val scanner = CloudVisionMediaModerationScanner(client = { error("must not be called") })
            assertEquals(MediaModerationDecision.PendingReview, scanner.scan(asset(storageObjectKey = "")))
        }

    @Test
    fun credentialsConstructorStillShortCircuitsNonImages() =
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
            assertEquals(MediaModerationDecision.PendingReview, scanner.scan(asset(contentType = "video/mp4")))
        }

    // ---- Response parsing ----

    @Test
    fun parserReadsTheAnnotation() {
        val parsed =
            parseSafeSearchResponse(
                """{"responses":[{"safeSearchAnnotation":{"adult":"UNLIKELY","racy":"LIKELY","violence":"VERY_UNLIKELY","spoof":"UNLIKELY","medical":"UNLIKELY"}}]}""",
            )
        assertEquals("LIKELY", parsed?.racy)
    }

    @Test
    fun parserTreatsAPerImageErrorAsNoAnswer() {
        // Vision reports an unreadable object as `error` on an HTTP 200.
        assertNull(parseSafeSearchResponse("""{"responses":[{"error":{"code":7,"message":"denied"}}]}"""))
        assertNull(parseSafeSearchResponse("""{"responses":[{}]}"""))
        assertNull(parseSafeSearchResponse("""{"responses":[]}"""))
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
