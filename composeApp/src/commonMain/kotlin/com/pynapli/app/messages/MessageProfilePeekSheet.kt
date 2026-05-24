package com.pynapli.app.messages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pynapli.app.profile.ProfileDetailUiState
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.settings.AppSettings

@Composable
fun MessageProfilePeekSheet(
    state: ProfileDetailUiState,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onSpark: () -> Unit,
    onRequestAlbum: () -> Unit,
    distanceUnit: String = AppSettings.UNIT_METRIC,
    modifier: Modifier = Modifier
) {
    val card = state.selectedCard ?: return
    val profile = state.profile
    val displayName = profile?.displayName ?: card.displayName
    val displayAge = profile?.displayAge
    val distanceLabel = formatDistanceForUnit(
        distanceMeters = profile?.distanceMeters ?: card.distanceMeters,
        fallbackLabel = profile?.distanceLabel ?: card.distanceLabel,
        distanceUnit = distanceUnit
    )
    val photoUrl = profile?.profilePhotoUrl ?: card.thumbnailUrl

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 8.dp),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.46f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.74f))
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ProfilePeekHero(
                        displayName = displayName,
                        displayAge = displayAge,
                        online = card.online,
                        distanceLabel = distanceLabel,
                        photoUrl = photoUrl,
                        onDismiss = onDismiss
                    )

                    when {
                        state.isLoading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        state.errorMessage != null -> {
                            Text(
                                text = state.errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        else -> {
                            ProfilePeekSummary(state)
                            state.flirtFeedback?.let { feedback ->
                                FeedbackText(feedback)
                            }
                            state.albumFeedback?.let { feedback ->
                                FeedbackText(feedback)
                            }
                        }
                    }
                }

                if (state.errorMessage == null) {
                    ProfilePeekActions(
                        canMessage = profile?.allowGridMessages != false,
                        canSpark = profile?.allowFlirts != false,
                        canRequestAlbum = profile?.allowAlbumRequests != false,
                        isSparkLoading = state.isSendingFlirt,
                        isAlbumLoading = state.isRequestingAlbum || state.isLoadingPrivateAlbum,
                        albumStatus = profile?.privateAlbumAccessStatus ?: "none",
                        onMessage = onMessage,
                        onSpark = onSpark,
                        onRequestAlbum = onRequestAlbum,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .navigationBarsPadding()
                    )
                }
            }
        }
    }
}
