package com.pynapli.server.dependencies

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.pynapli.server.media.AutoApproveMediaModerationScanner
import com.pynapli.server.media.ManualReviewMediaModerationScanner
import com.pynapli.server.media.MediaModerationTarget
import com.pynapli.server.media.MediaScanScope
import com.pynapli.server.media.SafeSearchLikelihood
import com.pynapli.server.media.SafeSearchPolicy
import com.pynapli.server.testsupport.testServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The upload-complete scanner wiring: no silent fallback from cloud_vision to manual. */
class MediaModerationWiringTest {
    @Test
    fun cloudVisionWithoutCredentialsFailsInsteadOfFallingBackToManual() {
        val config = testServerConfig().copy(mediaModerationProvider = "cloud_vision", mediaDevAutoApproveUploads = false)

        assertFailsWith<IllegalStateException> { createMediaModerationScanner(config, mediaSigningCredentials = null) }
    }

    @Test
    fun manualAndDevAutoApproveNeedNoCredentials() {
        val manual = testServerConfig().copy(mediaModerationProvider = "manual", mediaDevAutoApproveUploads = false)
        val dev = testServerConfig().copy(mediaModerationProvider = "cloud_vision", mediaDevAutoApproveUploads = true)

        assertIs<ManualReviewMediaModerationScanner>(createMediaModerationScanner(manual, null))
        assertIs<AutoApproveMediaModerationScanner>(createMediaModerationScanner(dev, null))
    }

    @Test
    fun scanScopeSendsOnlyProfilePhotosToTheScannerByDefault() {
        val default = MediaScanScope()
        assertTrue(default.scans(MediaModerationTarget.PROFILE_PHOTO))
        assertFalse(default.scans(MediaModerationTarget.PRIVATE_ALBUM))
        assertFalse(default.scans(MediaModerationTarget.CHAT_MEDIA))
        assertFalse(testServerConfig().mediaModerationScanPrivateMedia)

        val optedIn = MediaScanScope(scanPrivateMedia = true)
        assertTrue(optedIn.scans(MediaModerationTarget.PRIVATE_ALBUM))
        assertTrue(optedIn.scans(MediaModerationTarget.CHAT_MEDIA))
    }

    @Test
    fun verificationSelfiesAreFaceCheckedByVisionRegardlessOfThePrivateMediaFlag() {
        val credentials = GoogleCredentials.create(AccessToken("test", null))
        val cloudVision = testServerConfig().copy(mediaModerationProvider = "cloud_vision", mediaModerationScanPrivateMedia = false)

        assertNotNull(createSelfieFaceDetector(cloudVision, credentials))
        assertNull(createSelfieFaceDetector(cloudVision.copy(mediaModerationProvider = "manual"), credentials))
    }

    @Test
    fun thresholdsComeFromConfigWithSpecDefaults() {
        assertEquals(SafeSearchPolicy(), testServerConfig().safeSearchPolicy())
        assertEquals(SafeSearchLikelihood.UNLIKELY, SafeSearchPolicy().maxAdult)
        assertEquals(SafeSearchLikelihood.POSSIBLE, SafeSearchPolicy().maxViolence)
        assertEquals(SafeSearchLikelihood.LIKELY, SafeSearchPolicy().maxRacy)

        val tuned =
            testServerConfig()
                .copy(mediaModerationMaxAdult = "very_unlikely", mediaModerationMaxViolence = "UNLIKELY", mediaModerationMaxRacy = "POSSIBLE")
                .safeSearchPolicy()
        assertEquals(SafeSearchLikelihood.VERY_UNLIKELY, tuned.maxAdult)
        assertEquals(SafeSearchLikelihood.UNLIKELY, tuned.maxViolence)
        assertEquals(SafeSearchLikelihood.POSSIBLE, tuned.maxRacy)
    }
}
