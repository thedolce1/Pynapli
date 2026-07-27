package com.pynapli.app.tonight

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StartTonightSheetTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun selectingAProfilePhotoAttachesItToTheProposal() =
        runComposeUiTest {
            var captured: ProposeTonightSessionRequestDto? = null
            setContent {
                StartTonightSheetBody(
                    isSubmitting = false,
                    photoOptions =
                        listOf(
                            TonightPhotoOptionUi(assetId = "a1", thumbnailUrl = ""),
                            TonightPhotoOptionUi(assetId = "a2", thumbnailUrl = ""),
                        ),
                    onSubmit = { captured = it },
                )
            }
            waitForIdle()

            onNodeWithTag(StartTonightTags.photo("a2"), useUnmergedTree = true).performClick()
            onNodeWithTag(StartTonightTags.SUBMIT, useUnmergedTree = true).performClick()

            assertEquals("a2", captured?.photoMediaAssetId)
            // Defaults: a solo session.
            assertEquals("solo", captured?.mode)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun proposalHasNoPhotoWhenNoneSelected() =
        runComposeUiTest {
            var captured: ProposeTonightSessionRequestDto? = null
            setContent {
                StartTonightSheetBody(
                    isSubmitting = false,
                    photoOptions = listOf(TonightPhotoOptionUi(assetId = "a1", thumbnailUrl = "")),
                    onSubmit = { captured = it },
                )
            }
            waitForIdle()

            onNodeWithTag(StartTonightTags.SUBMIT, useUnmergedTree = true).performClick()

            assertNull(captured?.photoMediaAssetId)
        }
}
