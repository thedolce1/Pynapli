package com.pynapli.app.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.SamplePublicProfileRepository
import kotlin.test.Test
import kotlin.test.assertTrue

class GridVerificationRowInteractionTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun verificationRowRendersAndCtaRoutesIntoAgeVerification() =
        runComposeUiTest {
            // The grid feed rejects with AGE_VERIFICATION_REQUIRED (server gate for lifestyle
            // filters). The screen must render the verify prompt and its CTA must invoke
            // onOpenAgeVerification (which the host wires to the Profile-Hub flow).
            var verificationOpened = false
            val gridStateHolder =
                GridStateHolder(
                    AgeVerificationFailingRepository(),
                )
            val profileDetailStateHolder = ProfileDetailStateHolder(SamplePublicProfileRepository())

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        GridScreen(
                            stateHolder = gridStateHolder,
                            profileDetailStateHolder = profileDetailStateHolder,
                            profileImageUrl = null,
                            onOpenAgeVerification = { verificationOpened = true },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) {
                gridStateHolder.state.value.verificationRequired
            }
            waitForIdle()

            onNodeWithTag(GridVerificationTags.ROW).assertIsDisplayed()
            onNodeWithTag(GridVerificationTags.CTA).performClick()
            waitForIdle()

            assertTrue(verificationOpened, "Verify CTA must invoke onOpenAgeVerification")
        }
}

private class AgeVerificationFailingRepository : GridFeedRepository {
    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi =
        throw GridFeedException(
            code = "AGE_VERIFICATION_REQUIRED",
            message = "Age verification is required for this feature.",
        )
}
