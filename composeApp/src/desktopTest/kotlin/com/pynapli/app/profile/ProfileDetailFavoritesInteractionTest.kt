package com.pynapli.app.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.network.dto.PublicProfileDto
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileDetailFavoritesInteractionTest {
    private val card = GridProfileCardUi(id = "u1", displayName = "Maya", online = false, distanceLabel = "2 km")

    private fun profile(
        favorite: Boolean,
        lastActive: String?,
        online: Boolean = false,
    ) = PublicProfileDto(
        userId = "u1",
        displayName = "Maya",
        discoveryStyle = "both",
        online = online,
        lastActive = lastActive,
        favorite = favorite,
        allowGridMessages = true,
        allowFlirts = true,
        allowAlbumRequests = true,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun starLastSeenAndLikeBackShowOnTheProfile() =
        runComposeUiTest {
            var toggles = 0
            var likeBacks = 0
            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        ProfileDetailSheet(
                            state = ProfileDetailUiState(selectedCard = card, profile = profile(favorite = false, lastActive = "active_today")),
                            onDismiss = {},
                            onMessage = {},
                            onSpark = {},
                            onRequestAlbum = {},
                            onViewPrivateAlbum = {},
                            onHide = {},
                            onBlock = {},
                            onReport = { _, _ -> },
                            onToggleFavorite = { toggles += 1 },
                            likeBack = LikeBackUi(done = false),
                            onLikeBack = { likeBacks += 1 },
                        )
                    }
                }
            }

            onNodeWithText("Active today").assertExists()
            onNodeWithContentDescription("Add to Favorites").performClick()
            onNodeWithTag(ProfileDetailTags.LIKE_BACK).performClick()
            assertEquals(1, toggles)
            assertEquals(1, likeBacks)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun onlineReplacesLastSeenAndAnsweredLikeIsDone() =
        runComposeUiTest {
            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        ProfileDetailSheet(
                            state =
                                ProfileDetailUiState(
                                    selectedCard = card,
                                    profile = profile(favorite = true, lastActive = "online_now", online = true),
                                ),
                            onDismiss = {},
                            onMessage = {},
                            onSpark = {},
                            onRequestAlbum = {},
                            onViewPrivateAlbum = {},
                            onHide = {},
                            onBlock = {},
                            onReport = { _, _ -> },
                            onToggleFavorite = {},
                            likeBack = LikeBackUi(done = true),
                        )
                    }
                }
            }

            onNodeWithText("Online").assertExists()
            onNodeWithTag(ProfileDetailTags.LAST_SEEN).assertDoesNotExist()
            onNodeWithContentDescription("Remove from Favorites").assertExists()
            onNodeWithText("Liked back").assertExists()
            onNodeWithTag(ProfileDetailTags.LIKE_BACK).assertIsNotEnabled()
        }
}
