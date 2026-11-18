package com.pynapli.app.verification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliSheetColumn
import com.pynapli.app.design.PynapliSheetTitle
import com.pynapli.app.design.VerifiedBadge
import com.pynapli.app.design.handCursor
import com.pynapli.shared.network.dto.PhotoVerificationStatus

/**
 * "Get verified": the server's pose, then one selfie doing it. [onPickSelfie] is the platform photo
 * picker; null where the shell has none wired (the selfie action is then hidden, not dead).
 */
@Composable
internal fun PhotoVerificationSheet(
    state: PhotoVerificationUiState,
    retryLabel: String?,
    onDismiss: () -> Unit,
    onRequestChallenge: () -> Unit,
    onPickSelfie: (() -> Unit)?,
) {
    PynapliBottomSheet(onDismiss = onDismiss) {
        PynapliSheetColumn {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VerifiedBadge(size = 20.dp)
                PynapliSheetTitle("Photo verification")
            }
            Text(
                text =
                    "Take a selfie doing the pose we show you. A reviewer compares it with your profile photos. " +
                        "The selfie is never shown on your profile and is deleted after the review.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            when {
                !state.loaded && state.isLoading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))

                state.status == PhotoVerificationStatus.VERIFIED ->
                    StatusLine("You're verified. Your profile shows the verified badge.")

                state.status == PhotoVerificationStatus.PENDING ->
                    StatusLine("Your selfie is being reviewed. We'll let you know when it's done.")

                state.status == PhotoVerificationStatus.CHALLENGE -> {
                    Text(
                        text = "Your pose",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = state.poseInstruction.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag(PhotoVerificationTags.POSE),
                    )
                    Text(
                        text = "Face the camera in good light, on your own. Take it within 10 minutes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (onPickSelfie != null) {
                        Button(
                            onClick = onPickSelfie,
                            enabled = state.canTakeSelfie,
                            modifier = Modifier.fillMaxWidth().handCursor(enabled = state.canTakeSelfie),
                        ) {
                            Text(if (state.isSubmitting) "Sending your selfie" else "Take or choose selfie")
                        }
                    } else {
                        StatusLine("Selfie upload isn't available in this version of the app yet.")
                    }
                }

                else -> {
                    if (state.status == PhotoVerificationStatus.REJECTED) {
                        Text(
                            text = state.rejectionReason ?: "Your last selfie wasn't accepted.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        retryLabel?.let { StatusLine(it) }
                    }
                    OutlinedButton(
                        onClick = onRequestChallenge,
                        enabled = state.canRequestChallenge,
                        modifier = Modifier.fillMaxWidth().handCursor(enabled = state.canRequestChallenge),
                    ) {
                        Text(
                            when {
                                state.isRequestingChallenge -> "Getting your pose"
                                state.status == PhotoVerificationStatus.REJECTED -> "Try again"
                                else -> "Get my pose"
                            },
                        )
                    }
                }
            }

            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

internal object PhotoVerificationTags {
    const val POSE = "photo-verification-pose"
}

/** Profile Hub row value for the current photo verification status. */
internal fun PhotoVerificationUiState.hubSummary(): String =
    when (status) {
        PhotoVerificationStatus.VERIFIED -> "Verified"
        PhotoVerificationStatus.PENDING -> "Under review"
        PhotoVerificationStatus.CHALLENGE -> "Take your selfie"
        PhotoVerificationStatus.REJECTED -> "Not accepted, try again"
        else -> "Get verified"
    }
