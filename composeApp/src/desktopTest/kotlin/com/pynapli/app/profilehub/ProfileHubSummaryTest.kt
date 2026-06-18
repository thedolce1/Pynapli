package com.pynapli.app.profilehub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.DiscoverySettingsDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.ProfileDto
import kotlin.test.Test

class ProfileHubSummaryTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun unlinkedSummaryHidesMemberNumberAndPartnerMetric() =
        runComposeUiTest {
            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        ProfileSummary(
                            state =
                                ProfileHubUiState(
                                    me = sampleMe(),
                                ),
                            profileImageUrl = null,
                            linkedPartnerLabel = null,
                            profilePhotoCount = 2,
                            privatePhotoCount = 4,
                        )
                    }
                }
            }

            onNodeWithText("Sebastien").assertExists()
            onNodeWithText("39 - King West").assertExists()
            onNodeWithText("Member owner-use").assertDoesNotExist()
            onNodeWithText("Partner").assertDoesNotExist()
        }
}

private fun sampleMe(): MeDto =
    MeDto(
        user = AuthUserDto(userId = "owner-user-id", status = "active"),
        profile =
            ProfileDto(
                userId = "owner-user-id",
                displayName = "Sebastien",
                displayAge = 39,
                neighborhood = "King West",
                discoveryStyle = "both",
                profileCompletenessScore = 80,
                moderationStatus = "approved",
                visibilityStatus = "visible",
            ),
        discoverySettings =
            DiscoverySettingsDto(
                gridVisible = true,
                swipeVisible = true,
                eventsVisible = true,
                showDistance = true,
                showInViewedLists = true,
                incognito = false,
                allowGridMessages = true,
                gridMessagePolicy = "everyone",
                allowFlirts = true,
                allowIntroNotes = true,
                allowAlbumRequests = true,
                soloEnabled = true,
                togetherEnabled = true,
                socialEnabled = true,
            ),
    )
