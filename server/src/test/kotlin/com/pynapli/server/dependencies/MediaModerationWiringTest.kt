package com.pynapli.server.dependencies

import com.pynapli.server.media.AutoApproveMediaModerationScanner
import com.pynapli.server.media.ManualReviewMediaModerationScanner
import com.pynapli.server.media.SafeSearchLikelihood
import com.pynapli.server.media.SafeSearchPolicy
import com.pynapli.server.testsupport.testServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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
