package com.pynapli.app.profilehub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.app.profilelink.ProfileLinkUi
import com.pynapli.app.profilelink.ProfileLinksUiState
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfileHubPartnerPsyncTest {
    private val linked =
        ProfileLinkUi(
            linkId = "link-1",
            otherUserId = "partner-1",
            otherDisplayName = "Pat",
            otherPhotoUrl = null,
            status = "active",
            direction = "outgoing",
            createdAt = "2026-11-01T12:00:00Z",
            acceptedAt = "2026-11-01T12:05:00Z",
            unlinkedAt = null,
        )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aLinkedPartnerPanelOffersPsync() =
        runComposeUiTest {
            var started = false
            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        PartnerManagementContent(
                            state = ProfileHubUiState(),
                            profileLinksState = ProfileLinksUiState(current = linked),
                            onBack = {},
                            onAcceptInvite = {},
                            onDeclineInvite = {},
                            onUnlink = {},
                            onPartnerVisibilityChanged = {},
                            onSave = {},
                            onStartPsync = { started = true },
                        )
                    }
                }
            }

            onNodeWithTag(ProfileHubPartnerTags.START_PSYNC).performClick()
            assertTrue(started)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun withoutAPartnerThereIsNoPsyncButton() =
        runComposeUiTest {
            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        PartnerManagementContent(
                            state = ProfileHubUiState(),
                            profileLinksState = ProfileLinksUiState(),
                            onBack = {},
                            onAcceptInvite = {},
                            onDeclineInvite = {},
                            onUnlink = {},
                            onPartnerVisibilityChanged = {},
                            onSave = {},
                            onStartPsync = {},
                        )
                    }
                }
            }

            onNodeWithTag(ProfileHubPartnerTags.START_PSYNC).assertDoesNotExist()
        }
}
