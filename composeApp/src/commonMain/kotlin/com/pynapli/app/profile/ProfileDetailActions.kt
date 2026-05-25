package com.pynapli.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ProfileActions(
    allowGridMessages: Boolean,
    allowFlirts: Boolean,
    allowAlbumRequests: Boolean,
    privateAlbumAccessStatus: String,
    isSendingFlirt: Boolean,
    isRequestingAlbum: Boolean,
    isLoadingPrivateAlbum: Boolean,
    flirtFeedback: String?,
    albumFeedback: String?,
    profileLinkAction: ProfileLinkActionState?,
    onMessage: () -> Unit,
    onSpark: () -> Unit,
    onRequestAlbum: () -> Unit,
    onViewPrivateAlbum: () -> Unit,
    onInviteProfileLink: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onMessage,
            enabled = allowGridMessages,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Filled.ChatBubble,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Message",
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onSpark,
                enabled = allowFlirts && !isSendingFlirt,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (isSendingFlirt) "Sending" else "Spark",
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1
                )
            }
            OutlinedButton(
                onClick = if (privateAlbumAccessStatus == "granted") onViewPrivateAlbum else onRequestAlbum,
                enabled = when (privateAlbumAccessStatus) {
                    "granted" -> !isLoadingPrivateAlbum
                    "pending" -> false
                    else -> allowAlbumRequests && !isRequestingAlbum
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = when {
                        privateAlbumAccessStatus == "granted" && isLoadingPrivateAlbum -> "Loading"
                        privateAlbumAccessStatus == "granted" -> "View"
                        privateAlbumAccessStatus == "pending" -> "Pending"
                        isRequestingAlbum -> "Requesting"
                        else -> "Request"
                    },
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1
                )
            }
        }
        profileLinkAction?.let { action ->
            OutlinedButton(
                onClick = onInviteProfileLink,
                enabled = action.enabled && !action.isSubmitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (action.isSubmitting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = action.label,
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1
                )
            }
        }
        (albumFeedback ?: flirtFeedback)?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        profileLinkAction?.feedbackMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        profileLinkAction?.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun SwipeProfileActions(
    isSubmitting: Boolean,
    onPass: () -> Unit,
    onLike: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onPass,
            enabled = !isSubmitting,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = if (isSubmitting) "Saving" else "Pass",
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1
            )
        }
        Button(
            onClick = onLike,
            enabled = !isSubmitting,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = if (isSubmitting) "Saving" else "Like",
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun MatchedSwipeProfileActions(
    onMessage: () -> Unit
) {
    Button(
        onClick = onMessage,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.ChatBubble,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Message",
            modifier = Modifier.padding(start = 6.dp),
            maxLines = 1
        )
    }
}
